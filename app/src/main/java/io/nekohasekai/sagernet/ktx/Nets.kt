@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.fmt.AbstractBean
import libcore.HTTPClient
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.HttpUrl
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 内部 HTTP（订阅/资产/版本检查）优先经 mixedPort SOCKS5 代理拨号（跨主进程与 :bg 进程通用）；
 * 当禁用混合入站（纯 TUN）时回退至 tryBoxOutbound。
 */
fun HTTPClient.tryProxyOutbound() {
    if (!io.nekohasekai.sagernet.database.DataStore.mixedInboundDisabled) {
        trySocks5(
            io.nekohasekai.sagernet.database.DataStore.mixedPort,
            io.nekohasekai.sagernet.database.DataStore.mixedInboundUser,
            io.nekohasekai.sagernet.database.DataStore.mixedInboundPass
        )
    } else {
        tryBoxOutbound()
    }
}

fun linkBuilder() = HttpUrl.Builder().scheme("https")

fun HttpUrl.Builder.toLink(scheme: String, appendDefaultPort: Boolean = true): String {
    var url = build()
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    var replace = false
    if (appendDefaultPort && url.port == defaultPort) {
        url = url.newBuilder().port(14514).build()
        replace = true
    }
    return url.toString().replace("${url.scheme}://", "$scheme://").let {
        if (replace) it.replace(":14514", ":$defaultPort") else it
    }
}

fun String.isIpAddress(): Boolean {
    return NGUtil.isIpv4Address(this) || NGUtil.isIpv6Address(this)
}

fun String.isIpAddressV6(): Boolean {
    return NGUtil.isIpv6Address(this)
}

// [2001:4860:4860::8888] -> 2001:4860:4860::8888
fun String.unwrapIPV6Host(): String {
    if (startsWith("[") && endsWith("]")) {
        return substring(1, length - 1).unwrapIPV6Host()
    }
    return this
}

// [2001:4860:4860::8888] or 2001:4860:4860::8888 -> [2001:4860:4860::8888]
fun String.wrapIPV6Host(): String {
    val unwrapped = this.unwrapIPV6Host()
    if (unwrapped.isIpAddressV6()) {
        return "[$unwrapped]"
    } else {
        return this
    }
}

fun AbstractBean.wrapUri(): String {
    return "${finalAddress.wrapIPV6Host()}:$finalPort"
}

fun mkPort(): Int {
    val socket = Socket()
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(0))
    val port = socket.localPort
    socket.close()
    return port
}

val USER_AGENT: String
    get() = io.nekohasekai.sagernet.database.DataStore.defaultSubscriptionUserAgent
