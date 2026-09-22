package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.unUrlSafe
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// URI 格式: snell://base64(psk)@server:port?version=6&userkey=base64(userkey)&mode=default&reuse=true&network=tcp#name
fun parseSnell(url: String): SnellBean {
    val link = url.replace("snell://", "https://").toHttpUrlOrNull()
    if (link != null) {
        return SnellBean().apply {
            serverAddress = link.host
            serverPort = link.port
            psk = (link.username.takeIf { it.isNotBlank() } ?: link.queryParameter("psk") ?: "").unUrlSafe()
            name = (link.fragment ?: "").unUrlSafe()

            (link.queryParameter("version") ?: link.queryParameter("v"))?.toIntOrNull()?.let {
                version = it.coerceIn(1, 6)
            }
            link.queryParameter("userkey")?.let { userKey = it.unUrlSafe() }
            (link.queryParameter("obfs-mode") ?: link.queryParameter("obfs"))?.let { obfsMode = it }
            (link.queryParameter("obfs-host") ?: link.queryParameter("host"))?.let { obfsHost = it }
            link.queryParameter("reuse")?.let { reuse = it == "1" || it.equals("true", ignoreCase = true) }
            link.queryParameter("network")?.let { network = it }
            link.queryParameter("mode")?.let { mode = it }
        }
    }

    // Fallback regex parsing for raw/non-standard snell:// links
    val regex = Regex("""^snell://(?:(?<psk>[^@]+)@)?(?<host>[^:/?#]+)(?::(?<port>\d+))?(?:[/?#](?<rest>.*))?$""")
    val match = regex.find(url) ?: error("Invalid snell URL: $url")
    val pskStr = match.groups["psk"]?.value?.unUrlSafe() ?: ""
    val hostStr = match.groups["host"]?.value ?: ""
    val portStr = match.groups["port"]?.value?.toIntOrNull() ?: 443
    val rest = match.groups["rest"]?.value ?: ""

    val queryPart = rest.substringBefore('#')
    val fragment = if (rest.contains('#')) rest.substringAfter('#') else ""

    val queryParams = mutableMapOf<String, String>()
    if (queryPart.isNotBlank()) {
        queryPart.trimStart('?').split('&').forEach { param ->
            val parts = param.split('=', limit = 2)
            if (parts.isNotEmpty()) {
                queryParams[parts[0]] = if (parts.size > 1) parts[1].unUrlSafe() else ""
            }
        }
    }

    return SnellBean().apply {
        serverAddress = hostStr
        serverPort = portStr
        psk = (pskStr.takeIf { it.isNotBlank() } ?: queryParams["psk"] ?: "")
        name = fragment.unUrlSafe()

        (queryParams["version"] ?: queryParams["v"])?.toIntOrNull()?.let {
            version = it.coerceIn(1, 6)
        }
        queryParams["userkey"]?.let { userKey = it }
        (queryParams["obfs-mode"] ?: queryParams["obfs"])?.let { obfsMode = it }
        (queryParams["obfs-host"] ?: queryParams["host"])?.let { obfsHost = it }
        queryParams["reuse"]?.let { reuse = it == "1" || it.equals("true", ignoreCase = true) }
        queryParams["network"]?.let { network = it }
        queryParams["mode"]?.let { mode = it }
    }
}

fun SnellBean.toUri(): String {
    val builder = StringBuilder("snell://")
    builder.append((psk ?: "").urlSafe()).append("@")
    builder.append(serverAddress).append(":").append(serverPort)

    val params = mutableListOf<String>()
    params.add("version=${version ?: 4}")
    if (!userKey.isNullOrBlank()) params.add("userkey=${userKey.urlSafe()}")
    if (version == 6) {
        if (!mode.isNullOrBlank() && mode != "default") params.add("mode=$mode")
    } else {
        if (!obfsMode.isNullOrBlank()) params.add("obfs-mode=$obfsMode")
        if (!obfsHost.isNullOrBlank()) params.add("obfs-host=$obfsHost")
    }
    if (reuse == true) params.add("reuse=true")
    if (!network.isNullOrBlank()) params.add("network=$network")

    builder.append("?").append(params.joinToString("&"))

    if (!name.isNullOrBlank()) {
        builder.append("#").append(name.urlSafe())
    }

    return builder.toString()
}

fun parseClashSnell(proxy: Map<String, Any?>): SnellBean {
    return SnellBean().apply {
        name = proxy["name"] as? String ?: ""
        serverAddress = proxy["server"] as? String ?: ""
        serverPort = (proxy["port"] as? Number)?.toInt() ?: 443
        psk = proxy["psk"] as? String ?: ""

        val clashVersion = ((proxy["version"] as? Number)?.toInt() ?: 4).coerceIn(1, 5)
        version = if (clashVersion == 5) 4 else clashVersion

        reuse = proxy["reuse"] as? Boolean ?: false

        val udpEnabled = proxy["udp"] as? Boolean ?: false
        network = if (udpEnabled) {
            ""
        } else {
            "tcp"
        }

        // obfs-opts
        (proxy["obfs-opts"] as? Map<*, *>)?.let { obfsOpts ->
            obfsMode = obfsOpts["mode"] as? String ?: ""
            obfsHost = obfsOpts["host"] as? String ?: ""
        }
    }
}
