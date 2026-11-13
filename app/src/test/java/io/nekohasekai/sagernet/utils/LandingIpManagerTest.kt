package io.nekohasekai.sagernet.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LandingIpManagerTest {

    private fun sampleInfo(country: String, ip: String, queryTimestamp: Long) = LandingIpInfo(
        ip = ip,
        country = country,
        countryCode = "JP",
        countryFlag = "🇯🇵",
        city = "Tokyo",
        region = "Tokyo",
        isp = "TestISP",
        org = "TestOrg",
        asn = "AS1234",
        durationMs = 120L,
        queryTimestamp = queryTimestamp,
    )

    @After
    fun tearDown() {
        LandingIpManager.clearCache()
    }

    @Test
    fun getCachedInfo_rejectsMismatchedOrInvalidProfileId() {
        val now = System.currentTimeMillis()
        LandingIpManager.setCacheForTest(101L, sampleInfo("日本", "1.2.3.4", now))

        val matched = LandingIpManager.getCachedInfo(expectedProfileId = 101L)
        assertNotNull(matched)
        assertEquals("日本", matched?.country)

        assertNull(LandingIpManager.getCachedInfo(expectedProfileId = 202L))
        assertNull(LandingIpManager.getCachedInfo(expectedProfileId = 0L))
        assertNull(LandingIpManager.getCachedInfo(expectedProfileId = -1L))
    }

    @Test
    fun getCachedInfo_rejectsExpiredCache() {
        val expiredTimestamp = System.currentTimeMillis() - LandingIpManager.CACHE_TTL_MS - 1_000L
        LandingIpManager.setCacheForTest(101L, sampleInfo("日本", "1.2.3.4", expiredTimestamp))

        assertNull(LandingIpManager.getCachedInfo(expectedProfileId = 101L))
    }

    @Test
    fun clearCache_removesStaleProfileAndData() {
        val now = System.currentTimeMillis()
        LandingIpManager.setCacheForTest(101L, sampleInfo("新加坡", "5.6.7.8", now))
        assertNotNull(LandingIpManager.getCachedInfo(expectedProfileId = 101L))

        LandingIpManager.clearCache()
        assertEquals(-1L, LandingIpManager.cachedProfileId)
        assertNull(LandingIpManager.getCachedInfo())
        assertNull(LandingIpManager.getCachedInfo(expectedProfileId = 101L))
    }
}
