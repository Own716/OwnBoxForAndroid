package moe.matsuri.nb4a.proxy.anytls

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.parseSingBoxOutbound
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnyTLSFmtTest {

    private fun baseBean(): AnyTLSBean = AnyTLSBean().apply {
        serverAddress = "example.com"
        serverPort = 443
        password = "secret-password"
        sni = "example.com"
        allowInsecure = false
        applyDefaultValues()
    }

    @Test
    fun buildSingBoxOutbound_mapsInlinePemClientCertAndKey() {
        val certPem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
        val keyPem = "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----"
        val bean = baseBean().apply {
            clientCertificate = certPem
            clientKey = keyPem
        }

        val out = buildSingBoxOutboundAnyTLSBean(bean, globalAllowInsecure = false)
        assertEquals(certPem, out.tls.client_certificate)
        assertEquals(keyPem, out.tls.client_key)
        assertNull(out.tls.client_certificate_path)
        assertNull(out.tls.client_key_path)
    }

    @Test
    fun buildSingBoxOutbound_mapsFilePathsAndSkipsEmptyMtls() {
        val beanWithPaths = baseBean().apply {
            clientCertificate = "/data/local/tmp/client.crt"
            clientKey = "/data/local/tmp/client.key"
        }
        val outWithPaths = buildSingBoxOutboundAnyTLSBean(beanWithPaths, globalAllowInsecure = false)
        assertEquals("/data/local/tmp/client.crt", outWithPaths.tls.client_certificate_path)
        assertEquals("/data/local/tmp/client.key", outWithPaths.tls.client_key_path)
        assertNull(outWithPaths.tls.client_certificate)
        assertNull(outWithPaths.tls.client_key)

        val plainBean = baseBean()
        val plainOut = buildSingBoxOutboundAnyTLSBean(plainBean, globalAllowInsecure = false)
        assertNull(plainOut.tls.client_certificate)
        assertNull(plainOut.tls.client_certificate_path)
        assertNull(plainOut.tls.client_key)
        assertNull(plainOut.tls.client_key_path)

        assertTrue(isCertificateFilePath("/etc/ssl/client.pem"))
        assertFalse(isCertificateFilePath("-----BEGIN CERTIFICATE-----\nABC"))
        assertFalse(isCertificateFilePath("   "))
    }

    @Test
    fun kryoSerialization_supportsV2RoundtripAndLegacyV1Compat() {
        val bean = baseBean().apply {
            name = "mTLS Node"
            realityPubKey = "pub"
            realityShortId = "abcd"
            clientCertificate = "/data/local/tmp/c.pem"
            clientKey = "/data/local/tmp/k.pem"
        }
        val roundtrip = bean.clone()
        assertEquals("/data/local/tmp/c.pem", roundtrip.clientCertificate)
        assertEquals("/data/local/tmp/k.pem", roundtrip.clientKey)
        assertEquals("pub", roundtrip.realityPubKey)
        assertEquals("abcd", roundtrip.realityShortId)

        // Simulate legacy v1 serialized payload without trailing clientCertificate/clientKey fields
        val v1Bean = AnyTLSBean().apply {
            serverAddress = "legacy.example.com"
            serverPort = 8443
            password = "legacy-password"
            sni = "legacy.example.com"
            alpn = "h2"
            certificates = ""
            utlsFingerprint = "chrome"
            allowInsecure = false
            echConfig = ""
            realityPubKey = "legacyPub"
            realityShortId = "01"
            applyDefaultValues()
        }
        val v2Buf = ByteBufferOutput(1024).also { v1Bean.serialize(it) }.toBytes()
        assertEquals(2, ByteBufferInput(v2Buf).readInt())
        // Each empty String ("") encodes as 1 byte (0x80) in Kryo Output
        val v1Buf = v2Buf.copyOfRange(0, v2Buf.size - 2)
        ByteBufferOutput(8).apply { writeInt(1) }.toBytes().copyInto(v1Buf, destinationOffset = 0)

        val decoded = AnyTLSBean().apply { deserialize(ByteBufferInput(v1Buf)) }
        assertEquals("legacy.example.com", decoded.serverAddress)
        assertEquals("legacyPub", decoded.realityPubKey)
        assertEquals("01", decoded.realityShortId)
        assertEquals("", decoded.clientCertificate)
        assertEquals("", decoded.clientKey)
    }

    @Test
    fun parseSingBoxOutbound_parsesInlineAndPathMtlsForAnyTls() {
        val inlineJson = JSONObject(
            """
            {
              "type": "anytls",
              "tag": "anytls-inline",
              "server": "a.example.com",
              "server_port": 443,
              "password": "pw",
              "tls": {
                "enabled": true,
                "server_name": "a.example.com",
                "client_certificate": ["-----BEGIN CERTIFICATE-----\nCERT"],
                "client_key": ["-----BEGIN PRIVATE KEY-----\nKEY"]
              }
            }
            """.trimIndent()
        )
        val parsedInline = parseSingBoxOutbound(inlineJson) as AnyTLSBean
        assertEquals("-----BEGIN CERTIFICATE-----\nCERT", parsedInline.clientCertificate)
        assertEquals("-----BEGIN PRIVATE KEY-----\nKEY", parsedInline.clientKey)

        val pathJson = JSONObject(
            """
            {
              "type": "anytls",
              "tag": "anytls-path",
              "server": "b.example.com",
              "server_port": 443,
              "password": "pw",
              "tls": {
                "enabled": true,
                "server_name": "b.example.com",
                "client_certificate_path": "/data/local/tmp/client.pem",
                "client_key_path": "/data/local/tmp/client.key"
              }
            }
            """.trimIndent()
        )
        val parsedPath = parseSingBoxOutbound(pathJson) as AnyTLSBean
        assertEquals("/data/local/tmp/client.pem", parsedPath.clientCertificate)
        assertEquals("/data/local/tmp/client.key", parsedPath.clientKey)
    }
}
