package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.KryoConverters
import moe.matsuri.nb4a.SingBoxOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VLESSRealityFmtTest {

    @Test
    fun parseVlessRealityUrlPreservesPublicKeyAfterKryoRoundTrip() {
        val testUrl = "vless://11111111-2222-3333-4444-555555555555@reality.example.com:443" +
                "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=dest.example.com" +
                "&fp=chrome&pbk=TestPublicKeyBase64String1234567890abcdef=&sid=1a2b3c4d#reality-test"

        val bean = parseV2Ray(testUrl)
        assertTrue(bean.isVLESS)
        assertEquals("reality", bean.security)
        assertEquals("dest.example.com", bean.sni)
        assertEquals("chrome", bean.utlsFingerprint)
        assertEquals("TestPublicKeyBase64String1234567890abcdef=", bean.realityPubKey)
        assertEquals("1a2b3c4d", bean.realityShortId)

        bean.initializeDefaultValues()

        // Test Kryo serialization and deserialization (fixes Issue #76)
        val serializedBytes = KryoConverters.serialize(bean)
        val restored = KryoConverters.deserialize(VMessBean(), serializedBytes)

        assertEquals("reality", restored.security)
        assertEquals("dest.example.com", restored.sni)
        assertEquals("chrome", restored.utlsFingerprint)
        assertEquals("TestPublicKeyBase64String1234567890abcdef=", restored.realityPubKey)
        assertEquals("1a2b3c4d", restored.realityShortId)

        // Test sing-box outbound generation
        val outbound = buildSingBoxOutboundStandardV2RayBean(restored)
        assertTrue(outbound is SingBoxOptions.Outbound_VLESSOptions)
        val vless = outbound as SingBoxOptions.Outbound_VLESSOptions
        assertEquals("xtls-rprx-vision", vless.flow)
        assertNotNull(vless.tls)
        assertNotNull(vless.tls.reality)
        assertTrue(vless.tls.reality.enabled)
        assertEquals("TestPublicKeyBase64String1234567890abcdef=", vless.tls.reality.public_key)
        assertEquals("1a2b3c4d", vless.tls.reality.short_id)
        assertEquals("chrome", vless.tls.utls.fingerprint)
        assertEquals("dest.example.com", vless.tls.server_name)
    }

    @Test
    fun vlessFlowNormalizationFiltersUnsupportedFlows() {
        // none / unsupported flows should not be passed to sing-box outbound
        val urlWithoutFlow = "vless://11111111-2222-3333-4444-555555555555@example.com:443?encryption=none"
        val bean = parseV2Ray(urlWithoutFlow)
        bean.initializeDefaultValues()
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean) as SingBoxOptions.Outbound_VLESSOptions
        assertNull(outbound.flow)
    }
}
