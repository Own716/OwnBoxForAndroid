package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateFormatterTest {

    private val sampleInfo = LiveUpdateFormatter.LiveUpdateInfo(
        profileName = "Hong Kong 01",
        groupName = "Premium Group",
        downSpeed = "12.5 MB/s",
        upSpeed = "1.2 MB/s",
        totalDownTraffic = "1.4 GB",
        totalUpTraffic = "230 MB",
        totalTraffic = "1.6 GB",
        duration = "01:23:45",
        protocol = "VLESS",
        latency = "45ms",
        directDownSpeed = "100 KB/s",
        directUpSpeed = "50 KB/s",
        ip = "104.28.19.24",
        server = "hk01.example.com",
        country = "香港",
    )

    @Test
    fun testStandardVariableReplacements() {
        assertEquals("香港", LiveUpdateFormatter.format("{location}", sampleInfo))
        assertEquals("香港", LiveUpdateFormatter.format("{country}", sampleInfo))
        assertEquals("香港", LiveUpdateFormatter.format("{ip_location}", sampleInfo))
        assertEquals("香港", LiveUpdateFormatter.format("{country_name}", sampleInfo))
        assertEquals("Hong Kong 01", LiveUpdateFormatter.format("{profile}", sampleInfo))
        assertEquals("Premium Group", LiveUpdateFormatter.format("{group}", sampleInfo))
        assertEquals("12.5 MB/s", LiveUpdateFormatter.format("{down}", sampleInfo))
        assertEquals("1.2 MB/s", LiveUpdateFormatter.format("{up}", sampleInfo))
        assertEquals("104.28.19.24", LiveUpdateFormatter.format("{ip}", sampleInfo))
        assertEquals("hk01.example.com", LiveUpdateFormatter.format("{server}", sampleInfo))
        assertEquals("1.4 GB", LiveUpdateFormatter.format("{traffic_down}", sampleInfo))
        assertEquals("230 MB", LiveUpdateFormatter.format("{traffic_up}", sampleInfo))
        assertEquals("1.6 GB", LiveUpdateFormatter.format("{traffic_total}", sampleInfo))
        assertEquals("01:23:45", LiveUpdateFormatter.format("{duration}", sampleInfo))
        assertEquals("VLESS", LiveUpdateFormatter.format("{protocol}", sampleInfo))
        assertEquals("45ms", LiveUpdateFormatter.format("{latency}", sampleInfo))
        assertEquals("100 KB/s", LiveUpdateFormatter.format("{direct_down}", sampleInfo))
        assertEquals("50 KB/s", LiveUpdateFormatter.format("{direct_up}", sampleInfo))
    }

    @Test
    fun testFullWidthBracesSupport() {
        assertEquals("香港", LiveUpdateFormatter.format("｛location｝", sampleInfo))
        assertEquals("香港", LiveUpdateFormatter.format("｛country｝", sampleInfo))
        assertEquals("Hong Kong 01", LiveUpdateFormatter.format("｛profile｝", sampleInfo))
        assertEquals("Premium Group", LiveUpdateFormatter.format("｛group｝", sampleInfo))
        assertEquals("104.28.19.24", LiveUpdateFormatter.format("｛ip｝", sampleInfo))
    }

    @Test
    fun testCaseInsensitiveAndWhitespaceTolerance() {
        assertEquals("香港", LiveUpdateFormatter.format("{Location}", sampleInfo))
        assertEquals("香港", LiveUpdateFormatter.format("{LOCATION}", sampleInfo))
        assertEquals("香港", LiveUpdateFormatter.format("{ location }", sampleInfo))
        assertEquals("香港", LiveUpdateFormatter.format("｛ Location ｝", sampleInfo))
        assertEquals("Hong Kong 01", LiveUpdateFormatter.format("{ Profile }", sampleInfo))
        assertEquals("Premium Group", LiveUpdateFormatter.format("{ GROUP }", sampleInfo))
    }

    @Test
    fun testEmptyDirectSpeedCleanup() {
        val noDirectInfo = sampleInfo.copy(directDownSpeed = "", directUpSpeed = "")
        val formatted = LiveUpdateFormatter.format(
            "Hong Kong 01 | 代理：↑ {up} ↓ {down} | 直连：↑ {direct_up} ↓ {direct_down}",
            noDirectInfo
        )
        assertEquals("Hong Kong 01 | 代理：↑ 1.2 MB/s ↓ 12.5 MB/s", formatted)
        assertFalse(formatted.contains("直连"))
    }

    @Test
    fun testEmptyGroupCleanup() {
        val noGroupInfo = sampleInfo.copy(groupName = "")
        assertEquals("Hong Kong 01", LiveUpdateFormatter.format("[{group}] {profile}", noGroupInfo))
        assertEquals("Hong Kong 01", LiveUpdateFormatter.format("【{group}】{profile}", noGroupInfo))
    }

    @Test
    fun testUnknownPlaceholderSanitization() {
        assertEquals("Hong Kong 01", LiveUpdateFormatter.format("{profile} {unknown_variable}", sampleInfo))
        assertFalse(LiveUpdateFormatter.format("{foo}", sampleInfo).contains("{"))
    }

    @Test
    fun testInferCountryFromName() {
        assertEquals("日本", LiveUpdateFormatter.inferCountryFromName("🇯🇵 日本 01 - 专线"))
        assertEquals("日本", LiveUpdateFormatter.inferCountryFromName("Tokyo 02"))
        assertEquals("香港", LiveUpdateFormatter.inferCountryFromName("🇭🇰 香港 HK-05"))
        assertEquals("香港", LiveUpdateFormatter.inferCountryFromName("Hong Kong 01"))
        assertEquals("新加坡", LiveUpdateFormatter.inferCountryFromName("Singapore 01"))
        assertEquals("美国", LiveUpdateFormatter.inferCountryFromName("US Los Angeles"))
    }

    @After
    fun tearDown() {
        ActiveOutboundTracker.reset()
    }

    @Test
    fun testRequiresEgressProbeOnlyWhenTemplatesReferenceEgressFields() {
        val noEgress = LiveUpdateFormatter.Templates(
            collapsed = "{profile}",
            title = "[{group}] {profile}",
            content = "↓ {down} ↑ {up}",
            subText = "{protocol} | {duration}",
        )
        assertFalse(LiveUpdateFormatter.requiresEgressProbe(noEgress))

        assertTrue(
            LiveUpdateFormatter.requiresEgressProbe(
                noEgress.copy(collapsed = "{country}")
            )
        )
        assertTrue(
            LiveUpdateFormatter.requiresEgressProbe(
                noEgress.copy(collapsed = "｛ Location ｝")
            )
        )
        assertTrue(
            LiveUpdateFormatter.requiresEgressProbe(
                noEgress.copy(subText = "{profile} | {ip}")
            )
        )
        assertTrue(
            LiveUpdateFormatter.requiresEgressProbe(
                noEgress.copy(content = "{egress_ip} / {region}")
            )
        )
    }

    @Test
    fun testResolveEffectiveProfileIdPrefersActiveLeafOnlyForStrategyProfiles() {
        val singleNode = ProxyEntity().apply {
            id = 101L
            type = 0
            putBean(ShadowsocksBean().apply { name = "日本 01" })
        }
        val balancer = ProxyEntity().apply {
            id = 200L
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply { name = "自动选择" })
        }

        ActiveOutboundTracker.reset()
        assertEquals(101L, LiveUpdateFormatter.resolveEffectiveProfileId(singleNode))
        assertEquals(200L, LiveUpdateFormatter.resolveEffectiveProfileId(balancer))
        assertEquals(55L, LiveUpdateFormatter.resolveEffectiveProfileId(null, fallbackProfileId = 55L))

        ActiveOutboundTracker.updateActiveLeaf(305L, "新加坡 01")
        // Single node ignores leaf ID; strategy balancer resolves active leaf ID.
        assertEquals(101L, LiveUpdateFormatter.resolveEffectiveProfileId(singleNode))
        assertEquals(305L, LiveUpdateFormatter.resolveEffectiveProfileId(balancer))
    }
}
