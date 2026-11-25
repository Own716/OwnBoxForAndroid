/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.mieru

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

fun MieruBean.buildSingBoxOutboundMieruBean(): SingBoxOptions.Outbound_MieruOptions {
    return buildSingBoxOutboundMieruBean(this)
}

fun buildSingBoxOutboundMieruBean(bean: MieruBean): SingBoxOptions.Outbound_MieruOptions {
    val isUdp = bean.protocol.equals("UDP", ignoreCase = true)
    val range = bean.portRange?.trim()?.replace(':', '-')?.takeIf { it.isNotBlank() }
    return SingBoxOptions.Outbound_MieruOptions().apply {
        type = "mieru"
        server = bean.serverAddress
        if (range != null) {
            port_range = range
        } else {
            server_port = bean.serverPort
        }
        transport = if (isUdp) "UDP" else "TCP"
        username = bean.username
        password = bean.password
        if (isUdp && bean.mtu != null && bean.mtu in 1280..1500) {
            mtu = bean.mtu
        }
        multiplexing = bean.multiplexing?.takeIf { it.isNotBlank() }
        handshake_mode = bean.handshakeMode?.takeIf { it.isNotBlank() }
        traffic_pattern = bean.trafficPattern?.takeIf { it.isNotBlank() }
    }
}

fun parseMieru(link: String): MieruBean {
    if (!link.startsWith("mierus://", ignoreCase = true)) {
        error("Unsupported mieru link format: $link")
    }
    val rawFragment = if (link.contains("#")) link.substringAfter("#").trim() else ""
    val linkWithoutFragment = if (link.contains("#")) link.substringBefore("#") else link
    val decodedFragment = if (rawFragment.isNotBlank()) {
        runCatching { java.net.URLDecoder.decode(rawFragment, "UTF-8") }.getOrDefault(rawFragment)
    } else ""

    val rawUrlStr = "https://" + linkWithoutFragment.substringAfter("://")
    val url = rawUrlStr.toHttpUrlOrNull() ?: error("Invalid mieru link: $link")

    return MieruBean().applyDefaultValues().apply {
        serverAddress = url.host
        username = url.username
        password = url.password

        val portParam = url.queryParameter("port")?.trim().orEmpty()
        if (portParam.isNotBlank()) {
            val normalized = portParam.replace(':', '-')
            if (normalized.contains('-')) {
                portRange = normalized
                serverPort = normalized.substringBefore('-').trim().toIntOrNull() ?: url.port
            } else {
                serverPort = normalized.toIntOrNull() ?: url.port
            }
        } else {
            serverPort = url.port
        }

        val protoRaw = url.queryParameter("protocol")
            ?: url.queryParameter("transport")
            ?: "TCP"
        protocol = if (protoRaw.equals("UDP", ignoreCase = true)) "UDP" else "TCP"

        url.queryParameter("mtu")?.toIntOrNull()?.let { mtu = it }
        url.queryParameter("multiplexing")?.trim()?.takeIf { it.isNotBlank() }?.let { multiplexing = it }
        url.queryParameter("handshake-mode")?.trim()?.takeIf { it.isNotBlank() }?.let { handshakeMode = it }
        url.queryParameter("traffic-pattern")?.trim()?.takeIf { it.isNotBlank() }?.let { trafficPattern = it }

        val profileParam = url.queryParameter("profile")?.trim().orEmpty()
        name = when {
            decodedFragment.isNotBlank() -> decodedFragment
            profileParam.isNotBlank() -> profileParam
            else -> ""
        }
    }
}

fun MieruBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress)
        .username(username)
        .password(password)

    val isUdp = protocol.equals("UDP", ignoreCase = true)
    val range = portRange?.trim()?.takeIf { it.isNotBlank() }
    val firstPort = range?.substringBefore('-')?.trim()?.toIntOrNull()
        ?: serverPort?.takeIf { it in 1..65535 }
        ?: 443
    builder.port(firstPort)

    builder.addQueryParameter("profile", name.takeIf { !it.isNullOrBlank() } ?: "default")
    builder.addQueryParameter("port", range ?: firstPort.toString())
    builder.addQueryParameter("protocol", if (isUdp) "UDP" else "TCP")

    if (isUdp && mtu != null && mtu in 1280..1500) {
        builder.addQueryParameter("mtu", mtu.toString())
    }
    if (!multiplexing.isNullOrBlank()) {
        builder.addQueryParameter("multiplexing", multiplexing)
    }
    if (!handshakeMode.isNullOrBlank()) {
        builder.addQueryParameter("handshake-mode", handshakeMode)
    }
    if (!trafficPattern.isNullOrBlank()) {
        builder.addQueryParameter("traffic-pattern", trafficPattern)
    }
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    return builder.toLink("mierus")
}

fun MieruBean.buildMieruConfig(port: Int): String {
    val serverInfo = JSONArray().apply {
        put(JSONObject().apply {
            put("ipAddress", finalAddress)
            put("portBindings", JSONArray().apply {
                put(JSONObject().apply {
                    put("port", finalPort)
                    put("protocol", protocol)
                })
            })
        })
    }
    return JSONObject().apply {
        put("activeProfile", "default")
        put("socks5Port", port)
        // TODO: follow NekoBox logging level.
        put("loggingLevel", "INFO")
        put("profiles", JSONArray().apply {
            put(JSONObject().apply {
                put("profileName", "default")
                put("user", JSONObject().apply {
                    put("name", username)
                    put("password", password)
                })
                put("servers", serverInfo)
                put("mtu", mtu)
            })
        })
    }.toStringPretty()
}
