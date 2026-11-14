package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorCountry
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorHealthStore
import io.nekohasekai.sagernet.fmt.AutoSelectorPlanner
import io.nekohasekai.sagernet.fmt.AutoSelectorSkip
import io.nekohasekai.sagernet.fmt.SelectorGroup
import io.nekohasekai.sagernet.fmt.SelectorMember
import io.nekohasekai.sagernet.fmt.SelectorStore
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoSelectorPlanTest {

    private class FakeStore(
        groups: List<SelectorGroup>,
        members: List<SelectorMember>,
    ) : SelectorStore {
        private val gMap = groups.associateBy { it.id }
        private val mMap = members.associateBy { it.id }
        override fun group(id: Long): SelectorGroup? = gMap[id]
        override fun member(id: Long): SelectorMember? = mMap[id]
    }

    @Before
    fun setUp() {
        AutoSelectorHealthStore.memoryOnlyForTest = true
        AutoSelectorHealthStore.clearForTest()
    }

    @After
    fun tearDown() {
        AutoSelectorHealthStore.clearForTest()
        AutoSelectorHealthStore.memoryOnlyForTest = false
    }

    @Test
    fun countryInferenceAndFilterIncludeExclude() {
        assertEquals("JP", AutoSelectorCountry.inferCountryCode("🇯🇵 Tokyo 01"))
        assertEquals("HK", AutoSelectorCountry.inferCountryCode("香港 IEPL 02"))
        assertEquals("SG", AutoSelectorCountry.inferCountryCode("狮城 专线 | x1"))
        assertEquals("DE", AutoSelectorCountry.inferCountryCode("Aiyun 9929 DE-FRA"))
        assertEquals("", AutoSelectorCountry.inferCountryCode("Node at 01")) // lowercase ambiguous token ignored
        assertEquals("", AutoSelectorCountry.inferCountryCode("Node OK 01")) // status token ignored even in uppercase
        assertEquals("AT", AutoSelectorCountry.inferCountryCode("Node AT 01")) // uppercase ISO code recognized
        assertEquals("", AutoSelectorCountry.inferCountryCode("Server 01"))

        val includeFilter = AutoSelectorCountry.parseFilter("jp, sg")
        assertTrue(includeFilter.isActive)
        assertTrue(includeFilter.matches("JP"))
        assertTrue(includeFilter.matches("SG"))
        assertFalse(includeFilter.matches("US"))
        assertFalse(includeFilter.matches(""))

        val excludeFilter = AutoSelectorCountry.parseFilter("!RU, !cn")
        assertTrue(excludeFilter.isActive)
        assertTrue(excludeFilter.matches("JP"))
        assertFalse(excludeFilter.matches("RU"))
        assertFalse(excludeFilter.matches("CN"))
        assertFalse(excludeFilter.matches(""))
    }

    @Test
    fun plannerFiltersSkipsAndRespectsTrustWindowAndRescuesAllFailed() {
        val now = 1_700_000_000L
        val group1 = SelectorGroup(10L, "SubA", listOf(1L, 2L, 3L, 4L, 5L, 999L))
        val members = listOf(
            SelectorMember(1L, 10L, "🇯🇵 Tokyo Fast", latency = 80, latencyAt = now - 300L),
            SelectorMember(2L, 10L, "🇯🇵 Tokyo Unmeasured"),
            SelectorMember(3L, 10L, "🇯🇵 Tokyo DeadFresh", latency = 0, latencyAt = now - 600L, error = "timeout"),
            SelectorMember(4L, 10L, "🇸🇬 Singapore", latency = 45, latencyAt = now - 100L),
            SelectorMember(5L, 10L, "🇯🇵 MetaBalancer", meta = true),
            SelectorMember(6L, 20L, "🇯🇵 Explicit Slow", latency = 220, latencyAt = now - 120L),
        )
        val store = FakeStore(listOf(group1), members)

        val bean = BalancerBean().apply {
            strategy = BalancerBean.STRATEGY_AUTO_SELECTOR
            targetGroupIds = arrayListOf(10L, 404L)
            proxies = arrayListOf(6L, 1L) // 1L should be deduplicated
            countryFilter = "JP"
            resultValidityMins = 1440
            excludeUnavailable = true
        }

        val plan = AutoSelectorPlanner.plan(bean, store, nowSec = now)
        assertEquals(1, plan.missingGroups)
        assertEquals(listOf(1L, 6L, 2L), plan.pool.map { it.id })
        assertEquals(2, plan.reusedCount)
        assertEquals(1, plan.unmeasuredInPool)
        assertEquals(1, plan.skipped[AutoSelectorSkip.MISSING])
        assertEquals(1, plan.skipped[AutoSelectorSkip.META])
        assertEquals(1, plan.skipped[AutoSelectorSkip.COUNTRY])
        assertEquals(1, plan.skipped[AutoSelectorSkip.UNAVAILABLE])
        assertFalse(plan.keptAllUnavailable)

        // When every candidate has a recent failed test, planner rescues all of them.
        val allFailedGroup = SelectorGroup(11L, "AllDead", listOf(31L, 32L))
        val failedMembers = listOf(
            SelectorMember(31L, 11L, "🇯🇵 J1", latency = 0, latencyAt = now - 100L, error = "EOF"),
            SelectorMember(32L, 11L, "🇯🇵 J2", latency = 0, latencyAt = now - 100L, error = "timeout"),
        )
        val rescuedPlan = AutoSelectorPlanner.plan(
            BalancerBean().apply {
                strategy = BalancerBean.STRATEGY_AUTO_SELECTOR
                targetGroupIds = arrayListOf(11L)
                excludeUnavailable = true
                resultValidityMins = 1440
            },
            FakeStore(listOf(allFailedGroup), failedMembers),
            nowSec = now,
        )
        assertTrue(rescuedPlan.keptAllUnavailable)
        assertEquals(listOf(31L, 32L), rescuedPlan.build.map { it.id })
    }

    @Test
    fun plannerPromotesPinnedIntoTruncatedPoolAndBuildAndComputesQuorum() {
        val now = 1_700_000_000L
        val members = (1..6).map { i ->
            SelectorMember(
                id = i.toLong(),
                groupId = 1L,
                name = "🇯🇵 Node $i",
                latency = i * 50,
                latencyAt = now - 60L,
            )
        }
        val group = SelectorGroup(1L, "Sub", members.map { it.id })
        val bean = BalancerBean().apply {
            strategy = BalancerBean.STRATEGY_AUTO_SELECTOR
            targetGroupIds = arrayListOf(1L)
            poolCap = 4
            buildLimit = 2
            pinnedId = 6L // ranked last by latency, must still be promoted into pool & build
        }
        val plan = AutoSelectorPlanner.plan(bean, FakeStore(listOf(group), members), nowSec = now)
        assertTrue(plan.truncated)
        assertEquals(4, plan.pool.size)
        assertEquals(2, plan.build.size)
        assertTrue(plan.pool.any { it.id == 6L })
        assertTrue(plan.build.any { it.id == 6L })

        assertEquals(3, AutoSelectorPlanner.startQuorum(totalUnmeasured = 10, expected = 3, buildSize = 8))
        assertEquals(2, AutoSelectorPlanner.startQuorum(totalUnmeasured = 2, expected = 5, buildSize = 8))
        assertEquals(0, AutoSelectorPlanner.startQuorum(totalUnmeasured = 0, expected = 3, buildSize = 8))
    }

    @Test
    fun healthStoreInMemoryRoundTrip() {
        AutoSelectorHealthStore.record(42L, latencyMs = 123, atSec = 1_700_000_000L, error = null)
        val entry = AutoSelectorHealthStore.get(42L)
        assertEquals(123, entry?.latency)
        assertEquals(1_700_000_000L, entry?.at)
        assertEquals(null, entry?.error)
    }
}
