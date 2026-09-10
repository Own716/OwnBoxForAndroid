package io.nekohasekai.sagernet.fmt.hysteria

import moe.matsuri.nb4a.SingBoxOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HysteriaFmtTest {

    @Test
    fun hopPortsToSingboxListFormatsPortRangesWithColons() {
        // Range with dash (e.g. from subscription)
        assertEquals(listOf("50000:55000"), hopPortsToSingboxList("50000-55000"))

        // Range with colon
        assertEquals(listOf("50000:55000"), hopPortsToSingboxList("50000:55000"))

        // Single port in hopping string
        assertEquals(listOf("443:443"), hopPortsToSingboxList("443"))

        // Multiple comma-separated ranges and ports
        assertEquals(
            listOf("50000:55000", "8000:9000", "443:443"),
            hopPortsToSingboxList("50000-55000, 8000:9000, 443")
        )
    }

    @Test
    fun getFirstPortParsesVariousFormats() {
        assertEquals(50000, getFirstPort("50000-55000"))
        assertEquals(50000, getFirstPort("50000:55000"))
        assertEquals(443, getFirstPort("443"))
        assertEquals(8080, getFirstPort("8080,9000-9100"))
        assertEquals(443, getFirstPort("invalid"))
    }

    @Test
    fun buildSingBoxOutboundHysteria2UsesColonSeparatedServerPorts() {
        val bean = HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = "203.10.99.59"
            serverPorts = "50000-55000"
            authPayload = "secret-token"
            hopInterval = 10
            sni = "www.bing.com"
            allowInsecure = true
        }

        val option = buildSingBoxOutboundHysteriaBean(bean)
        val hy2 = option as SingBoxOptions.Outbound_Hysteria2Options

        assertEquals("hysteria2", hy2.type)
        assertEquals("203.10.99.59", hy2.server)
        assertEquals(listOf("50000:55000"), hy2.server_ports)
        assertNull(hy2.server_port)
        assertEquals("10s", hy2.hop_interval)
        assertEquals("secret-token", hy2.password)
        assertEquals("www.bing.com", hy2.tls.server_name)
        assertEquals(true, hy2.tls.insecure)
        assertEquals(listOf("h3"), hy2.tls.alpn)
    }

    @Test
    fun buildSingBoxOutboundHysteria2UsesSinglePortWhenNumeric() {
        val bean = HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = "203.10.99.59"
            serverPorts = "8443"
            authPayload = "secret-token"
        }

        val option = buildSingBoxOutboundHysteriaBean(bean)
        val hy2 = option as SingBoxOptions.Outbound_Hysteria2Options

        assertEquals("hysteria2", hy2.type)
        assertEquals(8443, hy2.server_port)
        assertNull(hy2.server_ports)
    }
}
