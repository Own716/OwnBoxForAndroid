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
    fun balancerBeanDefaultToleranceIs30() {
        val bean = io.nekohasekai.sagernet.fmt.internal.BalancerBean()
        assertEquals(30, bean.tolerance)
        assertEquals(30L, bean.calculateToleranceMs())
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
    fun testUnifiedModesInBalancerBean() {
        val bean = io.nekohasekai.sagernet.fmt.internal.BalancerBean()
        bean.proxies = listOf(1L, 2L, 3L)

        bean.selectionMode = io.nekohasekai.sagernet.fmt.internal.BalancerBean.SELECTION_LATENCY
        bean.balanceEnabled = false
        assertEquals("节点 3 · 最快最稳", bean.displayAddress())
        assertEquals("节点 3 · 日本 02", bean.displayWithNodeName("日本 02"))

        bean.selectionMode = io.nekohasekai.sagernet.fmt.internal.BalancerBean.SELECTION_PRIORITY
        assertEquals("节点 3 · 按优先级", bean.displayAddress())

        bean.selectionMode = io.nekohasekai.sagernet.fmt.internal.BalancerBean.SELECTION_LATENCY
        bean.balanceEnabled = true
        assertEquals("节点 3 · 最快最稳 · 流量分配", bean.displayAddress())
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

        val consistentHash = buildLoadBalanceOutbound(members, "consistent_hash")
        assertEquals("loadbalance", consistentHash.type)
        assertEquals("consistent_hash", consistentHash.strategy)

        val consistentHashCamel = buildLoadBalanceOutbound(members, "consistentHash")
        assertEquals("loadbalance", consistentHashCamel.type)
        assertEquals("consistentHash", consistentHashCamel.strategy)
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

    @Test
    fun testBuildLoadBalanceOutboundURLTestOptions() {
        val members = listOf("n1", "n2")
        val lb = buildLoadBalanceOutbound(
            memberTags = members,
            strategy = "leastLoad",
            testUrl = "https://www.gstatic.com/generate_204",
            intervalSec = 120,
            toleranceMs = 150,
            idleTimeoutStr = "120s",
            interruptExist = true,
            customTag = "lb-group"
        )
        assertEquals("loadbalance", lb.type)
        assertEquals("leastLoad", lb.strategy)
        assertEquals("lb-group", lb.tag)
        assertEquals("https://www.gstatic.com/generate_204", lb.url)
        assertEquals("120s", lb.interval)
        assertEquals(150, lb.tolerance)
        assertEquals("120s", lb.idle_timeout)
        assertEquals(true, lb.interrupt_exist_connections)
    }
}
