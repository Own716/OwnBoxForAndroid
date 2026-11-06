package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxOutboundParserTest {

    @Test
    fun parseVlessRealityWithAddressAndPortAliases() {
        val json = JSONObject(
            """
            {
              "type": "vless",
              "tag": "HK01 0.20x",
              "address": "hk01.example.com",
              "port": 443,
              "uuid": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
              "flow": "xtls-rprx-vision",
              "tls": {
                "enabled": true,
                "server_name": "gateway.icloud.com",
                "reality": {
                  "enabled": true,
                  "public_key": "abc123realitykey",
                  "short_id": "123456"
                }
              }
            }
            """.trimIndent()
        )

        val bean = parseSingBoxOutbound(json)
        assertNotNull(bean)
        assertTrue(bean is VMessBean)
        val vless = bean as VMessBean
        assertEquals("HK01 0.20x", vless.name)
        assertEquals("hk01.example.com", vless.serverAddress)
        assertEquals(443, vless.serverPort)
        assertEquals("a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d", vless.uuid)
        assertEquals("xtls-rprx-vision", vless.encryption)
        assertEquals("reality", vless.security)
        assertEquals("abc123realitykey", vless.realityPubKey)
        assertEquals("123456", vless.realityShortId)
        assertEquals("gateway.icloud.com", vless.sni)
    }

    @Test
    fun parseHy2Alias() {
        val json = JSONObject(
            """
            {
              "type": "hy2",
              "tag": "SG01 0.20x",
              "server": "sg01.example.com",
              "server_port": 8443,
              "password": "secretpassword"
            }
            """.trimIndent()
        )

        val bean = parseSingBoxOutbound(json)
        assertNotNull(bean)
        assertTrue(bean is HysteriaBean)
        val hy = bean as HysteriaBean
        assertEquals(2, hy.protocolVersion)
        assertEquals("sg01.example.com", hy.serverAddress)
        assertEquals(8443, hy.serverPort)
        assertEquals("secretpassword", hy.authPayload)
    }

    @Test
    fun parseShadowsocksAlias() {
        val json = JSONObject(
            """
            {
              "type": "ss",
              "tag": "JP01 0.20x",
              "server_address": "jp01.example.com",
              "port": 8388,
              "method": "2022-blake3-aes-128-gcm",
              "password": "sspassword"
            }
            """.trimIndent()
        )

        val bean = parseSingBoxOutbound(json)
        assertNotNull(bean)
        assertTrue(bean is ShadowsocksBean)
        val ss = bean as ShadowsocksBean
        assertEquals("jp01.example.com", ss.serverAddress)
        assertEquals(8388, ss.serverPort)
        assertEquals("2022-blake3-aes-128-gcm", ss.method)
        assertEquals("sspassword", ss.password)
    }

    @Test
    fun sanitizeSingBoxConfigDegradesGvisorAndMixed() {
        val raw = """
            {
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "stack": "gvisor",
                  "auto_route": true
                },
                {
                  "type": "tun",
                  "tag": "tun-mixed",
                  "stack": "mixed"
                }
              ]
            }
        """.trimIndent()

        val sanitized = sanitizeSingBoxConfig(raw)
        assertTrue(sanitized.contains(""""stack": "go""""))
        assertTrue(!sanitized.contains(""""stack": "gvisor""""))
        assertTrue(!sanitized.contains(""""stack": "mixed""""))
    }

    @Test
    fun parsesSnellOutboundCorrectly() {
        val json = JSONObject("""
            {
              "type": "snell",
              "tag": "Snell-Node",
              "server": "snell.example.com",
              "server_port": 1234,
              "psk": "secretpsk",
              "version": 4,
              "obfs": {
                "type": "tls",
                "host": "snell.tls.host"
              }
            }
        """.trimIndent())
        val bean = parseSingBoxOutbound(json)
        assertNotNull(bean)
        assertTrue(bean is io.nekohasekai.sagernet.fmt.snell.SnellBean)
        val snell = bean as io.nekohasekai.sagernet.fmt.snell.SnellBean
        assertEquals("snell.example.com", snell.serverAddress)
        assertEquals(1234, snell.serverPort)
        assertEquals("secretpsk", snell.psk)
        assertEquals(4, snell.version)
        assertEquals("tls", snell.obfsMode)
        assertEquals("snell.tls.host", snell.obfsHost)
    }

    @Test
    fun parsesJuicityOutboundCorrectly() {
        val json = JSONObject("""
            {
              "type": "juicity",
              "tag": "Juicity-Node",
              "server": "juicity.example.com",
              "server_port": 443,
              "uuid": "11111111-2222-3333-4444-555555555555",
              "password": "juicitypwd",
              "tls": {
                "enabled": true,
                "server_name": "juicity.sni.com",
                "insecure": true
              }
            }
        """.trimIndent())
        val bean = parseSingBoxOutbound(json)
        assertNotNull(bean)
        assertTrue(bean is io.nekohasekai.sagernet.fmt.juicity.JuicityBean)
        val j = bean as io.nekohasekai.sagernet.fmt.juicity.JuicityBean
        assertEquals("juicity.example.com", j.serverAddress)
        assertEquals(443, j.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", j.uuid)
        assertEquals("juicitypwd", j.password)
        assertEquals("juicity.sni.com", j.sni)
        assertEquals(true, j.allowInsecure)
    }

    @Test
    fun parsesShadowsocksROutboundCorrectly() {
        val json = JSONObject("""
            {
              "type": "ssr",
              "tag": "SSR-Node",
              "server": "ssr.example.com",
              "server_port": 8388,
              "password": "ssrpwd",
              "method": "aes-256-cfb",
              "protocol": "auth_aes128_md5",
              "obfs": "tls1.2_ticket_auth"
            }
        """.trimIndent())
        val bean = parseSingBoxOutbound(json)
        assertNotNull(bean)
        assertTrue(bean is io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean)
        val ssr = bean as io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
        assertEquals("ssr.example.com", ssr.serverAddress)
        assertEquals(8388, ssr.serverPort)
        assertEquals("aes-256-cfb", ssr.method)
        assertEquals("auth_aes128_md5", ssr.protocol)
        assertEquals("tls1.2_ticket_auth", ssr.obfs)
    }

    @Test
    fun parsesNaiveOutboundCorrectly() {
        val json = JSONObject("""
            {
              "type": "naive",
              "tag": "Naive-Node",
              "server": "naive.example.com",
              "server_port": 443,
              "username": "user",
              "password": "pwd",
              "tls": {
                "server_name": "naive.sni.com"
              }
            }
        """.trimIndent())
        val bean = parseSingBoxOutbound(json)
        assertNotNull(bean)
        assertTrue(bean is io.nekohasekai.sagernet.fmt.naive.NaiveBean)
        val naive = bean as io.nekohasekai.sagernet.fmt.naive.NaiveBean
        assertEquals("naive.example.com", naive.serverAddress)
        assertEquals("user", naive.username)
        assertEquals("naive.sni.com", naive.sni)
    }

    @Test
    fun parsesSSHOutboundCorrectly() {
        val json = JSONObject("""
            {
              "type": "ssh",
              "tag": "SSH-Node",
              "server": "ssh.example.com",
              "server_port": 22,
              "user": "root",
              "password": "rootpassword"
            }
        """.trimIndent())
        val bean = parseSingBoxOutbound(json)
        assertNotNull(bean)
        assertTrue(bean is io.nekohasekai.sagernet.fmt.ssh.SSHBean)
        val ssh = bean as io.nekohasekai.sagernet.fmt.ssh.SSHBean
        assertEquals("ssh.example.com", ssh.serverAddress)
        assertEquals(22, ssh.serverPort)
        assertEquals("root", ssh.username)
        assertEquals(io.nekohasekai.sagernet.fmt.ssh.SSHBean.AUTH_TYPE_PASSWORD, ssh.authType)
    }

    @Test
    fun protocolTfoSafeguardRules() {
        fun checkTfo(bean: AbstractBean): Boolean {
            return when (bean) {
                is io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean,
                is io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean,
                is io.nekohasekai.sagernet.fmt.socks.SOCKSBean -> true
                is moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean -> false
                is io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean ->
                    bean.type != "quic" && bean.type != "splithttp" && bean.type != "xhttp"
                else -> false
            }
        }

        // TFO must be false for non-supported protocols
        assertFalse(checkTfo(moe.matsuri.nb4a.proxy.anytls.AnyTLSBean()))
        assertFalse(checkTfo(io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean()))
        assertFalse(checkTfo(io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean()))
        assertFalse(checkTfo(io.nekohasekai.sagernet.fmt.tuic.TuicBean()))
        assertFalse(checkTfo(io.nekohasekai.sagernet.fmt.juicity.JuicityBean()))
        assertFalse(checkTfo(io.nekohasekai.sagernet.fmt.snell.SnellBean()))
        assertFalse(checkTfo(io.nekohasekai.sagernet.fmt.ssh.SSHBean()))
        assertFalse(checkTfo(moe.matsuri.nb4a.proxy.config.ConfigBean()))
        assertFalse(checkTfo(moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean()))

        // TFO must be true for supported standard TCP protocols
        assertTrue(checkTfo(io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean()))
        assertTrue(checkTfo(io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean()))
        assertTrue(checkTfo(io.nekohasekai.sagernet.fmt.trojan.TrojanBean()))
        assertTrue(checkTfo(io.nekohasekai.sagernet.fmt.v2ray.VMessBean().apply { type = "tcp" }))
    }
}
