package io.nekohasekai.sagernet.fmt.tuic

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTuic(url: String): TuicBean {
    // https://github.com/daeuniverse/dae/discussions/182
    val link = url.replace("tuic://", "https://").toHttpUrlOrNull() ?: error(
        "invalid tuic link $url"
    )
    return TuicBean().apply {
        protocolVersion = 5

        val rawFrag = url.substringAfter("#", "")
        name = if (rawFrag.isNotBlank()) {
            runCatching { java.net.URLDecoder.decode(rawFrag, "UTF-8") }.getOrDefault(rawFrag)
        } else link.fragment

        serverAddress = link.host
        serverPort = link.port

        val rawUser = link.username
        val rawPass = link.password

        if (rawUser.contains(":")) {
            val parts = rawUser.split(":", limit = 2)
            uuid = parts[0]
            token = parts.getOrElse(1) { "" }
        } else {
            uuid = rawUser
            token = rawPass
        }

        (link.queryParameter("sni") ?: link.queryParameter("peer"))?.let {
            if (it.isNotBlank()) sni = it
        }
        (link.queryParameter("congestion_control") ?: link.queryParameter("congestion-control") ?: link.queryParameter("congestion_controller"))?.let {
            if (it.isNotBlank()) congestionController = it
        }
        link.queryParameter("udp_relay_mode")?.let {
            if (it.isNotBlank()) udpRelayMode = it
        }
        link.queryParameter("alpn")?.let {
            if (it.isNotBlank() && it != "none") alpn = it
        }
        val ins = link.queryParameter("allow_insecure") ?: link.queryParameter("allowInsecure") ?: link.queryParameter("insecure")
        if (ins != null && (ins == "1" || ins.equals("true", ignoreCase = true))) {
            allowInsecure = true
        }
        val disSni = link.queryParameter("disable_sni") ?: link.queryParameter("disableSni")
        if (disSni != null && (disSni == "1" || disSni.equals("true", ignoreCase = true))) {
            disableSNI = true
        }
    }
}

fun TuicBean.toUri(): String {
    val builder = linkBuilder().username(uuid).password(token).host(serverAddress).port(serverPort)

    builder.addQueryParameter("congestion_control", congestionController)
    builder.addQueryParameter("udp_relay_mode", udpRelayMode)

    if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
    if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn)
    if (allowInsecure) builder.addQueryParameter("allow_insecure", "1")
    if (disableSNI) builder.addQueryParameter("disable_sni", "1")
    if (name.isNotBlank()) builder.encodedFragment(name.urlSafe())

    return builder.toLink("tuic")
}

fun buildSingBoxOutboundTuicBean(bean: TuicBean): SingBoxOptions.Outbound_TUICOptions {
    if (bean.protocolVersion == 4) throw Exception("TUIC v4 is no longer supported")
    return SingBoxOptions.Outbound_TUICOptions().apply {
        type = "tuic"
        server = bean.serverAddress
        server_port = bean.serverPort
        uuid = bean.uuid
        password = bean.token
        congestion_control = bean.congestionController
        when (bean.udpRelayMode) {
            "quic" -> udp_relay_mode = "quic"
        }
        zero_rtt_handshake = bean.reduceRTT
        udp_fragment = true
        tls = SingBoxOptions.OutboundTLSOptions().apply {
            val effectiveSni = bean.sni.takeIf { it.isNotBlank() } ?: bean.serverAddress
            if (effectiveSni.isNotBlank() && !bean.disableSNI) {
                server_name = effectiveSni
            }
            if (bean.alpn.isNotBlank()) {
                alpn = bean.alpn.listByLineOrComma()
            }
            if (bean.caText.isNotBlank()) {
                certificate = bean.caText
            }
            disable_sni = bean.disableSNI
            insecure = bean.allowInsecure || DataStore.globalAllowInsecure
            enabled = true
        }
    }
}
