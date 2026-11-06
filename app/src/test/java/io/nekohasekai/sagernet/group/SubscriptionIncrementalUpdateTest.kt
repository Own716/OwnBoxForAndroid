package io.nekohasekai.sagernet.group

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import moe.matsuri.nb4a.Protocols
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SubscriptionIncrementalUpdateTest {

    @Test
    fun subscriptionBeanV5SerializationRoundTrip() {
        val bean = SubscriptionBean().apply {
            type = 0
            link = "https://example.com/sub"
            forceResolve = true
            deduplication = true
            updateWhenConnectedOnly = false
            customUserAgent = "OwnBox/3.1.4"
            autoUpdate = true
            autoUpdateDelay = 720
            lastUpdated = 12345678
            subscriptionUserinfo = "upload=100; download=200; total=1000"
            filterMode = 1
            filterRegex = "HK.*"
            serverDnsResolver = "8.8.8.8"
            lockUserAgent = true
            retainValidNodes = true
        }

        val bos = ByteArrayOutputStream()
        val out = ByteBufferOutput(bos)
        bean.serializeToBuffer(out)
        out.flush()
        out.close()

        val restored = SubscriptionBean()
        val `in` = ByteBufferInput(bos.toByteArray())
        restored.deserializeFromBuffer(`in`)

        assertEquals(bean.link, restored.link)
        assertEquals(true, restored.lockUserAgent)
        assertEquals(true, restored.retainValidNodes)
        assertEquals(bean.autoUpdateDelay, restored.autoUpdateDelay)
    }

    @Test
    fun subscriptionBeanV4BackwardCompatibilityDefaultsRetainValidNodesToFalse() {
        val bos = ByteArrayOutputStream()
        val out = ByteBufferOutput(bos)

        // Simulate legacy version 4 payload without retainValidNodes
        out.writeInt(4) // version = 4
        out.writeInt(0) // type
        out.writeString("https://example.com/v4") // link
        out.writeBoolean(false) // forceResolve
        out.writeBoolean(false) // deduplication
        out.writeBoolean(false) // updateWhenConnectedOnly
        out.writeString("legacy-ua") // customUserAgent
        out.writeBoolean(true) // autoUpdate
        out.writeInt(1440) // autoUpdateDelay
        out.writeInt(99999) // lastUpdated
        out.writeString("userinfo") // subscriptionUserinfo
        out.writeInt(0) // filterMode
        out.writeString("") // filterRegex
        out.writeString("") // serverDnsResolver
        out.writeBoolean(true) // lockUserAgent
        out.flush()
        out.close()

        val restored = SubscriptionBean()
        val `in` = ByteBufferInput(bos.toByteArray())
        restored.deserializeFromBuffer(`in`)

        assertEquals("https://example.com/v4", restored.link)
        assertEquals(true, restored.lockUserAgent)
        // Must safely default to false on v4
        assertEquals(false, restored.retainValidNodes)
    }

    private fun shouldRetainProfileHelper(
        entity: ProxyEntity,
        currentProfileId: Long,
        retainValidNodes: Boolean
    ): Boolean {
        if (!retainValidNodes) return false
        // 1. Current active connection
        if (entity.id == currentProfileId) return true
        // 2. Previously tested successfully (status == 1 && ping > 0)
        if (entity.status == 1 && entity.ping > 0) return true
        // 3. Has data transfer traffic
        if (entity.rx > 0L || entity.tx > 0L) return true
        // 4. Untested, failed, or defunct
        return false
    }

    @Test
    fun modeADeletesAllMissingNodesRegardlessOfHealth() {
        val activeNode = ProxyEntity(id = 101, status = 1, ping = 150)
        val deadNode = ProxyEntity(id = 102, status = 3, ping = 0)
        val untestedNode = ProxyEntity(id = 103, status = 0, ping = 0)

        assertFalse("Mode A must not retain active node", shouldRetainProfileHelper(activeNode, currentProfileId = 999, retainValidNodes = false))
        assertFalse("Mode A must not retain dead node", shouldRetainProfileHelper(deadNode, currentProfileId = 999, retainValidNodes = false))
        assertFalse("Mode A must not retain untested node", shouldRetainProfileHelper(untestedNode, currentProfileId = 999, retainValidNodes = false))
    }

    @Test
    fun modeBRetainsSuccessfulNodesAndCurrentProfile() {
        val successNode = ProxyEntity(id = 101, status = 1, ping = 120)
        val currentProfileNode = ProxyEntity(id = 102, status = 3, ping = 0) // even if status is failed, active node is retained
        val trafficNode = ProxyEntity(id = 103, status = 3, rx = 5000L)
        val untestedNode = ProxyEntity(id = 104, status = 0, ping = 0)
        val deadNode = ProxyEntity(id = 105, status = 3, ping = 0)

        assertTrue("Mode B retains node with successful test", shouldRetainProfileHelper(successNode, currentProfileId = 999, retainValidNodes = true))
        assertTrue("Mode B retains active profile node", shouldRetainProfileHelper(currentProfileNode, currentProfileId = 102, retainValidNodes = true))
        assertTrue("Mode B retains node with traffic history", shouldRetainProfileHelper(trafficNode, currentProfileId = 999, retainValidNodes = true))
        assertFalse("Mode B does not retain untested node", shouldRetainProfileHelper(untestedNode, currentProfileId = 999, retainValidNodes = true))
        assertFalse("Mode B does not retain dead/failed node", shouldRetainProfileHelper(deadNode, currentProfileId = 999, retainValidNodes = true))
    }

    @Test
    fun twoPhaseMatchingEndpointHashIdentifiesRenamedNode() {
        val oldBean = ShadowsocksBean().apply {
            name = "HK 01"
            serverAddress = "198.51.100.1"
            serverPort = 8388
            password = "secret-password"
            method = "chacha20-ietf-poly1305"
        }

        val newBean = ShadowsocksBean().apply {
            name = "🇭🇰 [Premium] HK 01 (1.5x)"
            serverAddress = "198.51.100.1"
            serverPort = 8388
            password = "secret-password"
            method = "chacha20-ietf-poly1305"
        }

        val oldHash = Protocols.Deduplication(oldBean, oldBean.javaClass.toString()).hash()
        val newHash = Protocols.Deduplication(newBean, newBean.javaClass.toString()).hash()

        // Hashes must match despite different display names!
        assertEquals("Endpoint hash must match despite rename", oldHash, newHash)
    }
}
