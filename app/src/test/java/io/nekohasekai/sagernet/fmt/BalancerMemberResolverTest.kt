package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.BalancerMemberResolver
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BalancerMemberResolverTest {

    private fun socksEntity(id: Long, groupId: Long, name: String): ProxyEntity {
        val bean = SOCKSBean().apply {
            this.name = name
            serverAddress = "198.51.100.$id"
            serverPort = 1080
        }
        return ProxyEntity(
            id = id,
            groupId = groupId,
            type = ProxyEntity.TYPE_SOCKS,
            socksBean = bean,
        )
    }

    @Test
    fun mixedModeCombinesFilteredGroupAndExtraNodesWithoutDuplicates() {
        val groupNodes = listOf(
            socksEntity(1L, 10L, "JP Tokyo 01"),
            socksEntity(2L, 10L, "HK Premium 01"),
            socksEntity(3L, 10L, "Expired Traffic Notice"),
            socksEntity(4L, 10L, "Front Relay"),
            ProxyEntity(id = 5L, groupId = 10L, type = ProxyEntity.TYPE_BALANCER, balancerBean = BalancerBean()),
        )
        val extraNodes = listOf(
            socksEntity(1L, 10L, "JP Tokyo 01"), // duplicate already matched by group
            socksEntity(6L, 20L, "Custom Direct SG"), // does not match group include regex, still included as extra
            socksEntity(7L, 30L, "Disabled Group Node"),
        )
        val allById = (groupNodes + extraNodes).associateBy { it.id }

        val spec = BalancerMemberResolver.FilterSpec(
            balancerType = BalancerBean.TYPE_MIXED,
            targetGroupIds = listOf(10L),
            explicitProxyIds = listOf(1L, 6L, 7L),
            nameExclude = "Expired|Notice",
            nameInclude = "Tokyo|HK|Front|Expired",
            selfId = 99L,
            frontProxyId = 4L,
            landingProxyId = 0L,
        )

        val result = BalancerMemberResolver.resolve(
            spec = spec,
            groupProfilesProvider = { gid -> if (gid == 10L) groupNodes else emptyList() },
            entitiesByIdsProvider = { ids -> ids.mapNotNull { allById[it] } },
            isGroupDisabled = { gid -> gid == 30L },
        )

        assertFalse(result.hasRegexError)
        assertEquals(2, result.groupMatchedCount)
        assertEquals(1, result.extraAddedCount)
        assertEquals(3, result.totalCount)
        assertEquals(listOf(1L, 2L, 6L), result.members.map { it.id })
    }

    @Test
    fun invalidRegexFlagsErrorWithoutCrashing() {
        val node = socksEntity(10L, 1L, "US Node 01")
        val result = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_GROUP,
                targetGroupIds = listOf(1L),
                explicitProxyIds = emptyList(),
                nameExclude = "[unclosed",
                nameInclude = "(broken",
            ),
            groupProfilesProvider = { listOf(node) },
            entitiesByIdsProvider = { emptyList() },
            isGroupDisabled = { false },
        )

        assertTrue(result.excludeRegexInvalid)
        assertTrue(result.includeRegexInvalid)
        assertTrue(result.hasRegexError)
        assertEquals(1, result.totalCount)
    }

    @Test
    fun balancerBeanDefaultsToleranceTo30AndFormatsMixedDisplay() {
        val bean = BalancerBean().apply {
            tolerance = -1
            initializeDefaultValues()
            balancerType = BalancerBean.TYPE_MIXED
            targetGroupIds = mutableListOf(10L)
            proxies = mutableListOf(1L, 2L, 3L)
            selectionMode = BalancerBean.SELECTION_LATENCY
        }

        assertEquals(30, bean.tolerance)
        assertEquals(30L, bean.calculateToleranceMs())
        assertEquals("混合 1+3", bean.displayNodeOnly())
        assertEquals("混合 1+3 · 最快最稳", bean.displayAddress())
        assertEquals("混合 1+3 · Tokyo 01", bean.displayWithNodeName("Tokyo 01"))
    }

    @Test
    fun customBalancerChainsDoNotShareGlobalOutbounds() {
        val globalOutbounds = linkedMapOf<Long, String>()
        // Two balancers share member id 42 with different front proxies, so both run with needGlobal = false.
        val first = resolveChainHopTag(42L, "HK-01", needGlobal = false, globalOutbounds = globalOutbounds)
        val second = resolveChainHopTag(42L, "HK-01-1", needGlobal = false, globalOutbounds = globalOutbounds)

        assertFalse(first.reused)
        assertFalse(second.reused)
        assertEquals("HK-01", first.tag)
        assertEquals("HK-01-1", second.tag)
        assertTrue(globalOutbounds.isEmpty())

        val configBuilderSource = listOf(
            File("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt"),
            File("app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt"),
        ).first { it.isFile }.readText()
        assertTrue(configBuilderSource.contains("BalancerMemberChainKey"))
        assertTrue(configBuilderSource.contains("customChainTagMap"))
        assertTrue(configBuilderSource.contains("isCustomChainContext"))
        assertTrue(configBuilderSource.contains("BalancerMemberResolver.sortForColdStart"))
    }

    @Test
    fun coldStartSortPlacesHealthyLowestLatencyFirstAndFailedLast() {
        val untested = socksEntity(1L, 10L, "Untested").apply {
            status = 0
            ping = 0
        }
        val failedWithStalePing = socksEntity(2L, 10L, "Failed").apply {
            status = 3
            ping = 12
        }
        val slowHealthy = socksEntity(3L, 10L, "SlowHealthy").apply {
            status = 1
            ping = 180
        }
        val fastHealthy = socksEntity(4L, 10L, "FastHealthy").apply {
            status = 1
            ping = 45
        }
        val legacyCached = socksEntity(5L, 10L, "CachedPing").apply {
            status = 0
            ping = 90
        }

        val sorted = BalancerMemberResolver.sortForColdStart(
            listOf(untested, failedWithStalePing, slowHealthy, fastHealthy, legacyCached)
        )

        assertEquals(
            listOf(fastHealthy.id, slowHealthy.id, legacyCached.id, untested.id, failedWithStalePing.id),
            sorted.map { it.id },
        )
    }

    @Test
    fun countryFilterMatchesIsoCodesFlagsAndKeywordsWithoutFalsePositives() {
        val nodes = listOf(
            socksEntity(1L, 10L, "🇩🇪 Frankfurt 01"),
            socksEntity(2L, 10L, "Amsterdam-NL-02"),
            socksEntity(3L, 10L, "🇯🇵 Tokyo Premium"),
            socksEntity(4L, 10L, "Australia 01"), // contains "us" as substring, must not match US
            socksEntity(5L, 10L, "London UK 01"),
            socksEntity(6L, 10L, "🇵🇱 Warsaw 01"),
            socksEntity(7L, 10L, "🇺🇸 Los Angeles 01"),
        )
        val result = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_GROUP,
                targetGroupIds = listOf(10L),
                explicitProxyIds = emptyList(),
                countryFilter = "DE, nl; 日本 GB PL",
            ),
            groupProfilesProvider = { nodes },
            entitiesByIdsProvider = { emptyList() },
            isGroupDisabled = { false },
        )
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L), result.members.map { it.id })
        assertEquals(5, result.matchedCandidateCount)

        val usOnly = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_GROUP,
                targetGroupIds = listOf(10L),
                explicitProxyIds = emptyList(),
                countryFilter = "US",
            ),
            groupProfilesProvider = { nodes },
            entitiesByIdsProvider = { emptyList() },
            isGroupDisabled = { false },
        )
        assertEquals(listOf(7L), usOnly.members.map { it.id })
    }

    @Test
    fun resolveIncludesAllCandidatesAndComputesPreviewCountsWithoutSkippingFailed() {
        val healthyA = socksEntity(1L, 10L, "SG 01").apply { status = 1; ping = 40 }
        val healthyB = socksEntity(2L, 10L, "SG 02").apply { status = 1; ping = 55 }
        val failedA = socksEntity(3L, 10L, "SG 03").apply { status = 3; ping = 0 }
        val failedB = socksEntity(4L, 10L, "SG 04").apply { status = 2; ping = 0 }
        val untested = socksEntity(5L, 10L, "SG 05").apply { status = 0; ping = 0 }
        val allNodes = listOf(healthyA, healthyB, failedA, failedB, untested)

        val result = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_GROUP,
                targetGroupIds = listOf(10L),
                explicitProxyIds = emptyList(),
            ),
            groupProfilesProvider = { allNodes },
            entitiesByIdsProvider = { emptyList() },
            isGroupDisabled = { false },
        )

        assertEquals(5, result.matchedCandidateCount)
        assertEquals(3, result.availableCount)
        assertEquals(5, result.totalCount)
        assertEquals(listOf(1L, 2L, 5L, 3L, 4L), result.members.map { it.id })
    }

    @Test
    fun latencySortsColdStartWhilePriorityPreservesManualOrderWithoutHiddenTopNCap() {
        val manyNodes = (1L..320L).map { id ->
            socksEntity(id, 10L, "Node $id").apply {
                status = if (id == 4L) 3 else 1
                ping = if (id == 4L) 0 else (330 - id).toInt()
            }
        }
        val explicitExtra = socksEntity(321L, 20L, "Node 321").apply { status = 1; ping = 5 }
        val byId = (manyNodes + explicitExtra).associateBy { it.id }

        val latencyResult = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_MIXED,
                targetGroupIds = listOf(10L),
                explicitProxyIds = listOf(321L),
                selectionMode = BalancerBean.SELECTION_LATENCY,
            ),
            groupProfilesProvider = { manyNodes },
            entitiesByIdsProvider = { ids -> ids.mapNotNull { byId[it] } },
            isGroupDisabled = { false },
        )
        assertEquals(321, latencyResult.matchedCandidateCount)
        assertEquals(321, latencyResult.members.size)
        assertEquals(321L, latencyResult.members.first().id)
        assertEquals(4L, latencyResult.members.last().id)

        val priorityResult = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_MIXED,
                targetGroupIds = listOf(10L),
                explicitProxyIds = listOf(321L),
                selectionMode = BalancerBean.SELECTION_PRIORITY,
            ),
            groupProfilesProvider = { manyNodes },
            entitiesByIdsProvider = { ids -> ids.mapNotNull { byId[it] } },
            isGroupDisabled = { false },
        )
        assertEquals((1L..321L).toList(), priorityResult.members.map { it.id })
    }

    @Test
    fun priorityTiersAssignOrderTrackCountsAndValidateRegexWithoutCapping() {
        val g1Iepl = socksEntity(1L, 10L, "HK IEPL 01")
        val g1Normal = socksEntity(2L, 10L, "HK Normal 01")
        val g2Fast = socksEntity(3L, 20L, "JP BGP 01")
        val explicitSg = socksEntity(4L, 30L, "SG Fallback 01")
        val byId = listOf(g1Iepl, g1Normal, g2Fast, explicitSg).associateBy { it.id }

        val tiers = listOf(
            BalancerBean.PriorityTier(10L, "IEPL"),
            BalancerBean.PriorityTier(20L, ""),
            BalancerBean.PriorityTier(10L, "HK"),
        )
        val res = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_MIXED,
                targetGroupIds = listOf(10L, 20L),
                explicitProxyIds = listOf(4L),
                selectionMode = BalancerBean.SELECTION_PRIORITY,
                tiers = tiers,
            ),
            groupProfilesProvider = { gid ->
                when (gid) {
                    10L -> listOf(g1Normal, g1Iepl)
                    20L -> listOf(g2Fast)
                    else -> emptyList()
                }
            },
            entitiesByIdsProvider = { ids -> ids.mapNotNull { byId[it] } },
            isGroupDisabled = { false },
        )

        // Expected bucket order: Tier0=[g1Iepl], Tier1=[g2Fast], Tier2=[g1Normal], Fallback=[explicitSg]
        assertEquals(listOf(1L, 3L, 2L, 4L), res.members.map { it.id })
        assertEquals(listOf(1, 1, 1, 1), res.tierCounts)
        assertFalse(res.hasRegexError)

        // Invalid regex in tier must flag tierRegexInvalid and match nothing for that tier
        val invalidRes = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_GROUP,
                targetGroupIds = listOf(10L),
                explicitProxyIds = emptyList(),
                selectionMode = BalancerBean.SELECTION_PRIORITY,
                tiers = listOf(BalancerBean.PriorityTier(-1L, "[unclosed")),
            ),
            groupProfilesProvider = { listOf(g1Normal, g1Iepl) },
            isGroupDisabled = { false },
        )
        assertTrue(invalidRes.tierRegexInvalid)
        assertTrue(invalidRes.hasRegexError)
        assertEquals(listOf(0, 2), invalidRes.tierCounts)

        // All tiered nodes are preserved across tiers without artificial capping
        val tier1Nodes = (1L..4L).map { socksEntity(it, 10L, "T1-$it") }
        val tier2Nodes = (10L..13L).map { socksEntity(it, 20L, "T2-$it") }
        val uncapped = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_GROUP,
                targetGroupIds = listOf(10L, 20L),
                explicitProxyIds = emptyList(),
                selectionMode = BalancerBean.SELECTION_PRIORITY,
                tiers = listOf(
                    BalancerBean.PriorityTier(10L, ""),
                    BalancerBean.PriorityTier(20L, ""),
                ),
            ),
            groupProfilesProvider = { gid -> if (gid == 10L) tier1Nodes else tier2Nodes },
            isGroupDisabled = { false },
        )
        assertEquals(8, uncapped.members.size)
        assertEquals(listOf(4, 4, 0), uncapped.tierCounts)
    }
}
