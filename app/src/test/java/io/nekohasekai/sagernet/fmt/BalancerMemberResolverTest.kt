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
    fun skipFailedWithinTrustRespectsTrustWindowAndComputesPreviewCounts() {
        val nowMs = 1_700_000_000_000L
        val trustMinutes = 60
        val recentHealthy = socksEntity(1L, 10L, "SG 01").apply { status = 1; ping = 40 }
        val expiredHealthy = socksEntity(2L, 10L, "SG 02").apply { status = 1; ping = 55 }
        val recentFailed = socksEntity(3L, 10L, "SG 03").apply { status = 3; ping = 0 }
        val expiredFailed = socksEntity(4L, 10L, "SG 04").apply { status = 2; ping = 0 }
        val untested = socksEntity(5L, 10L, "SG 05").apply { status = 0; ping = 0 }

        val timestamps = mapOf(
            1L to nowMs - 10 * 60_000L, // recent (10m)
            2L to nowMs - 120 * 60_000L, // expired (120m > 60m)
            3L to nowMs - 15 * 60_000L, // recent failure
            4L to nowMs - 180 * 60_000L, // expired failure
        )
        val allNodes = listOf(recentHealthy, expiredHealthy, recentFailed, expiredFailed, untested)

        val skippedResult = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_GROUP,
                targetGroupIds = listOf(10L),
                explicitProxyIds = emptyList(),
                resultTrustMinutes = trustMinutes,
                skipFailedWithinTrust = true,
            ),
            groupProfilesProvider = { allNodes },
            entitiesByIdsProvider = { emptyList() },
            isGroupDisabled = { false },
            nowMsProvider = { nowMs },
            testTimestampProvider = { id -> timestamps[id] ?: 0L },
        )

        assertEquals(5, skippedResult.matchedCandidateCount)
        assertEquals(3, skippedResult.availableCount)
        assertEquals(1, skippedResult.skippedFailedCount)
        assertEquals(1, skippedResult.reusableRecentCount)
        assertEquals(listOf(1L, 2L, 5L, 4L), skippedResult.members.map { it.id })

        val keptResult = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_GROUP,
                targetGroupIds = listOf(10L),
                explicitProxyIds = emptyList(),
                resultTrustMinutes = trustMinutes,
                skipFailedWithinTrust = false,
            ),
            groupProfilesProvider = { allNodes },
            entitiesByIdsProvider = { emptyList() },
            isGroupDisabled = { false },
            nowMsProvider = { nowMs },
            testTimestampProvider = { id -> timestamps[id] ?: 0L },
        )
        assertEquals(0, keptResult.skippedFailedCount)
        assertEquals(5, keptResult.totalCount)
    }

    @Test
    fun runTopNAreAppliedInLatencyWhilePriorityPreservesManualOrderAndZeroMeansUnlimited() {
        val slowFirst = socksEntity(1L, 10L, "Node 1").apply { status = 1; ping = 220 }
        val fastSecond = socksEntity(2L, 10L, "Node 2").apply { status = 1; ping = 25 }
        val midThird = socksEntity(3L, 10L, "Node 3").apply { status = 1; ping = 80 }
        val failedFourth = socksEntity(4L, 10L, "Node 4").apply { status = 3; ping = 0 }
        val explicitFifth = socksEntity(5L, 10L, "Node 5").apply { status = 1; ping = 15 }
        val byId = listOf(slowFirst, fastSecond, midThird, failedFourth, explicitFifth).associateBy { it.id }

        val latencyResult = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_MIXED,
                targetGroupIds = listOf(10L),
                explicitProxyIds = listOf(5L),
                selectionMode = BalancerBean.SELECTION_LATENCY,
                runTopN = 3,
            ),
            groupProfilesProvider = { listOf(slowFirst, fastSecond, midThird, failedFourth) },
            entitiesByIdsProvider = { ids -> ids.mapNotNull { byId[it] } },
            isGroupDisabled = { false },
        )
        // Cold-start sorts by healthy latency: 5(15ms), 2(25ms), 3(80ms), 1(220ms), 4(failed); capped to 3
        assertEquals(5, latencyResult.matchedCandidateCount)
        assertEquals(listOf(5L, 2L, 3L), latencyResult.members.map { it.id })

        val priorityResult = BalancerMemberResolver.resolve(
            spec = BalancerMemberResolver.FilterSpec(
                balancerType = BalancerBean.TYPE_MIXED,
                targetGroupIds = listOf(10L),
                explicitProxyIds = listOf(5L),
                selectionMode = BalancerBean.SELECTION_PRIORITY,
                runTopN = 3,
            ),
            groupProfilesProvider = { listOf(slowFirst, fastSecond, midThird, failedFourth) },
            entitiesByIdsProvider = { ids -> ids.mapNotNull { byId[it] } },
            isGroupDisabled = { false },
        )
        // Priority preserves exact order [1, 2, 3, 4, 5], capped to first 3
        assertEquals(listOf(1L, 2L, 3L), priorityResult.members.map { it.id })

        val unlimitedBean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_MIXED
            targetGroupIds = mutableListOf(10L)
            proxies = mutableListOf(5L)
            selectionMode = BalancerBean.SELECTION_PRIORITY
            runTopN = 0
            rankTopN = 0
            initializeDefaultValues()
        }
        assertEquals(0, unlimitedBean.runTopN)
        assertEquals(0, unlimitedBean.effectiveRunTopN)
        val unlimitedResult = BalancerMemberResolver.resolve(
            balancer = unlimitedBean,
            groupProfilesProvider = { listOf(slowFirst, fastSecond, midThird, failedFourth) },
            entitiesByIdsProvider = { ids -> ids.mapNotNull { byId[it] } },
            isGroupDisabled = { false },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), unlimitedResult.members.map { it.id })
    }
}
