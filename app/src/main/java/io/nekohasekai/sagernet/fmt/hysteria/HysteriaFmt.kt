package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File


import java.net.URLDecoder

data class ParsedHysteriaUri(
    val auth: String,
    val host: String,
    val ports: String,
    val queryParams: Map<String, String>,
    val fragment: String
)

fun parseHysteriaUriString(rawUrl: String, schemePrefixes: List<String>): ParsedHysteriaUri {
    var working = rawUrl.trim()
    for (prefix in schemePrefixes) {
        if (working.startsWith(prefix, ignoreCase = true)) {
            working = working.substring(prefix.length)
            break
        }
    }

    // 1. Fragment (#remarks)
    var fragment = ""
    if (working.contains("#")) {
        val rawFrag = working.substringAfter("#")
        working = working.substringBefore("#")
        fragment = runCatching { URLDecoder.decode(rawFrag, "UTF-8") }.getOrDefault(rawFrag)
    }

    // 2. Query parameters (?k=v&...)
    val queryParams = mutableMapOf<String, String>()
    if (working.contains("?")) {
        val rawQuery = working.substringAfter("?")
        working = working.substringBefore("?")
        rawQuery.split("&").forEach { pair ->
            if (pair.isNotBlank()) {
                val kv = pair.split("=", limit = 2)
                val key = kv[0].trim()
                val value = if (kv.size > 1) {
                    runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
                } else ""
                if (key.isNotEmpty()) {
                    queryParams[key] = value
                }
            }
        }
    }

    // 3. Clean trailing slashes from authority
    while (working.endsWith("/")) {
        working = working.substring(0, working.length - 1)
    }

    // 4. Extract Auth [auth@]
    var auth = ""
    var hostPort = working
    if (working.contains("@")) {
        val rawAuth = working.substringBefore("@")
        auth = runCatching { URLDecoder.decode(rawAuth, "UTF-8") }.getOrDefault(rawAuth)
        hostPort = working.substringAfter("@")
    }

    // 5. Extract Host and Ports
    var host = ""
    var ports = "443"

    if (hostPort.startsWith("[")) {
        // IPv6 address: e.g. [2001:db8::1]:56000-59000
        val closeBracket = hostPort.indexOf("]")
        if (closeBracket != -1) {
            host = hostPort.substring(1, closeBracket)
            val rest = hostPort.substring(closeBracket + 1)
            if (rest.startsWith(":")) {
                ports = rest.substring(1)
            }
        } else {
            host = hostPort.removePrefix("[").removeSuffix("]")
        }
    } else {
        if (hostPort.contains(":")) {
            host = hostPort.substringBefore(":")
            ports = hostPort.substringAfter(":")
        } else {
            host = hostPort
        }
    }

    return ParsedHysteriaUri(auth, host, ports, queryParams, fragment)
}

// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean {
    val parsed = parseHysteriaUriString(url, listOf("hysteria://"))
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = parsed.host
        serverPorts = parsed.ports
        name = parsed.fragment
        authPayload = parsed.auth.ifBlank { parsed.queryParams["auth"] ?: "" }
        if (authPayload.isNotBlank()) {
            authPayloadType = HysteriaBean.TYPE_STRING
        }

        parsed.queryParams["mport"]?.takeIf { it.isNotBlank() }?.also {
            serverPorts = it
        }
        (parsed.queryParams["peer"] ?: parsed.queryParams["sni"])?.takeIf { it.isNotBlank() }?.also {
            sni = it
        }
        val ins = parsed.queryParams["insecure"] ?: parsed.queryParams["allowInsecure"] ?: parsed.queryParams["allow_insecure"]
        if (ins != null) {
            allowInsecure = ins == "1" || ins.equals("true", ignoreCase = true)
        }
        parsed.queryParams["upmbps"]?.toIntOrNull()?.also {
            uploadMbps = it
        }
        parsed.queryParams["downmbps"]?.toIntOrNull()?.also {
            downloadMbps = it
        }
        parsed.queryParams["alpn"]?.takeIf { it.isNotBlank() && it != "none" }?.also {
            alpn = it
        }
        (parsed.queryParams["obfsParam"] ?: parsed.queryParams["obfs-password"] ?: parsed.queryParams["obfs_password"])?.also {
            obfuscation = it
        }
        when (parsed.queryParams["protocol"]) {
            "faketcp" -> protocol = HysteriaBean.PROTOCOL_FAKETCP
            "wechat-video" -> protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
        }
    }
}

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    val parsed = parseHysteriaUriString(url, listOf("hysteria2://", "hy2://"))
    return HysteriaBean().apply {
        protocolVersion = 2
        serverAddress = parsed.host
        serverPorts = parsed.ports
        authPayload = parsed.auth
        name = parsed.fragment

        parsed.queryParams["mport"]?.takeIf { it.isNotBlank() }?.also {
            serverPorts = it
        }
        (parsed.queryParams["sni"] ?: parsed.queryParams["peer"])?.takeIf { it.isNotBlank() }?.also {
            sni = it
        }
        val ins = parsed.queryParams["insecure"] ?: parsed.queryParams["allowInsecure"] ?: parsed.queryParams["allow_insecure"]
        if (ins != null) {
            allowInsecure = ins == "1" || ins.equals("true", ignoreCase = true)
        }
        (parsed.queryParams["obfs"] ?: parsed.queryParams["obfs-type"] ?: parsed.queryParams["obfs_type"])?.takeIf { it.isNotBlank() }?.also {
            obfsType = it
        }
        (parsed.queryParams["obfs-password"] ?: parsed.queryParams["obfs_password"] ?: parsed.queryParams["obfsParam"])?.also {
            obfuscation = it
        }
        parsed.queryParams["alpn"]?.takeIf { it.isNotBlank() && it != "none" }?.also {
            alpn = it
        }
    }
}

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (protocolVersion == 2) {
        if (authPayload.contains(":")) {
            un = authPayload.substringBefore(":")
            pw = authPayload.substringAfter(":")
        } else {
            un = authPayload
        }
    }
    //
    val builder = linkBuilder()
        .host(serverAddress)
        .port(getFirstPort(serverPorts))
        .username(un)
        .password(pw)
    if (isMultiPort(displayAddress())) {
        builder.addQueryParameter("mport", serverPorts)
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (protocolVersion == 1) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("peer", sni)
        }
        if (authPayload.isNotBlank()) {
            builder.addQueryParameter("auth", authPayload)
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        if (alpn.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "xplus")
            builder.addQueryParameter("obfsParam", obfuscation)
        }
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                builder.addQueryParameter("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                builder.addQueryParameter("protocol", "wechat-video")
            }
        }
    } else {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (obfuscation.isNotBlank()) {
            val effObfsType = obfsType?.takeIf { it.isNotBlank() } ?: "salamander"
            builder.addQueryParameter("obfs", effObfsType)
            builder.addQueryParameter("obfs-password", obfuscation)
        }
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

fun JSONObject.parseHysteria1Json(): HysteriaBean {
    // TODO parse HY2 JSON+YAML
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = optString("server").substringBeforeLast(":")
        serverPorts = optString("server").substringAfterLast(":")
        uploadMbps = getIntNya("up_mbps")
        downloadMbps = getIntNya("down_mbps")
        obfuscation = getStr("obfs")
        getStr("auth")?.also {
            authPayloadType = HysteriaBean.TYPE_BASE64
            authPayload = it
        }
        getStr("auth_str")?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        getStr("protocol")?.also {
            when (it) {
                "faketcp" -> {
                    protocol = HysteriaBean.PROTOCOL_FAKETCP
                }

                "wechat-video" -> {
                    protocol = HysteriaBean.PROTOCOL_WECHAT_VIDEO
                }
            }
        }
        sni = getStr("server_name")
        getStr("alpn")?.also { if (it != "none") alpn = it }
        allowInsecure = getBool("insecure")

        streamReceiveWindow = getIntNya("recv_window_conn")
        connectionReceiveWindow = getIntNya("recv_window")
        disableMtuDiscovery = getBool("disable_mtu_discovery")
    }
}

fun HysteriaBean.buildHysteria1Config(port: Int, cacheFile: (() -> File)?): String {
    if (protocolVersion != 1) {
        throw Exception("error version: $protocolVersion")
    }
    return JSONObject().apply {
        put("server", displayAddress())
        when (protocol) {
            HysteriaBean.PROTOCOL_FAKETCP -> {
                put("protocol", "faketcp")
            }

            HysteriaBean.PROTOCOL_WECHAT_VIDEO -> {
                put("protocol", "wechat-video")
            }
        }
        put("up_mbps", uploadMbps)
        put("down_mbps", downloadMbps)
        put(
            "socks5", JSONObject(
                mapOf(
                    "listen" to "$LOCALHOST:$port",
                )
            )
        )
        put("retry", 5)
        put("fast_open", true)
        put("lazy_start", true)
        put("obfs", obfuscation)
        when (authPayloadType) {
            HysteriaBean.TYPE_BASE64 -> put("auth", authPayload)
            HysteriaBean.TYPE_STRING -> put("auth_str", authPayload)
        }
        if (sni.isBlank() && finalAddress == LOCALHOST && !serverAddress.isIpAddress()) {
            sni = serverAddress
        }
        if (sni.isNotBlank()) {
            put("server_name", sni)
        }
        if (alpn.isNotBlank()) put("alpn", alpn)
        if (caText.isNotBlank() && cacheFile != null) {
            val caFile = cacheFile()
            caFile.writeText(caText)
            put("ca", caFile.absolutePath)
        }

        if (allowInsecure) put("insecure", true)
        if (streamReceiveWindow > 0) put("recv_window_conn", streamReceiveWindow)
        if (connectionReceiveWindow > 0) put("recv_window", connectionReceiveWindow)
        if (disableMtuDiscovery) put("disable_mtu_discovery", true)

        put("hop_interval", hopInterval)
    }.toStringPretty()
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}

fun getFirstPort(portStr: String): Int {
    return portStr.substringBefore(":").substringBefore("-").substringBefore(",").trim().toIntOrNull() ?: 443
}

fun HysteriaBean.canUseSingBox(): Boolean {
    if (protocol != HysteriaBean.PROTOCOL_UDP) return false
    return true
}

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean): SingBoxOptions.SingBoxOption {
    return when (bean.protocolVersion) {
        1 -> SingBoxOptions.Outbound_HysteriaOptions().apply {
            type = "hysteria"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            obfs = bean.obfuscation
            disable_mtu_discovery = bean.disableMtuDiscovery == true
            when (bean.authPayloadType) {
                HysteriaBean.TYPE_BASE64 -> auth = bean.authPayload
                HysteriaBean.TYPE_STRING -> auth_str = bean.authPayload
            }
            if (bean.streamReceiveWindow != null && bean.streamReceiveWindow > 0) {
                recv_window_conn = bean.streamReceiveWindow.toLong()
            }
            if (bean.connectionReceiveWindow != null && bean.connectionReceiveWindow > 0) {
                recv_window_conn = bean.connectionReceiveWindow.toLong()
            }
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                val effectiveSni = bean.sni?.takeIf { it.isNotBlank() } ?: bean.serverAddress
                if (!effectiveSni.isNullOrBlank()) {
                    server_name = effectiveSni
                }
                if (!bean.alpn.isNullOrBlank()) {
                    alpn = bean.alpn.listByLineOrComma()
                }
                if (!bean.caText.isNullOrBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure == true || runCatching { DataStore.globalAllowInsecure }.getOrDefault(false)
                enabled = true
            }
        }

        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            server = bean.serverAddress
            val port = bean.serverPorts?.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                val hopList = bean.serverPorts?.let { hopPortsToSingboxList(it) } ?: emptyList()
                if (hopList.isNotEmpty()) {
                    server_ports = hopList
                    val interval = if (bean.hopInterval != null && bean.hopInterval > 0) bean.hopInterval else 30
                    hop_interval = "${interval}s"
                } else {
                    server_port = getFirstPort(bean.serverPorts)
                }
            }
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            if (!bean.obfuscation.isNullOrBlank()) {
                obfs = SingBoxOptions.Hysteria2Obfs().apply {
                    type = bean.obfsType?.takeIf { it.isNotBlank() } ?: "salamander"
                    password = bean.obfuscation
                }
            }
            password = bean.authPayload
            udp_fragment = true

            // QUIC & Mobile network resilience optimizations:
            keep_alive_period = "15s"
            idle_timeout = "30s"
            if (bean.disableMtuDiscovery == true) {
                disable_path_mtu_discovery = true
            }
            if (bean.streamReceiveWindow != null && bean.streamReceiveWindow > 0) {
                stream_receive_window = bean.streamReceiveWindow.toLong()
            }
            if (bean.connectionReceiveWindow != null && bean.connectionReceiveWindow > 0) {
                connection_receive_window = bean.connectionReceiveWindow.toLong()
            }
            bbr_profile = "standard"

            tls = SingBoxOptions.OutboundTLSOptions().apply {
                val effectiveSni = bean.sni?.takeIf { it.isNotBlank() } ?: bean.serverAddress
                if (!effectiveSni.isNullOrBlank()) {
                    server_name = effectiveSni
                }
                alpn = if (!bean.alpn.isNullOrBlank()) {
                    bean.alpn.listByLineOrComma()
                } else {
                    listOf("h3")
                }
                if (!bean.caText.isNullOrBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure == true || runCatching { DataStore.globalAllowInsecure }.getOrDefault(false)
                enabled = true
            }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}

fun hopPortsToSingboxList(s: String): List<String> {
    return s.split(",").mapNotNull { item ->
        val trimmed = item.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        if (trimmed.contains(":") || trimmed.contains("-")) {
            val delimiter = if (trimmed.contains(":")) ":" else "-"
            val parts = trimmed.split(delimiter).map { it.trim() }
            if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
                "${parts[0]}:${parts[1]}"
            } else {
                null
            }
        } else if (trimmed.toIntOrNull() != null) {
            "${trimmed}:${trimmed}"
        } else {
            null
        }
    }
}
