package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsLeakTesterTest {

    @Test
    fun isFakeIpAddress_validatesExactV4AndV6Cidrs() {
        assertTrue(DnsLeakTester.isFakeIpAddress("198.18.0.1"))
        assertTrue(DnsLeakTester.isFakeIpAddress("198.19.255.254"))
        assertFalse(DnsLeakTester.isFakeIpAddress("198.17.255.254"))
        assertFalse(DnsLeakTester.isFakeIpAddress("198.20.0.1"))
        assertFalse(DnsLeakTester.isFakeIpAddress("142.250.72.206"))

        // fc00::/18 covers fc00:0000:: .. fc00:3fff:ffff:...
        assertTrue(DnsLeakTester.isFakeIpAddress("fc00::1"))
        assertTrue(DnsLeakTester.isFakeIpAddress("fc00:3fff::1"))
        assertTrue(DnsLeakTester.isFakeIpAddress("[fc00:1234::5]"))
        assertFalse(DnsLeakTester.isFakeIpAddress("fc00:4000::1"))
        assertFalse(DnsLeakTester.isFakeIpAddress("fd00::1"))
        assertFalse(DnsLeakTester.isFakeIpAddress("2001:4860:4860::8888"))
        assertFalse(DnsLeakTester.isFakeIpAddress("not-an-ip"))
    }

    @Test
    fun parsers_extractCloudflareTraceAndEdnsIpApi() {
        val trace = DnsLeakTester.parseCloudflareTrace(
            """
            fl=41f123
            ip=18.138.184.7
            ts=1795372948.123
            loc=sg
            """.trimIndent()
        )
        assertEquals("18.138.184.7" to "SG", trace)
        assertNull(DnsLeakTester.parseCloudflareTrace("loc=sg"))

        val edns = DnsLeakTester.parseEdnsIpApiResponse(
            """{"dns":{"geo":"Singapore - Google LLC","ip":"172.217.32.158"}}"""
        )
        assertEquals(DnsResolverInfo("172.217.32.158", "Singapore - Google LLC"), edns)
        assertNull(DnsLeakTester.parseEdnsIpApiResponse("""{"error":"not found"}"""))
    }

    @Test
    fun isDomesticChinaDns_distinguishesMainlandChinaFromGreaterChinaAndForeignResolvers() {
        assertTrue(DnsLeakTester.isDomesticChinaDns("China - China Telecom"))
        assertTrue(DnsLeakTester.isDomesticChinaDns("China - Alibaba (Hangzhou) Technology Co., Ltd."))
        assertTrue(DnsLeakTester.isDomesticChinaDns("CN - China Unicom"))
        assertTrue(DnsLeakTester.isDomesticChinaDns("Shenzhen Tencent Computer Systems"))

        assertFalse(DnsLeakTester.isDomesticChinaDns("Hong Kong - Google LLC"))
        assertFalse(DnsLeakTester.isDomesticChinaDns("Taiwan - Chunghwa Telecom Co. Ltd."))
        assertFalse(DnsLeakTester.isDomesticChinaDns("Macao - CTM"))
        assertFalse(DnsLeakTester.isDomesticChinaDns("Singapore - Google LLC"))
        assertFalse(DnsLeakTester.isDomesticChinaDns("United States - Cloudflare, Inc."))
        assertFalse(DnsLeakTester.isDomesticChinaDns(""))
    }

    @Test
    fun evaluateLeakVerdict_handlesAllScenariosHonestly() {
        assertEquals(
            LeakVerdict.DIRECT_NOT_RUNNING,
            DnsLeakTester.evaluateLeakVerdict(false, "CN", DnsResolverInfo("223.5.5.5", "China - Alibaba"), null),
        )
        assertEquals(
            LeakVerdict.INCONCLUSIVE,
            DnsLeakTester.evaluateLeakVerdict(true, "SG", null, null),
        )
        assertEquals(
            LeakVerdict.LEAK_RISK,
            DnsLeakTester.evaluateLeakVerdict(
                serviceRunning = true,
                proxyEgressLoc = "SG",
                proxyDns = DnsResolverInfo("172.217.32.158", "Singapore - Google LLC"),
                systemDns = DnsResolverInfo("202.96.209.133", "China - China Telecom"),
            ),
        )
        assertEquals(
            LeakVerdict.LEAK_RISK,
            DnsLeakTester.evaluateLeakVerdict(
                serviceRunning = true,
                proxyEgressLoc = "JP",
                proxyDns = DnsResolverInfo("223.5.5.5", "China - AliDNS"),
                systemDns = null,
            ),
        )
        assertEquals(
            LeakVerdict.SAFE,
            DnsLeakTester.evaluateLeakVerdict(
                serviceRunning = true,
                proxyEgressLoc = "SG",
                proxyDns = DnsResolverInfo("172.217.32.158", "Singapore - Google LLC"),
                systemDns = DnsResolverInfo("172.217.32.159", "Singapore - Google LLC"),
            ),
        )
    }

    @Test
    fun formatReport_explainsModesClearlyWithoutEmojis() {
        val proxyReportText = DnsLeakTester.formatReport(
            DnsLeakReport(
                serviceRunning = true,
                serviceMode = Key.MODE_PROXY,
                fakeIp = FakeIpProbeResult(supportedInMode = false, enabledInSettings = true, active = false),
                egressIp = "18.138.184.7",
                egressLoc = "SG",
                proxyDns = DnsResolverInfo("172.217.32.158", "Singapore - Google LLC"),
                verdict = LeakVerdict.SAFE,
            )
        )
        assertTrue(proxyReportText.contains("仅代理模式"))
        assertTrue(proxyReportText.contains("仅代理模式无 TUN 虚拟网卡，不使用 Fake-IP"))
        assertTrue(proxyReportText.contains("18.138.184.7 (SG)"))
        assertTrue(proxyReportText.contains("172.217.32.158 (Singapore - Google LLC)"))
        assertTrue(proxyReportText.contains("未发现 DNS 泄漏"))
        assertFalse("Diagnostic output must contain no surrogate emojis", proxyReportText.any { Character.isSurrogate(it) })
    }
}
