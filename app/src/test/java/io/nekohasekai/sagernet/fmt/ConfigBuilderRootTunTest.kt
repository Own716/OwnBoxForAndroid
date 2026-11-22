package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderRootTunTest {

    @Test
    fun resolveTunInboundTags_mapsAllSixServiceModes() {
        assertEquals(listOf("tun-in"), resolveTunInboundTags(Key.MODE_VPN))
        assertEquals(listOf("tun-in"), resolveTunInboundTags(Key.MODE_PROXY))
        assertEquals(listOf(TAG_ROOT_TUN_IN), resolveTunInboundTags(Key.MODE_ROOT_TUN))
        assertEquals(listOf(TAG_ROOT_REDIRECT_IN, TAG_ROOT_DNS_IN), resolveTunInboundTags(Key.MODE_ROOT_REDIRECT))
        assertEquals(listOf(TAG_ROOT_REDIRECT_IN, TAG_ROOT_TUN_IN), resolveTunInboundTags(Key.MODE_ROOT_MIXED))
        assertEquals(listOf(TAG_ROOT_REDIRECT_IN, TAG_ROOT_TUN_IN), resolveTunInboundTags(Key.MODE_ROOT_ENHANCE))
    }

    @Test
    fun sanitizeDirectDnsForRoot_preventsNetdLoopInRootModes() {
        val input = listOf("local", "hosts", "https://223.5.5.5/dns-query")
        assertEquals(input, sanitizeDirectDnsForRoot(input, isRootMode = false))
        assertEquals(
            listOf("https://223.5.5.5/dns-query"),
            sanitizeDirectDnsForRoot(listOf("local", "hosts"), isRootMode = true),
        )
        assertEquals(
            listOf("https://1.12.12.12/dns-query"),
            sanitizeDirectDnsForRoot(listOf("https://1.12.12.12/dns-query"), isRootMode = true),
        )
    }

    @Test
    fun shouldWarnPackageRuleNeedsVpn_silencesSmartRulesAndPureDirectRulesInRootMode() {
        val smartForeign = RuleEntity(id = 1, name = "Smart-Foreign-Apps", outbound = 0L, enabled = true, packages = setOf("org.telegram.messenger"))
        val smartDomestic = RuleEntity(id = 2, name = "Smart-Domestic-Apps-CN", outbound = -1L, enabled = true, packages = setOf("com.tencent.mobileqq"))
        val customDirectApp = RuleEntity(id = 3, name = "QQ", outbound = -1L, enabled = true, packages = setOf("com.tencent.mobileqq"))
        val customProxyApp = RuleEntity(id = 4, name = "Telegram", outbound = 0L, enabled = true, packages = setOf("org.telegram.messenger"))

        assertFalse(shouldWarnPackageRuleNeedsVpn(smartForeign, isRootMode = true))
        assertFalse(shouldWarnPackageRuleNeedsVpn(smartDomestic, isRootMode = true))
        assertFalse(shouldWarnPackageRuleNeedsVpn(customDirectApp, isRootMode = true))
        assertTrue(shouldWarnPackageRuleNeedsVpn(customProxyApp, isRootMode = true))
    }

    @Test
    fun shouldUseFakeDns_suppressesFakeDnsOnlyInRootWhitelist() {
        assertTrue(
            shouldUseFakeDns(
                enableFakeDns = true,
                forTest = false,
                serviceMode = Key.MODE_VPN,
                proxyApps = true,
                bypass = false,
            )
        )
        for (mode in listOf(Key.MODE_ROOT_TUN, Key.MODE_ROOT_REDIRECT, Key.MODE_ROOT_MIXED, Key.MODE_ROOT_ENHANCE)) {
            assertTrue(
                shouldUseFakeDns(
                    enableFakeDns = true,
                    forTest = false,
                    serviceMode = mode,
                    proxyApps = false,
                    bypass = false,
                )
            )
            assertTrue(
                shouldUseFakeDns(
                    enableFakeDns = true,
                    forTest = false,
                    serviceMode = mode,
                    proxyApps = true,
                    bypass = true,
                )
            )
            assertFalse(
                shouldUseFakeDns(
                    enableFakeDns = true,
                    forTest = false,
                    serviceMode = mode,
                    proxyApps = true,
                    bypass = false,
                )
            )
        }
    }

    @Test
    fun rootInbounds_configureCorrectSingBoxOptions() {
        val socks = buildRootTunSocksInbound(port = 45678, username = "u", password = "p")
        assertEquals("socks", socks.type)
        assertEquals(TAG_ROOT_TUN_IN, socks.tag)
        assertEquals(LOCALHOST, socks.listen)
        assertEquals(45678, socks.listen_port)
        assertEquals(300L, socks.udp_timeout)
        assertEquals(1, socks.users.size)

        val redir = buildRootRedirectInbound(port = 45679, listenAddress = "0.0.0.0")
        assertEquals("redirect", redir.type)
        assertEquals(TAG_ROOT_REDIRECT_IN, redir.tag)
        assertEquals("0.0.0.0", redir.listen)
        assertEquals(45679, redir.listen_port)

        val dnsIn = buildRootDnsInbound(port = 45680, listenAddress = LOCALHOST)
        assertEquals("direct", dnsIn.type)
        assertEquals(TAG_ROOT_DNS_IN, dnsIn.tag)
        assertEquals(LOCALHOST, dnsIn.listen)
        assertEquals(45680, dnsIn.listen_port)
        assertEquals("1.1.1.1", dnsIn.override_address)
        assertEquals(53, dnsIn.override_port)
    }

    @Test
    fun standardVpnAndProxyModes_remainUntouchedByRootLogic() {
        for (mode in listOf(Key.MODE_VPN, Key.MODE_PROXY)) {
            assertFalse(Key.isRootMode(mode))
            assertEquals(listOf("tun-in"), resolveTunInboundTags(mode))
            val untouchedDns = listOf("local", "hosts", "https://223.5.5.5/dns-query")
            assertEquals(untouchedDns, sanitizeDirectDnsForRoot(untouchedDns, isRootMode = false))
            assertTrue(
                shouldUseFakeDns(
                    enableFakeDns = true,
                    forTest = false,
                    serviceMode = mode,
                    proxyApps = true,
                    bypass = false,
                )
            )
        }
        assertTrue(Key.isTunOrRootMode(Key.MODE_VPN))
        assertFalse(Key.isTunOrRootMode(Key.MODE_PROXY))
    }
}
