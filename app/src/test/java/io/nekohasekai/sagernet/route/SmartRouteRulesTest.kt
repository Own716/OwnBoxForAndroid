package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRouteRulesTest {

    @Test
    fun classifyAppsSeparatesDomesticForeignAndExcludesBrowsers() {
        val sampleInstalled = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.icbc.im",
            "cn.gov.tax.its",
            "org.telegram.messenger",
            "com.google.android.youtube",
            "com.openai.chatgpt",
            "com.zhiliaoapp.musically",
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx"
        )

        val (domesticCn, foreignCn) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = setOf("com.android.chrome", "org.telegram.messenger", "android")
        )

        // 国内应用应归入直连
        assertTrue(domesticCn.contains("com.tencent.mm"))
        assertTrue(domesticCn.contains("com.eg.android.AlipayGphone"))
        assertTrue(domesticCn.contains("com.icbc.im"))
        assertTrue(domesticCn.contains("cn.gov.tax.its"))

        // 国外专属应用（含 TikTok 国际版）应归入代理，绝不被字节前缀误判为国内
        assertTrue(foreignCn.contains("org.telegram.messenger"))
        assertTrue(foreignCn.contains("com.google.android.youtube"))
        assertTrue(foreignCn.contains("com.openai.chatgpt"))
        assertTrue(foreignCn.contains("com.zhiliaoapp.musically"))
        assertFalse(domesticCn.contains("com.zhiliaoapp.musically"))

        // 浏览器与系统核心不得被锁死在应用层，确保其内部网页流量交由下层域名/IP 动态分流
        assertFalse(domesticCn.contains("com.android.chrome"))
        assertFalse(foreignCn.contains("com.android.chrome"))
        assertFalse(domesticCn.contains("org.mozilla.firefox"))
        assertFalse(foreignCn.contains("org.mozilla.firefox"))
        assertFalse(domesticCn.contains("com.microsoft.emmx"))
        assertFalse(foreignCn.contains("com.microsoft.emmx"))
        assertFalse(foreignCn.contains("android"))
    }

    @Test
    fun buildSmartRulesAdaptsToSelectedCountry() {
        val cnRules = SmartRouteRules.buildSmartRules("cn")
        assertTrue(cnRules.any { it.outbound == -1L && it.domains.contains("geosite:cn") && it.domains.contains("domain:cn") })
        assertTrue(cnRules.any { it.outbound == -1L && it.ip.contains("geoip:cn") })

        val ruRules = SmartRouteRules.buildSmartRules("ru")
        assertTrue(ruRules.any { it.outbound == -1L && it.domains.contains("geosite:ru") && it.domains.contains("domain:ru") })
        assertTrue(ruRules.any { it.outbound == -1L && it.ip.contains("geoip:ru") })

        val irRules = SmartRouteRules.buildSmartRules("ir")
        assertTrue(irRules.any { it.outbound == -1L && it.domains.contains("geosite:ir") && it.domains.contains("domain:ir") })
        assertTrue(irRules.any { it.outbound == -1L && it.ip.contains("geoip:ir") })
    }

    @Test
    fun classifyAppsRespectsPerAppProxySelection() {
        val sampleInstalled = setOf(
            "tv.danmaku.bili",
            "com.tencent.mm",
            "org.telegram.messenger"
        )

        // 当分应用代理开启且为「仅代理选中应用 (!bypassMode)」时，手动勾选的国内应用 tv.danmaku.bili 应走代理而非强制直连
        val (domesticProxyMode, foreignProxyMode) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = emptySet(),
            proxyAppsEnabled = true,
            bypassMode = false,
            individualPackages = setOf("tv.danmaku.bili")
        )
        assertFalse(domesticProxyMode.contains("tv.danmaku.bili"))
        assertTrue(foreignProxyMode.contains("tv.danmaku.bili"))

        // 当分应用代理开启且为「绕过选中应用 (bypassMode)」时，手动勾选绕过的应用应从代理名单移除并归入直连
        val (domesticBypassMode, foreignBypassMode) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = emptySet(),
            proxyAppsEnabled = true,
            bypassMode = true,
            individualPackages = setOf("org.telegram.messenger")
        )
        assertFalse(foreignBypassMode.contains("org.telegram.messenger"))
        assertTrue(domesticBypassMode.contains("org.telegram.messenger"))
    }

    @Test
    fun classifyAppsExcludesSystemDownloadAndNetworkPackages() {
        val systemPkgs = setOf(
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.captiveportallogin",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.externalstorage",
            "com.android.shell",
        )
        val (domestic, foreign) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = systemPkgs + "com.tencent.mm",
            bundledForeign = systemPkgs,
            proxyAppsEnabled = false,
            individualPackages = emptySet()
        )
        for (pkg in systemPkgs) {
            assertFalse("System package $pkg must not be classified as domestic", domestic.contains(pkg))
            assertFalse("System package $pkg must not be classified as foreign", foreign.contains(pkg))
        }
        assertTrue(domestic.contains("com.tencent.mm"))
    }

    @Test
    fun classifyAppsDomesticPrefixBeatsBundledForeign() {
        val (domestic, foreign) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = setOf("com.tencent.huatuo", "org.telegram.messenger"),
            bundledForeign = setOf("com.tencent.huatuo"),
            proxyAppsEnabled = false,
            individualPackages = emptySet()
        )
        assertTrue(domestic.contains("com.tencent.huatuo"))
        assertFalse(foreign.contains("com.tencent.huatuo"))
        assertTrue(foreign.contains("org.telegram.messenger"))
    }

    @Test
    fun classifyAppsLeavesOrdinaryAppsUnclassifiedAndOmitsUninstalled() {
        val (domestic, foreign) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = setOf("com.example.unknownapp", "com.tencent.mm"),
            bundledForeign = emptySet(),
            proxyAppsEnabled = false,
            individualPackages = emptySet()
        )
        assertFalse(domestic.contains("com.example.unknownapp"))
        assertFalse(foreign.contains("com.example.unknownapp"))
        assertTrue(domestic.contains("com.tencent.mm"))
        assertFalse(foreign.contains("org.telegram.messenger"))
        assertFalse(domestic.contains("com.eg.android.AlipayGphone"))
    }

    @Test
    fun classifyAppsDefaultsToEmptyBundledForeign() {
        val (domestic, foreign) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = setOf("com.tencent.mm", "com.android.providers.downloads"),
            proxyAppsEnabled = false,
            individualPackages = emptySet()
        )
        assertEquals(setOf("com.tencent.mm"), domestic)
        assertTrue(foreign.isEmpty())
    }
}
