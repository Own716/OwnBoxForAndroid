package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BalancerBeanMigrationTest {

    private fun buildLegacyBlob(
        version: Int,
        balancerType: Int = BalancerBean.TYPE_MIXED,
        targetGroupId: Long = 12L,
        strategy: String = BalancerBean.STRATEGY_LEAST_PING,
        testUrl: String = "https://example.com/204",
        interval: Int = 180,
        proxies: List<Long> = listOf(101L, 102L, 103L),
        targetGroupIds: List<Long> = listOf(12L, 15L),
        tolerance: Int = 50,
        toleranceUnit: String = "ms",
        useFrontProxy: Boolean = true,
        useLandingProxy: Boolean = false,
        nameExclude: String = "Traffic|Expire",
        nameInclude: String = "SG|JP",
        frontProxy: Long = 77L,
        landingProxy: Long = 88L,
        selectionMode: String = BalancerBean.SELECTION_PRIORITY,
        balanceEnabled: Boolean = true,
        balanceMode: String = BalancerBean.BALANCE_PER_CONNECTION,
        balanceIntervalSec: Int = 45,
        countryFilter: String = "SG, JP",
        interruptOnSwitch: Boolean = true,
        learnFromConnections: Boolean = false,
        tiers: List<BalancerBean.PriorityTier> = emptyList(),
        name: String = "Test Balancer",
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = baos.byteBuffer()
        out.writeInt(version)
        out.writeInt(balancerType)
        out.writeLong(targetGroupId)
        out.writeString(strategy)
        out.writeString(testUrl)
        out.writeInt(interval)
        out.writeInt(proxies.size)
        proxies.forEach { out.writeLong(it) }
        if (version >= 2) {
            out.writeInt(targetGroupIds.size)
            targetGroupIds.forEach { out.writeLong(it) }
        }
        if (version >= 3) {
            out.writeInt(tolerance)
            out.writeString(toleranceUnit)
        }
        if (version >= 4) {
            out.writeBoolean(useFrontProxy)
            out.writeBoolean(useLandingProxy)
            out.writeString(nameExclude)
            out.writeString(nameInclude)
        }
        if (version >= 5) {
            out.writeLong(frontProxy)
            out.writeLong(landingProxy)
        }
        if (version >= 6) {
            out.writeString(selectionMode)
            out.writeBoolean(balanceEnabled)
            out.writeString(balanceMode)
            out.writeInt(balanceIntervalSec)
            out.writeString(countryFilter)
            out.writeInt(300) // legacy runTopN
            out.writeInt(1000) // legacy rankTopN
            out.writeInt(1440) // legacy resultTrustMinutes
            out.writeBoolean(false) // legacy skipFailedWithinTrust
            out.writeInt(3) // legacy standbySize
            out.writeInt(8) // legacy intensiveCheckCount
            out.writeInt(600) // legacy fullScanIntervalSec
            out.writeInt(15) // legacy watchIntervalSec
            out.writeInt(10) // legacy sampleCount
            out.writeInt(500) // legacy maxRttMs
            out.writeInt(2) // legacy dialRetries
            out.writeBoolean(interruptOnSwitch)
            out.writeInt(3) // legacy failbackChecks
            out.writeInt(60) // legacy failbackDelaySec
            out.writeBoolean(true) // legacy interruptOnFailback
            out.writeBoolean(learnFromConnections)
            out.writeInt(60) // legacy siteStickyMinutes
            out.writeString("https://connectivity.example.com") // legacy connectivityUrl
        }
        if (version >= 7) {
            out.writeInt(tiers.size)
            tiers.forEach { tier ->
                out.writeLong(tier.groupId)
                out.writeString(tier.nameFilter)
            }
        }
        // AbstractBean extra section
        out.writeInt(1)
        out.writeString(name)
        out.writeString("")
        out.writeString("")
        out.flush()
        out.close()
        return baos.toByteArray()
    }

    @Test
    fun `fresh BalancerBean uses unified mobile-safe defaults`() {
        val bean = BalancerBean().apply { initializeDefaultValues() }
        assertEquals(BalancerBean.SELECTION_LATENCY, bean.selectionMode)
        assertFalse(bean.isPriorityMode())
        assertFalse(bean.balanceEnabled)
        assertEquals(BalancerBean.BALANCE_TIMED_ROTATION, bean.balanceMode)
        assertEquals(30, bean.balanceIntervalSec)
        assertEquals("", bean.countryFilter)
        assertEquals(300, bean.interval)
        assertEquals(30, bean.tolerance)
        assertEquals("ms", bean.toleranceUnit)
        assertFalse(bean.interruptOnSwitch)
        assertTrue(bean.learnFromConnections)
        assertEquals(-1L, bean.frontProxy)
        assertEquals(-1L, bean.landingProxy)
    }

    @Test
    fun `v1 failover blob migrates to priority mode keeping proxies and target group`() {
        val bytes = buildLegacyBlob(
            version = 1,
            balancerType = BalancerBean.TYPE_GROUP,
            targetGroupId = 99L,
            strategy = BalancerBean.STRATEGY_FAILOVER,
            proxies = listOf(11L, 22L),
        )
        val bean = KryoConverters.deserialize(BalancerBean(), bytes)
        assertEquals(BalancerBean.SELECTION_PRIORITY, bean.selectionMode)
        assertTrue(bean.isPriorityMode())
        assertFalse(bean.balanceEnabled)
        assertEquals(listOf(99L), bean.targetGroupIds)
        assertEquals(listOf(11L, 22L), bean.proxies)
        assertEquals(BalancerBean.TYPE_GROUP, bean.balancerType)
        assertFalse(bean.interruptOnSwitch)
    }

    @Test
    fun `v4 and v5 distribution strategies migrate accurately preserving active legacy fields`() {
        val v4RoundRobin = KryoConverters.deserialize(
            BalancerBean(),
            buildLegacyBlob(version = 4, strategy = BalancerBean.STRATEGY_ROUND_ROBIN, tolerance = 80)
        )
        assertEquals(BalancerBean.SELECTION_LATENCY, v4RoundRobin.selectionMode)
        assertTrue(v4RoundRobin.balanceEnabled)
        assertEquals(BalancerBean.BALANCE_PER_CONNECTION, v4RoundRobin.balanceMode)
        assertEquals(80, v4RoundRobin.tolerance)
        assertEquals("Traffic|Expire", v4RoundRobin.nameExclude)
        assertEquals("SG|JP", v4RoundRobin.nameInclude)

        val v5Consistent = KryoConverters.deserialize(
            BalancerBean(),
            buildLegacyBlob(
                version = 5,
                strategy = BalancerBean.STRATEGY_CONSISTENT_HASH_CAMEL,
                frontProxy = 501L,
                landingProxy = 502L,
            )
        )
        assertEquals(BalancerBean.SELECTION_LATENCY, v5Consistent.selectionMode)
        assertTrue(v5Consistent.balanceEnabled)
        assertEquals(BalancerBean.BALANCE_TIMED_ROTATION, v5Consistent.balanceMode)
        assertTrue(v5Consistent.learnFromConnections)
        assertEquals(501L, v5Consistent.frontProxy)
        assertEquals(502L, v5Consistent.landingProxy)

        val v5LeastPing = KryoConverters.deserialize(
            BalancerBean(),
            buildLegacyBlob(version = 5, strategy = BalancerBean.STRATEGY_STABLE, tolerance = 75)
        )
        assertEquals(BalancerBean.SELECTION_LATENCY, v5LeastPing.selectionMode)
        assertFalse(v5LeastPing.balanceEnabled)
        assertEquals(75, v5LeastPing.tolerance)
    }

    @Test
    fun `v8 round trip preserves active fields and formats displayAddress cleanly`() {
        val original = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_MIXED
            targetGroupIds = mutableListOf(1L, 2L)
            proxies = mutableListOf(10L, 20L, 30L)
            selectionMode = BalancerBean.SELECTION_PRIORITY
            balanceEnabled = true
            balanceMode = BalancerBean.BALANCE_PER_CONNECTION
            balanceIntervalSec = 45
            countryFilter = "SG, JP"
            interval = 240
            tolerance = 40
            interruptOnSwitch = true
            learnFromConnections = false
            frontProxy = 8L
            landingProxy = 9L
        }

        val cloned = original.clone()
        assertEquals(BalancerBean.SELECTION_PRIORITY, cloned.selectionMode)
        assertTrue(cloned.balanceEnabled)
        assertEquals(BalancerBean.BALANCE_PER_CONNECTION, cloned.balanceMode)
        assertEquals(45, cloned.balanceIntervalSec)
        assertEquals("SG, JP", cloned.countryFilter)
        assertEquals(240, cloned.interval)
        assertEquals(40, cloned.tolerance)
        assertTrue(cloned.interruptOnSwitch)
        assertFalse(cloned.learnFromConnections)
        assertEquals(8L, cloned.frontProxy)
        assertEquals(9L, cloned.landingProxy)

        val summary = cloned.displayAddress()
        assertTrue("Expected priority label in $summary", summary.contains("按优先级"))
        assertTrue("Expected distribution label in $summary", summary.contains("流量分配"))
        assertTrue(cloned.tiers.isEmpty())
    }

    @Test
    fun `v8 round trip preserves priority tiers and json conversion`() {
        val original = BalancerBean().apply {
            initializeDefaultValues()
            selectionMode = BalancerBean.SELECTION_PRIORITY
            balanceEnabled = true
            tiers = mutableListOf(
                BalancerBean.PriorityTier(10L, "IEPL|IPLC"),
                BalancerBean.PriorityTier(-1L, "HK|SG"),
            )
        }

        val cloned = original.clone()
        assertEquals(2, cloned.tiers.size)
        assertEquals(10L, cloned.tiers[0].groupId)
        assertEquals("IEPL|IPLC", cloned.tiers[0].nameFilter)
        assertEquals(-1L, cloned.tiers[1].groupId)
        assertEquals("HK|SG", cloned.tiers[1].nameFilter)

        val summary = cloned.displayAddress()
        assertTrue("Expected tier count in $summary", summary.contains("2层级"))

        val json = BalancerBean.tiersToJson(cloned.tiers)
        val parsed = BalancerBean.parseTiersJson(json)
        assertEquals(2, parsed.size)
        assertEquals(10L, parsed[0].groupId)
        assertEquals("IEPL|IPLC", parsed[0].nameFilter)
        assertEquals(-1L, parsed[1].groupId)
        assertEquals("HK|SG", parsed[1].nameFilter)
    }

    @Test
    fun `legacy v6 and v7 binary blobs deserialize safely and upgrade cleanly to v8`() {
        val v6Blob = buildLegacyBlob(
            version = 6,
            selectionMode = BalancerBean.SELECTION_PRIORITY,
            balanceEnabled = true,
            balanceMode = BalancerBean.BALANCE_PER_CONNECTION,
            balanceIntervalSec = 55,
            countryFilter = "JP",
            interval = 420,
            tolerance = 15,
            interruptOnSwitch = true,
            learnFromConnections = false,
            frontProxy = 33L,
            landingProxy = 44L,
        )
        val fromV6 = KryoConverters.deserialize(BalancerBean(), v6Blob)
        assertEquals(BalancerBean.SELECTION_PRIORITY, fromV6.selectionMode)
        assertTrue(fromV6.balanceEnabled)
        assertEquals(BalancerBean.BALANCE_PER_CONNECTION, fromV6.balanceMode)
        assertEquals(55, fromV6.balanceIntervalSec)
        assertEquals("JP", fromV6.countryFilter)
        assertEquals(420, fromV6.interval)
        assertEquals(15, fromV6.tolerance)
        assertTrue(fromV6.interruptOnSwitch)
        assertFalse(fromV6.learnFromConnections)
        assertEquals(33L, fromV6.frontProxy)
        assertEquals(44L, fromV6.landingProxy)
        assertTrue(fromV6.tiers.isEmpty())

        val v7Blob = buildLegacyBlob(
            version = 7,
            selectionMode = BalancerBean.SELECTION_PRIORITY,
            tiers = listOf(
                BalancerBean.PriorityTier(12L, "IEPL"),
                BalancerBean.PriorityTier(-1L, "Direct"),
            ),
        )
        val fromV7 = KryoConverters.deserialize(BalancerBean(), v7Blob)
        assertEquals(2, fromV7.tiers.size)
        assertEquals(12L, fromV7.tiers[0].groupId)
        assertEquals("IEPL", fromV7.tiers[0].nameFilter)
        assertEquals(-1L, fromV7.tiers[1].groupId)
        assertEquals("Direct", fromV7.tiers[1].nameFilter)

        val reSerialized = fromV7.clone()
        assertEquals(2, reSerialized.tiers.size)
        assertEquals("IEPL", reSerialized.tiers[0].nameFilter)
        assertEquals(15, fromV6.clone().tolerance)
    }
}
