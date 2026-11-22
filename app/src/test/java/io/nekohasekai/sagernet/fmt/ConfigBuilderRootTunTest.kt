package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.RuleEntity
import moe.matsuri.nb4a.SingBoxOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderRootTunTest {

    @Test
    fun resolveTunInboundTags_mapsAllSixServiceModes() {
        assertEquals(listOf("tun-in"), resolveTunInboundTags(Key.MODE_VPN))
        assertEquals(listOf("tun-in"), resolveTunInboundTags(Key.MODE_PROXY))
        assertEquals(listOf(TAG_ROOT_TUN_IN), resolveTunInboundTags(Key.MODE_ROOT_TUN))
        assertEquals(
            listOf(TAG_ROOT_REDIRECT_IN, TAG_ROOT_DNS_IN, TAG_ROOT_DNS_LAN_IN),
            resolveTunInboundTags(Key.MODE_ROOT_REDIRECT),
        )
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
    fun shouldWarnPackageRuleNeedsVpn_silencesSmartRulesAndNativeRootOrHybridRulesInRootMode() {
        val smartForeign = RuleEntity(id = 1, name = "Smart-Foreign-Apps", outbound = 0L, enabled = true, packages = setOf("org.telegram.messenger"))
        val smartDomestic = RuleEntity(id = 2, name = "Smart-Domestic-Apps-CN", outbound = -1L, enabled = true, packages = setOf("com.tencent.mobileqq"))
        val customDirectApp = RuleEntity(id = 3, name = "QQ", outbound = -1L, enabled = true, packages = setOf("com.tencent.mobileqq"))
        val customBlockApp = RuleEntity(id = 4, name = "BlockedApp", outbound = -2L, enabled = true, packages = setOf("com.example.ads"))
        val hybridPlayRule = RuleEntity(
            id = 5,
            name = "PlayStore",
            outbound = 0L,
            enabled = true,
            domains = "domain:googleapis.cn",
            packages = setOf("com.android.vending"),
        )
        val customProxyApp = RuleEntity(id = 6, name = "Telegram", outbound = 0L, enabled = true, packages = setOf("org.telegram.messenger"))

        assertFalse(shouldWarnPackageRuleNeedsVpn(smartForeign, isRootMode = true))
        assertFalse(shouldWarnPackageRuleNeedsVpn(smartDomestic, isRootMode = true))
        assertFalse(shouldWarnPackageRuleNeedsVpn(customDirectApp, isRootMode = true))
        assertFalse(shouldWarnPackageRuleNeedsVpn(customBlockApp, isRootMode = true))
        assertFalse(shouldWarnPackageRuleNeedsVpn(hybridPlayRule, isRootMode = true))
        assertTrue(shouldWarnPackageRuleNeedsVpn(customProxyApp, isRootMode = true))
    }

    @Test
    fun shouldUseFakeDns_disablesFakeDnsInAllRootModesAndPreservesVpnProxy() {
        for (mode in listOf(Key.MODE_VPN, Key.MODE_PROXY)) {
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
                    bypass = false,
                )
            )
        }
        for (mode in listOf(Key.MODE_ROOT_TUN, Key.MODE_ROOT_REDIRECT, Key.MODE_ROOT_MIXED, Key.MODE_ROOT_ENHANCE)) {
            for ((proxyApps, bypass) in listOf(false to false, true to true, true to false)) {
                assertFalse(
                    "FakeDNS must stay off in $mode (proxyApps=$proxyApps, bypass=$bypass)",
                    shouldUseFakeDns(
                        enableFakeDns = true,
                        forTest = false,
                        serviceMode = mode,
                        proxyApps = proxyApps,
                        bypass = bypass,
                    )
                )
            }
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

        val dnsLocal = buildRootDnsInbound(port = 45680, listenAddress = LOCALHOST, inboundTag = TAG_ROOT_DNS_IN)
        assertEquals("direct", dnsLocal.type)
        assertEquals(TAG_ROOT_DNS_IN, dnsLocal.tag)
        assertEquals(LOCALHOST, dnsLocal.listen)
        assertEquals(45680, dnsLocal.listen_port)
        assertEquals("1.1.1.1", dnsLocal.override_address)
        assertEquals(53, dnsLocal.override_port)

        val dnsLan = buildRootDnsInbound(port = 45681, listenAddress = "0.0.0.0", inboundTag = TAG_ROOT_DNS_LAN_IN)
        assertEquals("direct", dnsLan.type)
        assertEquals(TAG_ROOT_DNS_LAN_IN, dnsLan.tag)
        assertEquals("0.0.0.0", dnsLan.listen)
        assertEquals(45681, dnsLan.listen_port)
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

    @Test
    fun syncRuleSetDownloadDetours_rewritesStaleProxyTagToMainProxyTag() {
        val remoteDefault = SingBoxOptions.RuleSet().apply {
            type = "remote"
            tag = "geosite-cn"
            download_detour = TAG_PROXY
        }
        val remoteCustom = SingBoxOptions.RuleSet().apply {
            type = "remote"
            tag = "geosite-custom"
            download_detour = "HK Node"
        }
        val localSet = SingBoxOptions.RuleSet().apply {
            type = "local"
            tag = "local-set"
        }
        val available = setOf("JP Node", "HK Node", TAG_DIRECT, TAG_BYPASS)

        syncRuleSetDownloadDetours(listOf(remoteDefault, remoteCustom, localSet), available, mainProxyTag = "JP Node")

        assertEquals("JP Node", remoteDefault.download_detour)
        assertEquals("HK Node", remoteCustom.download_detour)
        assertNull(localSet.download_detour)
    }

    @Test
    fun remapInboundTagsForServiceMode_mapsTunInForRootModesOnly() {
        assertEquals(listOf("tun-in", "mixed-in"), remapInboundTagsForServiceMode(listOf("tun-in", "mixed-in"), Key.MODE_VPN))
        assertEquals(listOf(TAG_ROOT_TUN_IN, "mixed-in"), remapInboundTagsForServiceMode(listOf("tun-in", "mixed-in"), Key.MODE_ROOT_TUN))
        assertEquals(
            listOf(TAG_ROOT_REDIRECT_IN, TAG_ROOT_DNS_IN, TAG_ROOT_DNS_LAN_IN),
            remapInboundTagsForServiceMode(listOf("tun-in"), Key.MODE_ROOT_REDIRECT),
        )
        assertEquals(
            listOf(TAG_ROOT_REDIRECT_IN, TAG_ROOT_TUN_IN),
            remapInboundTagsForServiceMode(listOf("tun-in"), Key.MODE_ROOT_ENHANCE),
        )

        val remappedJson = remapRuleCustomConfigForServiceMode(
            """{"inbound":["tun-in","mixed-in"],"ip_version":4}""",
            Key.MODE_ROOT_TUN,
        )!!
        assertTrue(remappedJson.contains(TAG_ROOT_TUN_IN))
        assertFalse(remappedJson.contains("\"tun-in\""))
        assertTrue(remappedJson.contains("\"mixed-in\""))
    }

    @Test
    fun googlePlayDomainSuffixes_coverAllDownloadCdnSubdomains() {
        listOf(
            "googleapis.cn",
            "google.cn",
            "play.googleapis.com",
            "play.google.com",
            "dl.google.com",
            "dl-ssl.google.com",
            "xn--ngstr-lra8j.com",
            "xn--ngstr-cn-8za9o.com",
            "gvt1.com",
            "gvt1-cn.com",
            "ggpht.com",
        ).forEach { expected ->
            assertTrue("Missing Play suffix $expected", expected in GOOGLE_PLAY_DOMAIN_SUFFIXES)
        }
    }
}
