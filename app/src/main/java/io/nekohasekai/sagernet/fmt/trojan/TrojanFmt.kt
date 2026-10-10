package io.nekohasekai.sagernet.fmt.trojan

import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseTrojan(server: String): TrojanBean {
    val rawFragment = if (server.contains("#")) server.substringAfter("#").trim() else ""
    val linkWithoutFragment = if (server.contains("#")) server.substringBefore("#") else server
    val nodeName = if (rawFragment.isNotBlank()) {
        runCatching { java.net.URLDecoder.decode(rawFragment, "UTF-8") }.getOrDefault(rawFragment)
    } else null

    val link = linkWithoutFragment.replace("trojan://", "https://").toHttpUrlOrNull()
        ?: error("invalid trojan link $server")

    return TrojanBean().apply {
        parseDuckSoft(link, nodeName)
        val ins = link.queryParameter("allowInsecure") ?: link.queryParameter("allow_insecure") ?: link.queryParameter("insecure")
        if (ins != null && (ins == "1" || ins.equals("true", ignoreCase = true))) allowInsecure = true
        (link.queryParameter("peer") ?: link.queryParameter("sni"))?.apply { if (this.isNotBlank()) sni = this }
    }
}
