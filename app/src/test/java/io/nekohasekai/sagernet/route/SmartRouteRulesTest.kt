package io.nekohasekai.sagernet.route

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
}
