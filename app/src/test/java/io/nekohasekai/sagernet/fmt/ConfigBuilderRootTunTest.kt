package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderRootTunTest {

    @Test
    fun resolveTunInboundTag_selectsDedicatedRootTunInbound() {
        assertEquals("tun-in", resolveTunInboundTag(Key.MODE_VPN))
        assertEquals(TAG_ROOT_TUN_IN, resolveTunInboundTag(Key.MODE_ROOT_TUN))
        assertEquals("tun-in", resolveTunInboundTag(Key.MODE_PROXY))
    }

    @Test
    fun shouldUseFakeDns_suppressesFakeDnsOnlyInRootTunWhitelist() {
        assertTrue(
            shouldUseFakeDns(
                enableFakeDns = true,
                forTest = false,
                serviceMode = Key.MODE_VPN,
                proxyApps = true,
                bypass = false,
            )
        )
        assertTrue(
            shouldUseFakeDns(
                enableFakeDns = true,
                forTest = false,
                serviceMode = Key.MODE_ROOT_TUN,
                proxyApps = false,
                bypass = false,
            )
        )
        assertTrue(
            shouldUseFakeDns(
                enableFakeDns = true,
                forTest = false,
                serviceMode = Key.MODE_ROOT_TUN,
                proxyApps = true,
                bypass = true,
            )
        )
        assertFalse(
            shouldUseFakeDns(
                enableFakeDns = true,
                forTest = false,
                serviceMode = Key.MODE_ROOT_TUN,
                proxyApps = true,
                bypass = false,
            )
        )
        assertFalse(
            shouldUseFakeDns(
                enableFakeDns = true,
                forTest = true,
                serviceMode = Key.MODE_ROOT_TUN,
                proxyApps = false,
                bypass = true,
            )
        )
        assertFalse(
            shouldUseFakeDns(
                enableFakeDns = false,
                forTest = false,
                serviceMode = Key.MODE_ROOT_TUN,
                proxyApps = false,
                bypass = true,
            )
        )
    }

    @Test
    fun buildRootTunSocksInbound_configuresLoopbackAuthenticatedSocks5() {
        val inbound = buildRootTunSocksInbound(
            port = 45678,
            username = "rootuser",
            password = "rootpassword",
        )
        assertEquals("socks", inbound.type)
        assertEquals(TAG_ROOT_TUN_IN, inbound.tag)
        assertEquals(LOCALHOST, inbound.listen)
        assertEquals(45678, inbound.listen_port)
        assertEquals(300L, inbound.udp_timeout)
        assertEquals(1, inbound.users.size)
        assertEquals("rootuser", inbound.users[0].username)
        assertEquals("rootpassword", inbound.users[0].password)
    }
}
