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
    fun balancerBeanDefaultsToleranceTo50AndFormatsMixedDisplay() {
        val bean = BalancerBean().apply {
            tolerance = -1
            initializeDefaultValues()
            balancerType = BalancerBean.TYPE_MIXED
            targetGroupIds = mutableListOf(10L)
            proxies = mutableListOf(1L, 2L, 3L)
            strategy = BalancerBean.STRATEGY_LEAST_PING
        }

        assertEquals(50, bean.tolerance)
        assertEquals(50L, bean.calculateToleranceMs())
        assertEquals("混合 1+3", bean.displayNodeOnly())
        assertEquals("混合 1+3 · 最低延迟", bean.displayAddress())
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
    }
}
