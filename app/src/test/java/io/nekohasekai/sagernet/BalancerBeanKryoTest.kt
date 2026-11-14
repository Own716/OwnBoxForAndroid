package io.nekohasekai.sagernet

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalancerBeanKryoTest {

    @Test
    fun v6SerializeAndDeserializeRoundTrip() {
        val original = BalancerBean().apply {
            name = "(日本🇯🇵) 策略组"
            balancerType = BalancerBean.TYPE_GROUP
            targetGroupId = 10L
            targetGroupIds = arrayListOf(10L, 12L)
            proxies = arrayListOf(101L, 102L)
            strategy = BalancerBean.STRATEGY_AUTO_SELECTOR
            testUrl = "https://cp.cloudflare.com/"
            interval = 240
            tolerance = 50
            toleranceUnit = "ms"
            useFrontProxy = false
            useLandingProxy = false
            nameInclude = "jp|tokyo"
            nameExclude = "expire"
            frontProxy = 11L
            landingProxy = 22L
            countryFilter = "JP,!RU"
            excludeUnavailable = false
            poolCap = 500
            buildLimit = 120
            resultValidityMins = 720
            connectivityUrl = "http://cp.cloudflare.com/"
            benchIntervalSec = 900
            watchIntervalSec = 20
            activeSize = 6
            sampling = 8
            maxRttMs = 450
            expected = 4
            dialRetries = 3
            interruptOnSwitch = true
            balance = true
            balanceMode = "connection"
            balanceIntervalSec = 45
            pinnedId = 101L
            pool = arrayListOf(101L, 102L, 103L)
            poolRankedAt = 1_700_000_000L
            lastBuilt = arrayListOf(101L, 102L)
            lastBuiltAt = 1_700_000_123L
            historyJson = """[{"t":1}]"""
        }

        val out = ByteBufferOutput(1024, -1)
        original.serialize(out)
        val restored = BalancerBean()
        restored.deserialize(ByteBufferInput(out.toBytes()))

        assertEquals(BalancerBean.STRATEGY_AUTO_SELECTOR, restored.strategy)
        assertEquals(listOf(10L, 12L), restored.targetGroupIds)
        assertEquals(listOf(101L, 102L), restored.proxies)
        assertEquals("JP,!RU", restored.countryFilter)
        assertFalse(restored.excludeUnavailable)
        assertEquals(500, restored.poolCap)
        assertEquals(120, restored.buildLimit)
        assertEquals(720, restored.resultValidityMins)
        assertEquals("http://cp.cloudflare.com/", restored.connectivityUrl)
        assertEquals(900, restored.benchIntervalSec)
        assertEquals(20, restored.watchIntervalSec)
        assertEquals(6, restored.activeSize)
        assertEquals(8, restored.sampling)
        assertEquals(450, restored.maxRttMs)
        assertEquals(4, restored.expected)
        assertEquals(3, restored.dialRetries)
        assertTrue(restored.interruptOnSwitch)
        assertTrue(restored.balance)
        assertEquals("connection", restored.balanceMode)
        assertEquals(45, restored.balanceIntervalSec)
        assertEquals(101L, restored.pinnedId)
        assertEquals(listOf(101L, 102L, 103L), restored.pool)
        assertEquals(1_700_000_000L, restored.poolRankedAt)
        assertEquals(listOf(101L, 102L), restored.lastBuilt)
        assertEquals(1_700_000_123L, restored.lastBuiltAt)
        assertEquals("""[{"t":1}]""", restored.historyJson)
    }

    @Test
    fun legacyV5PayloadRestoresSafelyWithV6Defaults() {
        val out = ByteBufferOutput(512, -1)
        out.writeInt(5)
        out.writeInt(BalancerBean.TYPE_GROUP)
        out.writeLong(7L)
        out.writeString(BalancerBean.STRATEGY_LEAST_PING)
        out.writeString("")
        out.writeInt(300)
        out.writeInt(0) // proxies.size()
        out.writeInt(1) // targetGroupIds.size()
        out.writeLong(7L)
        out.writeInt(300) // tolerance
        out.writeString("ms")
        out.writeBoolean(false)
        out.writeBoolean(false)
        out.writeString("")
        out.writeString("")
        out.writeLong(-1L)
        out.writeLong(-1L)

        val bean = BalancerBean()
        bean.deserialize(ByteBufferInput(out.toBytes()))

        assertEquals(BalancerBean.STRATEGY_LEAST_PING, bean.strategy)
        assertEquals(listOf(7L), bean.targetGroupIds)
        assertEquals("", bean.countryFilter)
        assertTrue(bean.excludeUnavailable)
        assertEquals(1000, bean.poolCap)
        assertEquals(300, bean.buildLimit)
        assertEquals(1440, bean.resultValidityMins)
        assertEquals(-1L, bean.pinnedId)
        assertFalse(bean.interruptOnSwitch)
        assertFalse(bean.balance)
        assertEquals("rotate", bean.balanceMode)
        assertEquals(30, bean.balanceIntervalSec)
    }
}
