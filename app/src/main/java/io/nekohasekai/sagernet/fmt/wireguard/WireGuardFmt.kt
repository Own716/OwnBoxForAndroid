package io.nekohasekai.sagernet.fmt.wireguard

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ini4j.Ini
import java.io.StringReader

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

fun parseWireGuardConfig(conf: String): List<WireGuardBean> {
    val ini = Ini().apply {
        config.isMultiSection = true
        load(StringReader(conf))
    }
    val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
    val isAwg = iface["Jc"] != null || iface["S1"] != null || iface["H1"] != null || conf.contains("awg://", ignoreCase = true)
    val localAddresses = iface.getAll("Address")
        ?.flatMap { value -> value.split(',') }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    if (localAddresses.isEmpty()) error("Empty address in 'Interface' selection")

    val baseBean = WireGuardBean().applyDefaultValues().apply {
        name = if (isAwg) "[AWG-Compat]" else ""
        localAddress = localAddresses.joinToString("\n")
        privateKey = iface["PrivateKey"]?.trim().orEmpty()
        iface["MTU"]?.trim()?.toIntOrNull()?.let { mtu = it }
        listenPort = iface["ListenPort"]?.trim()?.toIntOrNull() ?: 0
    }

    val peers = ini.getAll("Peer")
    if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")

    val beans = peers.mapNotNull { peer ->
        val (serverAddress, serverPort) = parseEndpoint(peer["Endpoint"] ?: return@mapNotNull null)
            ?: return@mapNotNull null
        val publicKey = peer["PublicKey"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null

        baseBean.clone().apply {
            this.serverAddress = serverAddress
            this.serverPort = serverPort
            peerPublicKey = publicKey
            peerPreSharedKey = peer["PresharedKey"]?.trim().orEmpty()
            persistentKeepaliveInterval =
                peer["PersistentKeepalive"]?.trim()?.toIntOrNull() ?: 0
            reserved = peer["Reserved"]?.trim().orEmpty()
        }.applyDefaultValues()
    }
    if (beans.isEmpty()) error("Empty available peer list")
    return beans
}

private fun parseEndpoint(value: String): Pair<String, Int>? {
    val endpoint = value.trim()
    val address: String
    val portValue: String
    if (endpoint.startsWith('[')) {
        val closingBracket = endpoint.indexOf(']')
        if (closingBracket <= 1 || endpoint.getOrNull(closingBracket + 1) != ':') return null
        address = endpoint.substring(1, closingBracket).trim()
        portValue = endpoint.substring(closingBracket + 2).trim()
    } else {
        val separator = endpoint.lastIndexOf(':')
        if (separator <= 0) return null
        address = endpoint.substring(0, separator).trim()
        portValue = endpoint.substring(separator + 1).trim()
    }
    val port = portValue.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return address.takeIf { it.isNotEmpty() }?.let { it to port }
}

fun parseWireGuardEndpoint(json: JsonObject): WireGuardBean? {
    if (json.stringValue("type") != "wireguard") return null
    val peer = json.arrayValue("peers")
        ?.firstOrNull()
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject
        ?: return null

    val localAddresses = json.listableStrings("address") ?: return null
    val privateKey = json.stringValue("private_key") ?: return null
    val serverAddress = peer.stringValue("address") ?: return null
    val serverPort = peer.intValue("port")?.takeIf { it in 1..65535 } ?: return null
    val publicKey = peer.stringValue("public_key") ?: return null

    return WireGuardBean().apply {
        name = json.stringValue("tag").orEmpty()
        localAddress = localAddresses.joinToString("\n")
        this.privateKey = privateKey
        mtu = json.intValue("mtu") ?: 0
        listenPort = json.intValue("listen_port") ?: 0
        this.serverAddress = serverAddress
        this.serverPort = serverPort
        peerPublicKey = publicKey
        peerPreSharedKey = peer.stringValue("pre_shared_key").orEmpty()
        persistentKeepaliveInterval = peer.intValue("persistent_keepalive_interval") ?: 0
        reserved = peer.reservedValue().orEmpty()
    }.applyDefaultValues()
}

fun parseWireGuardEndpoints(root: JsonObject): List<WireGuardBean> {
    return root.arrayValue("endpoints")
        ?.mapNotNull { endpoint ->
            endpoint.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let(::parseWireGuardEndpoint)
        }
        .orEmpty()
}

private fun JsonObject.stringValue(name: String): String? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    return value.asString.trim().takeIf { it.isNotEmpty() }
}

private fun JsonObject.intValue(name: String): Int? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    if (!value.isJsonPrimitive) return null
    return value.asJsonPrimitive.asString.trim().toIntOrNull()
}

private fun JsonObject.arrayValue(name: String): JsonArray? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    return value.takeIf(JsonElement::isJsonArray)?.asJsonArray
}

private fun JsonObject.listableStrings(name: String): List<String>? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    val values = when {
        value.isJsonArray -> value.asJsonArray.toList()
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value)
        else -> return null
    }
    return values.mapNotNull { element ->
        element.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.takeIf { it.isNotEmpty() }
}

private fun JsonObject.reservedValue(): String? {
    val value = get("reserved")?.takeUnless(JsonElement::isJsonNull) ?: return null
    return when {
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.trim()
        value.isJsonArray -> value.asJsonArray.mapNotNull { element ->
            element.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive?.asString?.trim()
        }.joinToString(", ")
        else -> null
    }?.takeIf { it.isNotEmpty() }
}

fun genReserved(anyStr: String): String {
    val values = anyStr
        .trim()
        .removeSurrounding("[", "]")
        .split(Regex("[,\\s]+"))
        .filter { it.isNotEmpty() }
        .map { value -> value.toIntOrNull()?.takeIf { it in 0..255 } ?: return anyStr }
    if (values.size != 3) return anyStr
    val bits = (values[0] shl 16) or (values[1] shl 8) or values[2]
    return buildString(4) {
        append(BASE64_ALPHABET[(bits ushr 18) and 0x3F])
        append(BASE64_ALPHABET[(bits ushr 12) and 0x3F])
        append(BASE64_ALPHABET[(bits ushr 6) and 0x3F])
        append(BASE64_ALPHABET[bits and 0x3F])
    }
}

fun buildSingBoxEndpointWireGuardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        mtu = bean.mtu?.takeIf { it > 0 }
        listen_port = bean.listenPort?.takeIf { it > 0 }
        peers = listOf(
            SingBoxOptions.Endpoint_WireGuardPeer().apply {
                address = bean.serverAddress?.takeIf { it.isNotBlank() }
                port = bean.serverPort?.takeIf { it > 0 }
                public_key = bean.peerPublicKey
                pre_shared_key = bean.peerPreSharedKey.takeIf { it.isNotBlank() }
                allowed_ips = listOf("0.0.0.0/0", "::/0")
                persistent_keepalive_interval = bean.persistentKeepaliveInterval?.takeIf { it > 0 }
                reserved = bean.reserved.takeIf { it.isNotBlank() }?.let(::genReserved)
            }
        )
    }
}

fun parseWireGuardLink(link: String): WireGuardBean? {
    val isAwg = link.startsWith("awg://", ignoreCase = true)
    if (!isAwg && !link.startsWith("wireguard://", ignoreCase = true)) return null

    val withoutScheme = link.substringAfter("://")
    if (!withoutScheme.contains("@") && !withoutScheme.contains("?")) {
        try {
            val decoded = String(android.util.Base64.decode(withoutScheme, android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE))
            if (decoded.contains("[Interface]")) {
                val list = parseWireGuardConfig(decoded)
                if (list.isNotEmpty()) {
                    val bean = list.first()
                    if (isAwg && !bean.name.startsWith("[AWG-Compat]")) {
                        bean.name = "[AWG-Compat] ${bean.name}".trim()
                    }
                    return bean
                }
            }
        } catch (_: Exception) {
        }
    }

    try {
        val raw = if (link.startsWith("awg://", ignoreCase = true)) {
            "https" + link.substring(3)
        } else {
            "https" + link.substring(9)
        }
        val uri = raw.toHttpUrlOrNull() ?: return null

        val serverAddress = uri.host
        val serverPort = uri.port
        val privateKey = uri.username.takeIf { it.isNotBlank() } ?: uri.queryParameter("private_key") ?: uri.queryParameter("privatekey") ?: ""
        val publicKey = uri.password?.takeIf { it.isNotBlank() } ?: uri.queryParameter("public_key") ?: uri.queryParameter("publickey") ?: uri.queryParameter("peer_public_key") ?: ""
        val address = uri.queryParameter("address") ?: uri.queryParameter("ip") ?: ""
        val psk = uri.queryParameter("preshared_key") ?: uri.queryParameter("presharedkey") ?: uri.queryParameter("psk") ?: ""
        val mtu = uri.queryParameter("mtu")?.toIntOrNull() ?: 1420
        val reserved = uri.queryParameter("reserved") ?: ""
        val tag = uri.fragment?.takeIf { it.isNotBlank() } ?: "WireGuard"

        val hasAwgParams = isAwg || uri.queryParameter("jc") != null || uri.queryParameter("jmin") != null || uri.queryParameter("s1") != null || uri.queryParameter("h1") != null

        return WireGuardBean().apply {
            name = if (hasAwgParams) "[AWG-Compat] $tag" else tag
            this.serverAddress = serverAddress
            this.serverPort = serverPort
            this.localAddress = address.replace(",", "\n")
            this.privateKey = privateKey
            this.peerPublicKey = publicKey
            this.peerPreSharedKey = psk
            this.mtu = mtu
            this.reserved = reserved
        }.applyDefaultValues()
    } catch (e: Exception) {
        return null
    }
}
