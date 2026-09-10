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
}
