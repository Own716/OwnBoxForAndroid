package io.nekohasekai.sagernet.fmt.snell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnellFmtTest {

    @Test
    fun parseStandardSnellV4Url() {
        val url = "snell://my-secret-psk@example.com:12345?obfs=http&host=bing.com#TestNode"
        val bean = parseSnell(url)

        assertEquals("TestNode", bean.name)
        assertEquals("example.com", bean.serverAddress)
        assertEquals(12345, bean.serverPort)
        assertEquals("my-secret-psk", bean.psk)
        assertEquals("http", bean.obfsMode)
        assertEquals("bing.com", bean.obfsHost)

        val outbound = buildSingBoxOutboundSnellBean(bean)
        assertEquals("snell", outbound.type)
        assertEquals("example.com", outbound.server)
        assertEquals(12345, outbound.server_port)
        assertEquals("my-secret-psk", outbound.psk)
        assertEquals("http", outbound.obfs_mode)
        assertEquals("bing.com", outbound.obfs_host)
    }

    @Test
    fun parseSnellV6UrlWithModeAndReuse() {
        val url = "snell://another-secret@1.2.3.4:443?version=6&mode=unshaped&reuse=1#SnellV6"
        val bean = parseSnell(url)

        assertEquals("SnellV6", bean.name)
        assertEquals("1.2.3.4", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("another-secret", bean.psk)
        assertEquals(6, bean.version)
        assertEquals("unshaped", bean.mode)
        assertTrue(bean.reuse ?: false)

        val outbound = buildSingBoxOutboundSnellBean(bean)
        assertEquals("snell", outbound.type)
        assertEquals(6, outbound.version)
        assertEquals("unshaped", outbound.mode)
        assertTrue(outbound.reuse ?: false)
    }

    @Test
    fun parseClashSnellMapping() {
        val map = mapOf<String, Any?>(
            "name" to "ClashSnell",
            "server" to "proxy.example.com",
            "port" to 23456,
            "psk" to "clash-psk-123",
            "version" to 4,
            "obfs-opts" to mapOf<String, Any?>(
                "mode" to "http",
                "host" to "bing.com"
            )
        )
        val bean = parseClashSnell(map)

        assertEquals("ClashSnell", bean.name)
        assertEquals("proxy.example.com", bean.serverAddress)
        assertEquals(23456, bean.serverPort)
        assertEquals("clash-psk-123", bean.psk)
        assertEquals(4, bean.version)
        assertEquals("http", bean.obfsMode)
        assertEquals("bing.com", bean.obfsHost)
    }

    @Test
    fun roundTripToUri() {
        val bean = SnellBean().apply {
            name = "RoundTrip"
            serverAddress = "10.0.0.1"
            serverPort = 8443
            psk = "psk-xyz"
            version = 4
            obfsMode = "http"
            obfsHost = "example.org"
        }
        val uri = bean.toUri()
        val restored = parseSnell(uri)

        assertEquals(bean.serverAddress, restored.serverAddress)
        assertEquals(bean.serverPort, restored.serverPort)
        assertEquals(bean.psk, restored.psk)
        assertEquals(bean.obfsMode, restored.obfsMode)
        assertEquals(bean.obfsHost, restored.obfsHost)
    }

    @Test
    fun snellV5FallbackToV4() {
        // 1. Link parsing with version=5
        val url = "snell://secret-pass@5.6.7.8:10086?version=5&obfs=http&host=cloudflare.com#V5Node"
        val bean = parseSnell(url)
        assertEquals(4, bean.version)

        // 2. Outbound building with an existing bean that has version = 5
        val manualV5Bean = SnellBean().apply {
            serverAddress = "5.6.7.8"
            serverPort = 10086
            psk = "secret-pass"
            version = 5
            obfsMode = "http"
            obfsHost = "cloudflare.com"
        }
        val outbound = buildSingBoxOutboundSnellBean(manualV5Bean)
        // Must be mapped to 4 so sing-box never throws unsupported version: 5
        assertEquals(4, outbound.version)
    }
}
