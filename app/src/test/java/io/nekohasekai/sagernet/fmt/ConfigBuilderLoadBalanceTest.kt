package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import moe.matsuri.nb4a.SingBoxOptions
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
}
