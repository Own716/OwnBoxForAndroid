package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.getIntNya
import io.nekohasekai.sagernet.ktx.getStr
import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.json.JSONArray
import org.json.JSONObject

/**
 * 将 sing-box 配置中的单个 outbound JSON 还原为原生协议 Bean。
 * 用于订阅返回完整 sing-box 配置（含 outbounds）的场景，
 * 避免节点全部退化为 ConfigBean（自定义 JSON）。
 *
 * 返回 null 表示该 outbound 无法还原（不支持的类型或缺少必要字段），
 * 调用方应回退为 ConfigBean。
 */
fun parseSingBoxOutbound(json: JSONObject): AbstractBean? {
    val type = json.getStr("type") ?: return null
    val tag = json.getStr("tag")
    val server = json.getStr("server")
    val port = json.getIntNya("server_port")
    val tls = json.parseSingBoxTLS()

    val bean: AbstractBean = when (type) {
        "shadowsocks" -> ShadowsocksBean().apply {
            method = json.getStr("method")
            password = json.getStr("password")
            val pluginName = json.getStr("plugin")
            val pluginOpts = json.getStr("plugin_opts")
            plugin = when {
                pluginName.isNullOrBlank() -> ""
                pluginOpts.isNullOrBlank() -> pluginName
                else -> "$pluginName;$pluginOpts"
            }
            when (val uot = json.opt("udp_over_tcp")) {
                is Boolean -> sUoT = uot
                is JSONObject -> sUoT = uot.optBoolean("enabled", false)
            }
            applySingBoxMux(json)
        }

        "vmess" -> VMessBean().apply {
            uuid = json.getStr("uuid")
            encryption = json.getStr("security") ?: "auto"
            alterId = json.getIntNya("alter_id") ?: 0
            packetEncoding = parseSingBoxPacketEncoding(json.getStr("packet_encoding"))
            applySingBoxTransport(json)
            applySingBoxTLS(tls)
            applySingBoxMux(json)
        }

        "vless" -> VMessBean().apply {
            alterId = -1 // make it VLESS
            uuid = json.getStr("uuid")
            encryption = json.getStr("flow") ?: ""
            json.getStr("encryption")?.takeIf { it != "none" }?.let {
                vlessEncryption = it
            }
            packetEncoding = parseSingBoxPacketEncoding(json.getStr("packet_encoding"))
            applySingBoxTransport(json)
            applySingBoxTLS(tls)
            applySingBoxMux(json)
        }

        "trojan" -> TrojanBean().apply {
            password = json.getStr("password")
            applySingBoxTransport(json)
            applySingBoxTLS(tls)
            applySingBoxMux(json)
        }

        "hysteria" -> HysteriaBean().apply {
            protocolVersion = 1
            serverPorts = parseSingBoxServerPorts(json, port)
            uploadMbps = json.getIntNya("up_mbps")
            downloadMbps = json.getIntNya("down_mbps")
            obfuscation = json.getStr("obfs") ?: ""
            json.getStr("auth")?.let {
                authPayloadType = HysteriaBean.TYPE_BASE64
                authPayload = it
            }
            json.getStr("auth_str")?.let {
                authPayloadType = HysteriaBean.TYPE_STRING
                authPayload = it
            }
            json.getIntNya("recv_window")?.let { streamReceiveWindow = it }
            json.getIntNya("recv_window_conn")?.let { connectionReceiveWindow = it }
            disableMtuDiscovery = json.optBoolean("disable_mtu_discovery", false)
            parseSingBoxHopInterval(json)?.let { hopInterval = it }
            if (tls != null) {
                sni = tls.sni
                alpn = tls.alpn
                caText = tls.certificates
                allowInsecure = tls.allowInsecure
            }
        }

        "hysteria2" -> HysteriaBean().apply {
            protocolVersion = 2
            serverPorts = parseSingBoxServerPorts(json, port)
            authPayload = json.getStr("password") ?: ""
            uploadMbps = json.getIntNya("up_mbps")
            downloadMbps = json.getIntNya("down_mbps")
            json.optJSONObject("obfs")?.let {
                obfuscation = it.getStr("password") ?: ""
            }
            parseSingBoxHopInterval(json)?.let { hopInterval = it }
            if (tls != null) {
                sni = tls.sni
                caText = tls.certificates
                allowInsecure = tls.allowInsecure
            }
        }

        "tuic" -> TuicBean().apply {
            protocolVersion = 5
            uuid = json.getStr("uuid")
            token = json.getStr("password")
            congestionController = json.getStr("congestion_control")
            udpRelayMode = json.getStr("udp_relay_mode")
            reduceRTT = json.optBoolean("zero_rtt_handshake", false)
            if (tls != null) {
                sni = tls.sni
                alpn = tls.alpn
                caText = tls.certificates
                allowInsecure = tls.allowInsecure
                disableSNI = tls.disableSNI
            }
        }

        "socks" -> SOCKSBean().apply {
            protocol = when (json.getStr("version")) {
                "4" -> SOCKSBean.PROTOCOL_SOCKS4
                "4a" -> SOCKSBean.PROTOCOL_SOCKS4A
                else -> SOCKSBean.PROTOCOL_SOCKS5
            }
            username = json.getStr("username")
            password = json.getStr("password")
        }

        "http" -> HttpBean().apply {
            username = json.getStr("username")
            password = json.getStr("password")
            applySingBoxTLS(tls)
        }

        "wireguard" -> WireGuardBean().apply {
            localAddress = json.optJSONArray("local_address")?.toStringList()?.joinToString("\n")
            privateKey = json.getStr("private_key")
            peerPublicKey = json.getStr("peer_public_key")
            peerPreSharedKey = json.getStr("pre_shared_key")
            mtu = json.getIntNya("mtu")
            reserved = json.optJSONArray("reserved")?.toStringList()?.joinToString("\n")
                ?: json.getStr("reserved")
        }

        "anytls" -> AnyTLSBean().apply {
            password = json.getStr("password")
            if (tls != null) {
                sni = tls.sni
                alpn = tls.alpn
                certificates = tls.certificates
                utlsFingerprint = tls.utlsFingerprint
                realityPubKey = tls.realityPubKey
                realityShortId = tls.realityShortId
                if (tls.enableECH) echConfig = tls.echConfig
                allowInsecure = tls.allowInsecure
            }
        }

        else -> return null
    }

    // 必要字段校验：无服务器地址的 outbound 无法作为节点使用
    if (server.isNullOrBlank()) return null
    bean.serverAddress = server
    if (port != null) bean.serverPort = port
    bean.name = tag

    // 对齐 Clash 解析的 SNI 兜底：TLS 节点未显式配置 SNI 时取 host
    if (bean is StandardV2RayBean) {
        if (bean.isTLS() && bean.sni.isNullOrBlank() && !bean.host.isNullOrBlank() && !bean.host.isIpAddress()) {
            bean.sni = bean.host
        }
    }

    bean.initializeDefaultValues()
    return bean
}

// ---------------------------------------------------------------------------
// TLS
// ---------------------------------------------------------------------------

private class SingBoxTLSFields {
    var sni: String = ""
    var alpn: String = ""
    var allowInsecure: Boolean = false
    var certificates: String = ""
    var utlsFingerprint: String = ""
    var realityPubKey: String = ""
    var realityShortId: String = ""
    var enableECH: Boolean = false
    var echConfig: String = ""
    var echQueryServerName: String = ""
    var disableSNI: Boolean = false
}

private fun JSONObject.parseSingBoxTLS(): SingBoxTLSFields? {
    val tls = optJSONObject("tls") ?: return null
    if (!tls.optBoolean("enabled", true)) return null
    return SingBoxTLSFields().apply {
        sni = tls.getStr("server_name") ?: ""
        allowInsecure = tls.optBoolean("insecure", false)
        alpn = tls.optJSONArray("alpn")?.toStringList()?.joinToString("\n") ?: ""
        certificates = when (val cert = tls.opt("certificate")) {
            is String -> cert
            is JSONArray -> cert.toStringList().joinToString("\n")
            else -> ""
        }
        tls.optJSONObject("utls")?.let {
            if (it.optBoolean("enabled", true)) {
                utlsFingerprint = it.getStr("fingerprint") ?: ""
            }
        }
        tls.optJSONObject("reality")?.let {
            if (it.optBoolean("enabled", true)) {
                realityPubKey = it.getStr("public_key") ?: ""
                realityShortId = it.getStr("short_id") ?: ""
            }
        }
        tls.optJSONObject("ech")?.let {
            if (it.optBoolean("enabled", true)) {
                enableECH = true
                echConfig = when (val config = it.opt("config")) {
                    is String -> config
                    is JSONArray -> config.toStringList().joinToString("\n")
                    else -> ""
                }
                echQueryServerName = it.getStr("query_server_name") ?: ""
            }
        }
        disableSNI = tls.optBoolean("disable_sni", false)
    }
}

private fun StandardV2RayBean.applySingBoxTLS(tls: SingBoxTLSFields?) {
    if (tls == null) return
    security = "tls"
    sni = tls.sni
    alpn = tls.alpn
    allowInsecure = tls.allowInsecure
    certificates = tls.certificates
    utlsFingerprint = tls.utlsFingerprint
    realityPubKey = tls.realityPubKey
    realityShortId = tls.realityShortId
    enableECH = tls.enableECH
    echConfig = tls.echConfig
    echQueryServerName = tls.echQueryServerName
}

// ---------------------------------------------------------------------------
// Transport (V2Ray Transport)
// ---------------------------------------------------------------------------

private fun StandardV2RayBean.applySingBoxTransport(json: JSONObject) {
    val transport = json.optJSONObject("transport") ?: return
    when (transport.getStr("type")) {
        "ws" -> {
            type = "ws"
            path = transport.getStr("path") ?: ""
            transport.optJSONObject("headers")?.let {
                host = it.optHeaderValue("Host") ?: it.optHeaderValue("host") ?: ""
            }
            wsMaxEarlyData = transport.getIntNya("max_early_data") ?: 0
            earlyDataHeaderName = transport.getStr("early_data_header_name") ?: ""
        }

        "http" -> {
            type = "http"
            host = transport.optJSONArray("host")?.toStringList()?.joinToString(",")
                ?: transport.getStr("host") ?: ""
            path = transport.getStr("path") ?: ""
        }

        "quic" -> type = "quic"

        "grpc" -> {
            type = "grpc"
            path = transport.getStr("service_name") ?: ""
        }

        "httpupgrade" -> {
            type = "httpupgrade"
            host = transport.getStr("host") ?: ""
            path = transport.getStr("path") ?: ""
        }
    }
}

// ---------------------------------------------------------------------------
// Mux
// ---------------------------------------------------------------------------

private fun ShadowsocksBean.applySingBoxMux(json: JSONObject) {
    val mux = json.optJSONObject("multiplex") ?: return
    if (!mux.optBoolean("enabled", false)) return
    enableMux = true
    muxPadding = mux.optBoolean("padding", false)
    mux.getIntNya("max_streams")?.let { muxConcurrency = it }
}

private fun StandardV2RayBean.applySingBoxMux(json: JSONObject) {
    val mux = json.optJSONObject("multiplex") ?: return
    if (!mux.optBoolean("enabled", false)) return
    enableMux = true
    muxPadding = mux.optBoolean("padding", false)
    mux.getIntNya("max_streams")?.let { muxConcurrency = it }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun parseSingBoxPacketEncoding(value: String?): Int {
    return when (value) {
        "packetaddr" -> 1
        "xudp" -> 2
        else -> 0
    }
}

/** server_port 或 server_ports -> HysteriaBean.serverPorts 格式（"443" 或 "8080,9000-9100"） */
private fun parseSingBoxServerPorts(json: JSONObject, port: Int?): String? {
    json.optJSONArray("server_ports")?.toStringList()?.let { ports ->
        if (ports.isNotEmpty()) {
            return ports.joinToString(",") { it.replace(":", "-") }
        }
    }
    return port?.toString()
}

/** sing-box 时长字符串（如 "10s"）-> 秒数 */
private fun parseSingBoxHopInterval(json: JSONObject): Int? {
    val raw = json.getStr("hop_interval") ?: return null
    return raw.removeSuffix("s").toIntOrNull()
}

private fun JSONArray.toStringList(): List<String> {
    val list = ArrayList<String>(length())
    for (i in 0 until length()) {
        list.add(opt(i)?.toString() ?: "")
    }
    return list
}

/** sing-box HTTPHeader 的值可能是字符串或字符串数组 */
private fun JSONObject.optHeaderValue(name: String): String? {
    return when (val value = opt(name)) {
        is String -> value
        is JSONArray -> value.toStringList().joinToString("\n")
        else -> null
    }
}
