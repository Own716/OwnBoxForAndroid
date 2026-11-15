package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderAutoSelectTest {

    private fun socksEntity(
        id: Long,
        name: String,
        status: Int = 0,
        ping: Int = 0,
    ): ProxyEntity {
        return ProxyEntity(
            id = id,
            groupId = 10L,
            type = ProxyEntity.TYPE_SOCKS,
            status = status,
            ping = ping,
            socksBean = SOCKSBean().apply {
                initializeDefaultValues()
                this.name = name
                serverAddress = "198.51.100.$id"
                serverPort = 1080
            },
        )
    }

    @Test
    fun buildAutoSelectOutboundSerializesOnlyActiveFields() {
        val m1 = socksEntity(1L, "SG 01", status = 1, ping = 42)
        val m2 = socksEntity(2L, "SG 02", status = 1, ping = 88)
        val m3 = socksEntity(3L, "SG 03", status = 3, ping = 15)

        val balancer = BalancerBean().apply {
            selectionMode = BalancerBean.SELECTION_PRIORITY
            balanceEnabled = true
            balanceMode = BalancerBean.BALANCE_PER_CONNECTION
            balanceIntervalSec = 45
            interval = 180
            tolerance = 40
            interruptOnSwitch = true
            learnFromConnections = false
            testUrl = "https://cp.cloudflare.com/generate_204"
        }

        val option = buildAutoSelectOutbound(
            balancer = balancer,
            memberEntities = listOf(m1, m2, m3),
            memberTags = listOf("tag-sg-1", "tag-sg-2", "tag-sg-3"),
            customTag = "SG-Auto",
        )

        val map = option.asMap()
        assertEquals("autoselect", map["type"])
        assertEquals("SG-Auto", map["tag"])
        assertEquals(listOf("tag-sg-1", "tag-sg-2", "tag-sg-3"), map["outbounds"])
        assertEquals("https://cp.cloudflare.com/generate_204", map["url"])
        assertEquals("priority", map["selection_mode"])
        assertEquals(true, map["balance_enabled"])
        assertEquals("per_connection", map["balance_mode"])
        assertEquals("45s", map["balance_interval"])
        assertEquals("180s", map["check_interval"])
        assertEquals(40, (map["tolerance"] as Number).toInt())
        assertEquals(true, map["interrupt_exist_connections"])
        assertEquals(false, map["learn_from_connections"])

        listOf(
            "rank_top_n",
            "standby_size",
            "intensive_check_count",
            "full_scan_interval",
            "watch_interval",
            "sample_count",
            "max_rtt",
            "dial_retries",
            "failback_checks",
            "failback_delay",
            "interrupt_on_failback",
            "site_sticky_ttl",
            "connectivity_url",
            "initial_delays_ms",
        ).forEach { removed ->
            assertFalse("Removed field $removed must not be emitted", map.containsKey(removed))
        }
    }

    @Test
    fun buildAutoSelectOutboundSeedsInitialDelaysAndPrioritizesHealthyInLatencyMode() {
        val dead = socksEntity(1L, "Dead", status = 2, ping = 0)
        val slow = socksEntity(2L, "Slow", status = 1, ping = 800)
        val fast = socksEntity(3L, "Fast", status = 1, ping = 150)

        val balancer = BalancerBean().apply {
            selectionMode = BalancerBean.SELECTION_LATENCY
            balanceEnabled = false
            interval = 300
        }

        val option = buildAutoSelectOutbound(
            balancer = balancer,
            memberEntities = listOf(dead, slow, fast),
            memberTags = listOf("tag-dead", "tag-slow", "tag-fast"),
            customTag = "SG-Latency",
        )

        val map = option.asMap()
        @Suppress("UNCHECKED_CAST")
        val outbounds = map["outbounds"] as List<String>
        assertEquals(listOf("tag-fast", "tag-slow", "tag-dead"), outbounds)

        @Suppress("UNCHECKED_CAST")
        val initialDelays = map["initial_delays"] as Map<String, Number>
        assertEquals(150, initialDelays["tag-fast"]?.toInt())
        assertEquals(800, initialDelays["tag-slow"]?.toInt())
        assertFalse(initialDelays.containsKey("tag-dead"))
    }

    @Test
    fun buildAutoSelectOutboundUsesDefaultsAndAllowsZeroTolerance() {
        val m1 = socksEntity(1L, "SG 01", status = 0, ping = 0)
        val balancer = BalancerBean().apply {
            initializeDefaultValues()
            tolerance = 0
            testUrl = "https://cp.cloudflare.com/generate_204"
        }

        val option = buildAutoSelectOutbound(
            balancer = balancer,
            memberEntities = listOf(m1),
            memberTags = listOf("tag-sg-1"),
            customTag = "SG-Auto",
        )

        val map = option.asMap()
        assertEquals("autoselect", map["type"])
        assertEquals("latency", map["selection_mode"])
        assertEquals(false, map["balance_enabled"])
        assertEquals("300s", map["check_interval"])
        assertEquals(0, (map["tolerance"] as Number).toInt())
        assertEquals(false, map["interrupt_exist_connections"])
        assertEquals(true, map["learn_from_connections"])
        assertFalse(map.containsKey("connectivity_url"))
        assertFalse(map.containsKey("initial_delays_ms"))
    }

    @Test
    fun chainWithMiddleBalancerBuildsValidDetourTopologyAndPreservesPriorityOrder() {
        val front = socksEntity(1L, "Front-Relay")
        val b1 = socksEntity(10L, "Mid-Slow", status = 1, ping = 200)
        val b2 = socksEntity(11L, "Mid-Fast", status = 1, ping = 20)
        val balancer = ProxyEntity(
            id = 2L,
            groupId = 10L,
            type = ProxyEntity.TYPE_BALANCER,
            balancerBean = BalancerBean().apply {
                name = "Mid-Balancer"
                balancerType = BalancerBean.TYPE_LIST
                proxies = mutableListOf(10L, 11L)
                selectionMode = BalancerBean.SELECTION_PRIORITY
                testUrl = "https://cp.cloudflare.com/generate_204"
            },
        )
        val landing = socksEntity(3L, "Landing-Exit")
        val chain = ProxyEntity(
            id = 100L,
            groupId = 10L,
            type = ProxyEntity.TYPE_CHAIN,
            chainBean = ChainBean().apply {
                name = "My-Chain"
                proxies = mutableListOf(front.id, balancer.id, landing.id)
            },
        )
        val store = mapOf(
            front.id to front,
            balancer.id to balancer,
            b1.id to b1,
            b2.id to b2,
            landing.id to landing,
            chain.id to chain,
        )

        val assembled = assembleChainGraphForTest(
            root = chain,
            entitiesById = store,
        )

        val byTag = assembled.outbounds.associateBy { it["tag"] as String }
        val landingMap = byTag["Landing-Exit"]!!
        val balancerMap = byTag["Mid-Balancer"]!!
        val frontTag = byTag.keys.first { it.endsWith("-1") || it == "Front-Relay" || it == "g-1" }
        val frontMap = byTag[frontTag]!!

        assertEquals("Mid-Balancer", landingMap["detour"])
        assertEquals("autoselect", balancerMap["type"])
        assertEquals("priority", balancerMap["selection_mode"])

        @Suppress("UNCHECKED_CAST")
        val memberTags = balancerMap["outbounds"] as List<String>
        assertEquals(2, memberTags.size)
        // Priority mode preserves exact order [10 (200ms), 11 (20ms)]
        assertEquals(10L, assembled.outboundTagProfileMap[memberTags[0]])
        assertEquals(11L, assembled.outboundTagProfileMap[memberTags[1]])
        assertEquals(frontTag, byTag[memberTags[0]]!!["detour"])
        assertEquals(frontTag, byTag[memberTags[1]]!!["detour"])
        assertNull(frontMap["detour"])

        assertEquals("Landing-Exit", assembled.mainProxyTag)
        assertEquals("Mid-Balancer", assembled.activeStrategyTag)
        assertEquals(2L, assembled.activeStrategyId)
        assertEquals(listOf(10L, 11L), assembled.balancerMemberMap[2L])
    }

    @Test
    fun chainWithLeadingOrTrailingBalancerBuildsValidDetoursAndLatencyColdStart() {
        val front = socksEntity(1L, "Front-Relay")
        val slow = socksEntity(10L, "Slow-Member", status = 1, ping = 150)
        val fast = socksEntity(11L, "Fast-Member", status = 1, ping = 30)
        val balancer = ProxyEntity(
            id = 2L,
            groupId = 10L,
            type = ProxyEntity.TYPE_BALANCER,
            balancerBean = BalancerBean().apply {
                name = "Auto-Pool"
                balancerType = BalancerBean.TYPE_LIST
                proxies = mutableListOf(10L, 11L)
                selectionMode = BalancerBean.SELECTION_LATENCY
                testUrl = "https://cp.cloudflare.com/generate_204"
            },
        )
        val landing = socksEntity(3L, "Landing-Exit")

        // Case A: [Balancer, Landing]
        val balancerThenLanding = ProxyEntity(
            id = 101L,
            groupId = 10L,
            type = ProxyEntity.TYPE_CHAIN,
            chainBean = ChainBean().apply {
                proxies = mutableListOf(balancer.id, landing.id)
            },
        )
        val storeA = mapOf(balancer.id to balancer, slow.id to slow, fast.id to fast, landing.id to landing, balancerThenLanding.id to balancerThenLanding)
        val resA = assembleChainGraphForTest(balancerThenLanding, storeA)
        val mapA = resA.outbounds.associateBy { it["tag"] as String }
        assertEquals("Landing-Exit", resA.mainProxyTag)
        assertEquals("Auto-Pool", mapA["Landing-Exit"]!!["detour"])
        @Suppress("UNCHECKED_CAST")
        val aMembers = mapA["Auto-Pool"]!!["outbounds"] as List<String>
        assertEquals(11L, resA.outboundTagProfileMap[aMembers[0]]) // Fast first in latency mode
        assertEquals(10L, resA.outboundTagProfileMap[aMembers[1]])
        assertNull(mapA[aMembers[0]]!!["detour"])

        // Case B: [Front, Balancer]
        val frontThenBalancer = ProxyEntity(
            id = 102L,
            groupId = 10L,
            type = ProxyEntity.TYPE_CHAIN,
            chainBean = ChainBean().apply {
                proxies = mutableListOf(front.id, balancer.id)
            },
        )
        val storeB = mapOf(front.id to front, balancer.id to balancer, slow.id to slow, fast.id to fast, frontThenBalancer.id to frontThenBalancer)
        val resB = assembleChainGraphForTest(frontThenBalancer, storeB)
        val mapB = resB.outbounds.associateBy { it["tag"] as String }
        assertEquals("Auto-Pool", resB.mainProxyTag)
        @Suppress("UNCHECKED_CAST")
        val bMembers = mapB["Auto-Pool"]!!["outbounds"] as List<String>
        val frontTagB = bMembers.map { mapB[it]!!["detour"] as String }.distinct().single()
        assertTrue(mapB.containsKey(frontTagB))
        assertNull(mapB[frontTagB]!!["detour"])
    }

    @Test
    fun listBalancerNeverInheritsGroupFrontOrLandingProxies() {
        val groupFront = socksEntity(50L, "Group-Front")
        val groupLanding = socksEntity(60L, "Group-Landing")
        val member = socksEntity(10L, "SG-Member", status = 1, ping = 40)
        val group = ProxyGroup(id = 10L, frontProxy = 50L, landingProxy = 60L)

        val balancer = ProxyEntity(
            id = 2L,
            groupId = 99L,
            type = ProxyEntity.TYPE_BALANCER,
            balancerBean = BalancerBean().apply {
                name = "List-Balancer"
                balancerType = BalancerBean.TYPE_LIST
                proxies = mutableListOf(10L)
                testUrl = "https://cp.cloudflare.com/generate_204"
            },
        )
        val result = assembleChainGraphForTest(
            root = balancer,
            entitiesById = mapOf(
                balancer.id to balancer,
                member.id to member,
                groupFront.id to groupFront,
                groupLanding.id to groupLanding,
            ),
            groupsById = mapOf(10L to group),
        )

        assertFalse(result.outbounds.any { it["server"] == "198.51.100.50" })
        assertFalse(result.outbounds.any { it["server"] == "198.51.100.60" })
        val memberOut = result.outbounds.first { it["server"] == "198.51.100.10" }
        assertNull(memberOut["detour"])
    }

    @Test
    fun tieredPriorityBalancerEmitsOrderedTiersToAutoSelectOutbound() {
        val mTier1 = socksEntity(10L, "HK IEPL 01", status = 1, ping = 45).apply { groupId = 100L }
        val mFallback = socksEntity(11L, "US Backup 01", status = 1, ping = 120).apply { groupId = 100L }
        val mTier3 = socksEntity(12L, "SG Direct 01", status = 1, ping = 70).apply { groupId = 200L }

        val balancer = ProxyEntity(
            id = 2L,
            groupId = 999L,
            type = ProxyEntity.TYPE_BALANCER,
            balancerBean = BalancerBean().apply {
                initializeDefaultValues()
                name = "Tiered-Auto"
                balancerType = BalancerBean.TYPE_MIXED
                targetGroupIds = mutableListOf(100L)
                proxies = mutableListOf(12L)
                selectionMode = BalancerBean.SELECTION_PRIORITY
                tiers = mutableListOf(
                    BalancerBean.PriorityTier(100L, "IEPL"),
                    BalancerBean.PriorityTier(-1L, "NO_MATCH_JP"),
                    BalancerBean.PriorityTier(-1L, "SG"),
                )
            },
        )

        val result = assembleChainGraphForTest(
            root = balancer,
            entitiesById = mapOf(
                balancer.id to balancer,
                mTier1.id to mTier1,
                mFallback.id to mFallback,
                mTier3.id to mTier3,
            ),
            groupsById = mapOf(
                100L to ProxyGroup(id = 100L),
                200L to ProxyGroup(id = 200L),
            ),
            groupProfiles = mapOf(
                100L to listOf(mFallback, mTier1),
            ),
        )

        val autoOut = result.outbounds.first { it["type"] == "autoselect" }
        assertEquals("priority", autoOut["selection_mode"])

        @Suppress("UNCHECKED_CAST")
        val tiers = autoOut["tiers"] as List<List<String>>
        assertEquals(3, tiers.size)
        assertEquals(1, tiers[0].size)
        assertTrue("Tier 1 should hold HK IEPL 01 tag", tiers[0][0].contains("HK IEPL 01"))
        assertTrue("Middle unmatched tier must stay empty placeholder", tiers[1].isEmpty())
        assertEquals(1, tiers[2].size)
        assertTrue("Tier 3 should hold SG Direct 01 tag", tiers[2][0].contains("SG Direct 01"))

        @Suppress("UNCHECKED_CAST")
        val outbounds = autoOut["outbounds"] as List<String>
        assertEquals(3, outbounds.size)
        // Unmatched mFallback stays in outbounds as implicit fallback tier
        assertTrue(outbounds.any { it.contains("US Backup 01") })
    }
}
