package io.nekohasekai.sagernet.bg.proto

import android.os.Build
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class TcpPing {

    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int = withContext(Dispatchers.IO) {
        val bean = profile.requireBean()
        val host = if (!bean.finalAddress.isNullOrBlank()) bean.finalAddress else bean.serverAddress
        val port = if (bean.finalPort != 0) {
            bean.finalPort
        } else if (bean is io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean) {
            io.nekohasekai.sagernet.fmt.hysteria.getFirstPort(bean.serverPorts ?: "443")
        } else {
            bean.serverPort ?: 443
        }

        if (host.isNullOrBlank() || port <= 0 || port > 65535) {
            error("Invalid host or port: $host:$port")
        }

        Logs.d("TcpPing ${profile.displayName()}: start, host=$host, port=$port, timeout=${timeout}ms")

        val isUdpOnly = bean is io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean ||
                bean is io.nekohasekai.sagernet.fmt.tuic.TuicBean ||
                bean is io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean

        if (isUdpOnly) {
            Logs.d("TcpPing ${profile.displayName()}: UDP/QUIC protocol, using URLTest directly")
            return@withContext UrlTest().doTest(profile)
        }

        val socket = Socket()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { SagerNet.underlyingNetwork?.bindSocket(socket) }
            }
            runCatching { DataStore.vpnService?.protect(socket) }

            // 预先解析地址，避免将本地 Android DNS 解析耗时计入 TCP Ping 握手延迟中导致延迟虚高
            val address = InetSocketAddress(host, port)
            val startTime = SystemClock.elapsedRealtime()
            socket.connect(address, timeout)
            val latency = (SystemClock.elapsedRealtime() - startTime).toInt().coerceAtLeast(1)
            Logs.d("TcpPing ${profile.displayName()}: done, latency=${latency}ms")
            latency
        } finally {
            runCatching { socket.close() }
        }
    }

}
