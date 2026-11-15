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
        assertEquals(300, bean.runTopN)
        assertEquals(1000, bean.rankTopN)
        assertEquals(1440, bean.resultTrustMinutes)
        assertFalse(bean.skipFailedWithinTrust)
        assertEquals(3, bean.standbySize)
        assertEquals(8, bean.intensiveCheckCount)
        assertEquals(300, bean.interval)
        assertEquals(600, bean.fullScanIntervalSec)
        assertEquals(15, bean.watchIntervalSec)
        assertEquals(10, bean.sampleCount)
        assertEquals(30, bean.tolerance)
        assertEquals(0, bean.maxRttMs)
        assertEquals(2, bean.dialRetries)
        assertFalse(bean.interruptOnSwitch)
        assertEquals(3, bean.failbackChecks)
        assertEquals(60, bean.failbackDelaySec)
        assertFalse(bean.interruptOnFailback)
        assertTrue(bean.learnFromConnections)
        assertEquals(60, bean.siteStickyMinutes)
        assertEquals("", bean.connectivityUrl)
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
    fun `v4 and v5 distribution strategies migrate accurately preserving all legacy fields`() {
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
        assertTrue(v4RoundRobin.useFrontProxy)

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
        assertEquals(60, v5Consistent.siteStickyMinutes)
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
    fun `v6 round trip preserves all fields and formats displayAddress cleanly`() {
        val original = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_MIXED
            targetGroupIds = mutableListOf(1L, 2L)
            proxies = mutableListOf(10L, 20L, 30L)
            selectionMode = BalancerBean.SELECTION_PRIORITY
            balanceEnabled = true
            balanceMode = BalancerBean.BALANCE_PER_CONNECTION
            balanceIntervalSec = 45
            countryFilter = "SG, JP"
            runTopN = 50
            rankTopN = 200
            resultTrustMinutes = 720
            skipFailedWithinTrust = true
            standbySize = 4
            intensiveCheckCount = 12
            interval = 240
            fullScanIntervalSec = 900
            watchIntervalSec = 20
            sampleCount = 15
            tolerance = 40
            maxRttMs = 500
            dialRetries = 3
            interruptOnSwitch = true
            failbackChecks = 4
            failbackDelaySec = 90
            interruptOnFailback = true
            learnFromConnections = false
            siteStickyMinutes = 120
            connectivityUrl = "https://connectivity.example.com"
            frontProxy = 8L
            landingProxy = 9L
        }

        val cloned = original.clone()
        assertEquals(BalancerBean.SELECTION_PRIORITY, cloned.selectionMode)
        assertTrue(cloned.balanceEnabled)
        assertEquals(BalancerBean.BALANCE_PER_CONNECTION, cloned.balanceMode)
        assertEquals(45, cloned.balanceIntervalSec)
        assertEquals("SG, JP", cloned.countryFilter)
        assertEquals(50, cloned.runTopN)
        assertEquals(200, cloned.rankTopN)
        assertEquals(720, cloned.resultTrustMinutes)
        assertTrue(cloned.skipFailedWithinTrust)
        assertEquals(4, cloned.standbySize)
        assertEquals(12, cloned.intensiveCheckCount)
        assertEquals(240, cloned.interval)
        assertEquals(900, cloned.fullScanIntervalSec)
        assertEquals(20, cloned.watchIntervalSec)
        assertEquals(15, cloned.sampleCount)
        assertEquals(40, cloned.tolerance)
        assertEquals(500, cloned.maxRttMs)
        assertEquals(3, cloned.dialRetries)
        assertTrue(cloned.interruptOnSwitch)
        assertEquals(4, cloned.failbackChecks)
        assertEquals(90, cloned.failbackDelaySec)
        assertTrue(cloned.interruptOnFailback)
        assertFalse(cloned.learnFromConnections)
        assertEquals(120, cloned.siteStickyMinutes)
        assertEquals("https://connectivity.example.com", cloned.connectivityUrl)
        assertEquals(8L, cloned.frontProxy)
        assertEquals(9L, cloned.landingProxy)

        val summary = cloned.displayAddress()
        assertTrue("Expected priority label in $summary", summary.contains("按优先级"))
        assertTrue("Expected distribution label in $summary", summary.contains("流量分配"))
    }
}
