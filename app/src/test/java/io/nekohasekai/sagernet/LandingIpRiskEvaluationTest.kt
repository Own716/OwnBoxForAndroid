package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.utils.IpRiskLevel
import io.nekohasekai.sagernet.utils.LandingIpInfo
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.utils.ServiceDiagnosticsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandingIpRiskEvaluationTest {

    @Test
    fun testCountryCodeToFlagEmoji() {
        assertEquals("🇺🇸", LandingIpManager.countryCodeToFlagEmoji("US"))
        assertEquals("🇯🇵", LandingIpManager.countryCodeToFlagEmoji("jp"))
        assertEquals("🇭🇰", LandingIpManager.countryCodeToFlagEmoji("HK"))
        assertEquals("🌐", LandingIpManager.countryCodeToFlagEmoji(null))
        assertEquals("🌐", LandingIpManager.countryCodeToFlagEmoji("USA"))
        assertEquals("🌐", LandingIpManager.countryCodeToFlagEmoji("12"))
    }

    @Test
    fun testParseProfileRegion() {
        // Emoji flag in profile name
        val parsed1 = LandingIpManager.parseProfileRegion("🇺🇸 美国 01 节点")
        assertEquals("🇺🇸", parsed1.first)
        assertEquals("US", parsed1.second)

        // Keywords in profile name
        val parsed2 = LandingIpManager.parseProfileRegion("香港 02 [BGP 高速]")
        assertEquals("🇭🇰", parsed2.first)
        assertEquals("HK", parsed2.second)

        val parsed3 = LandingIpManager.parseProfileRegion("Tokyo 01 Premium [JP]")
        assertEquals("🇯🇵", parsed3.first)
        assertEquals("JP", parsed3.second)

        // Unknown
        val parsedUnknown = LandingIpManager.parseProfileRegion("Custom Proxy")
        assertEquals("🌐", parsedUnknown.first)
        assertEquals("节点出口", parsedUnknown.second)
    }

    @Test
    fun testLandingIpInfoDefaultsAndIpVersion() {
        val infoV4 = LandingIpInfo(
            ip = "104.21.56.78",
            country = "United States",
            countryCode = "US",
            countryFlag = "🇺🇸",
            city = "San Jose",
            region = "CA",
            isp = "Cloudflare",
            org = "Cloudflare Anycast",
            asn = "AS13335",
            durationMs = 120,
        )
        assertEquals("IPv4", infoV4.ipVersion)
        assertEquals(IpRiskLevel.LOW, infoV4.riskLevel)
        assertTrue(infoV4.briefText.contains("104.21.56.78"))

        val infoV6 = LandingIpInfo(
            ip = "2606:4700::6811:384e",
            country = "United States",
            countryCode = "US",
            countryFlag = "🇺🇸",
            city = "San Jose",
            region = "CA",
            isp = "Cloudflare",
            org = "Cloudflare Anycast",
            asn = "AS13335",
            durationMs = 150,
        )
        assertEquals("IPv6", infoV6.ipVersion)
    }

    @Test
    fun testMaskedIpAndFlClashFields() {
        val infoV4 = LandingIpInfo(
            ip = "54.249.187.104",
            country = "Japan",
            countryCode = "JP",
            countryFlag = "🇯🇵",
            city = "Tokyo",
            region = "Tokyo",
            isp = "Amazon.com, Inc.",
            org = "Amazon.com, Inc.",
            asn = "AS16509",
            durationMs = 210,
            levelLabel = "普通",
            typeLabel = "机房",
            hitTag = "无",
            source = "ipquery.io",
        )
        assertEquals("54.249.*.*", infoV4.getMaskedIp())
        assertEquals("普通", infoV4.levelLabel)
        assertEquals("机房", infoV4.typeLabel)
        assertEquals("无", infoV4.hitTag)
        assertEquals("ipquery.io", infoV4.source)

        val infoV6 = LandingIpInfo(
            ip = "2400:cb00:2048:1::6814:260b",
            country = "Japan",
            countryCode = "JP",
            countryFlag = "🇯🇵",
            city = "Tokyo",
            region = "Tokyo",
            isp = "Cloudflare",
            org = "Cloudflare",
            asn = "AS13335",
            durationMs = 180,
        )
        assertEquals("2400:cb00:****:****", infoV6.getMaskedIp())
    }

    @Test
    fun testFlClashResidentialGoodLevel() {
        val info = LandingIpInfo(
            ip = "47.180.14.243",
            country = "United States",
            countryCode = "US",
            countryFlag = "🇺🇸",
            city = "San Jose",
            region = "CA",
            isp = "Verizon Business",
            org = "Verizon Business",
            asn = "AS5650",
            durationMs = 95,
            levelLabel = "优",
            typeLabel = "住宅",
            hitTag = "无",
            source = "iplocate.io",
        )
        assertEquals("优", info.levelLabel)
        assertEquals("住宅", info.typeLabel)
        assertEquals("Verizon Business", info.org)
        assertEquals("AS5650", info.asn)
        assertEquals("iplocate.io", info.source)
        assertEquals(IpRiskLevel.CLEAN, info.riskLevel)
    }

    @Test
    fun testInitialServiceListCountAndIds() {
        val list = ServiceDiagnosticsManager.createInitialList()
        assertTrue(list.isNotEmpty())
        val ids = list.map { it.id }.toSet()
        val expected = setOf(
            "openai", "claude", "gemini", "youtube",
            "netflix", "disney", "prime", "spotify", "tiktok", "discord", "bilibili",
            "google", "github"
        )
        assertTrue(ids.containsAll(expected))
    }
}
