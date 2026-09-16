package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import moe.matsuri.nb4a.SingBoxOptions
import org.junit.Assert.assertEquals
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
    fun verifyTunImplementationSingTunMapping() {
        val stack = when (io.nekohasekai.sagernet.TunImplementation.SING_TUN) {
            io.nekohasekai.sagernet.TunImplementation.GVISOR -> "gvisor"
            io.nekohasekai.sagernet.TunImplementation.SYSTEM -> "system"
            io.nekohasekai.sagernet.TunImplementation.SING_TUN -> "go"
            else -> "mixed"
        }
        assertEquals("go", stack)
    }
}
