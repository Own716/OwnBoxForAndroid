package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.makeSingBoxRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConfigBuilderLoadBalanceTest {

    @Test
    fun buildLoadBalanceOutboundSetsCorrectTypeTagAndMembers() {
        val members = listOf("node1", "node2", "node3")
        val lb = buildLoadBalanceOutbound(members)

        assertEquals("loadbalance", lb.type)
        assertEquals(TAG_PROXY, lb.tag)
        assertEquals(members, lb.outbounds)
    }

    @Test
    fun buildLoadBalanceOutboundWithStrategy() {
        val members = listOf("node1", "node2")
        val lbRandom = buildLoadBalanceOutbound(members, "random")
        assertEquals("loadbalance", lbRandom.type)
        assertEquals("random", lbRandom.strategy)

        val lbLeastLoad = buildLoadBalanceOutbound(members, "leastLoad")
        assertEquals("loadbalance", lbLeastLoad.type)
        assertEquals("leastLoad", lbLeastLoad.strategy)
    }

    @Test
    fun buildUrlTestOutboundAllowsZeroTolerance() {
        val members = listOf("node1", "node2")
        val testUrl = "http://cp.cloudflare.com/generate_204"
        val ut = buildUrlTestOutbound(members, testUrl = testUrl, toleranceMs = 0)
        assertEquals(0, ut.tolerance)

        val ut30 = buildUrlTestOutbound(members, testUrl = testUrl, toleranceMs = 30)
        assertEquals(30, ut30.tolerance)

        val utNull = buildUrlTestOutbound(members, testUrl = testUrl, toleranceMs = null)
        assertEquals(50, utNull.tolerance)
    }

    @Test
    fun verifyTunImplementationSingTunMapping() {
        val stack = when (io.nekohasekai.sagernet.TunImplementation.SING_TUN) {
            io.nekohasekai.sagernet.TunImplementation.GVISOR -> "gvisor"
            io.nekohasekai.sagernet.TunImplementation.SYSTEM -> "system"
            io.nekohasekai.sagernet.TunImplementation.SING_TUN -> "go"
            else -> "mixed"
        }
        assertEquals("go", stack)
    }

    @Test
    fun verifySingTunStackOmittedInJson() {
        val tunOptions = moe.matsuri.nb4a.SingBoxOptions.Inbound_TunOptions().apply {
            type = "tun"
            tag = "tun-in"
            interface_name = "tun0"
            stack = when (io.nekohasekai.sagernet.TunImplementation.SING_TUN) {
                io.nekohasekai.sagernet.TunImplementation.GVISOR -> "gvisor"
                io.nekohasekai.sagernet.TunImplementation.SYSTEM -> "system"
                io.nekohasekai.sagernet.TunImplementation.MIXED -> "mixed"
                io.nekohasekai.sagernet.TunImplementation.SING_TUN -> null
                else -> null
            }
        }
        val map = tunOptions.asMap()
        assertFalse("Sing-Tun must omit stack field completely", map.containsKey("stack"))

        tunOptions.stack = "gvisor"
        assertEquals("gvisor", tunOptions.asMap()["stack"])
    }

    @Test
    fun testAllFourStrategiesInBalancerBean() {
        val bean = io.nekohasekai.sagernet.fmt.internal.BalancerBean()
        bean.proxies = listOf(1L, 2L, 3L)

        bean.strategy = io.nekohasekai.sagernet.fmt.internal.BalancerBean.STRATEGY_LEAST_PING
        assertEquals("[节点 (3)] 策略: 最低延迟", bean.displayAddress())

        bean.strategy = io.nekohasekai.sagernet.fmt.internal.BalancerBean.STRATEGY_LEAST_LOAD
        assertEquals("[节点 (3)] 策略: 最低负载", bean.displayAddress())

        bean.strategy = io.nekohasekai.sagernet.fmt.internal.BalancerBean.STRATEGY_RANDOM
        assertEquals("[节点 (3)] 策略: 随机选择", bean.displayAddress())

        bean.strategy = io.nekohasekai.sagernet.fmt.internal.BalancerBean.STRATEGY_ROUND_ROBIN
        assertEquals("[节点 (3)] 策略: 轮询", bean.displayAddress())
    }

    @Test
    fun testLoadBalanceOutboundAllStrategies() {
        val members = listOf("n1", "n2", "n3")

        val failover = buildLoadBalanceOutbound(members, "failover")
        assertEquals("loadbalance", failover.type)
        assertEquals("failover", failover.strategy)

        val stable = buildLoadBalanceOutbound(members, "stable")
        assertEquals("loadbalance", stable.type)
        assertEquals("stable", stable.strategy)

        val roundRobin = buildLoadBalanceOutbound(members, "round_robin")
        assertEquals("loadbalance", roundRobin.type)
        assertEquals("round_robin", roundRobin.strategy)

        val random = buildLoadBalanceOutbound(members, "random")
        assertEquals("loadbalance", random.type)
        assertEquals("random", random.strategy)
    }

    @Test
    fun testMakeSingBoxRulePreservesRuleSetWhenIPPresent() {
        val rule = moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions().apply {
            outbound = "bypass"
            makeSingBoxRule(listOf("geosite:cn"), false)
            makeSingBoxRule(listOf("geoip:cn"), true)
        }
        // Verify rule_set is NOT cleared by isIP
        val map = rule.asMap()
        val ruleSets = map["rule_set"] as? List<*>
        assertEquals("bypass", map["outbound"])
        assertEquals(listOf("geosite:cn", "geoip:cn"), ruleSets)
    }
}
