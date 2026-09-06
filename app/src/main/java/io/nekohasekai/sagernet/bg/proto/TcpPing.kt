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
        val port = if (bean.finalPort != 0) bean.finalPort else (bean.serverPort ?: 443)

        if (host.isNullOrBlank() || port <= 0 || port > 65535) {
            error("Invalid host or port: $host:$port")
        }

        Logs.d("TcpPing ${profile.displayName()}: start, host=$host, port=$port, timeout=${timeout}ms")
        val socket = Socket()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { SagerNet.underlyingNetwork?.bindSocket(socket) }
            }
            runCatching { DataStore.vpnService?.protect(socket) }

            val startTime = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(host, port), timeout)
            val latency = (SystemClock.elapsedRealtime() - startTime).toInt()
            Logs.d("TcpPing ${profile.displayName()}: done, latency=${latency}ms")
            latency
        } finally {
            runCatching { socket.close() }
        }
    }

}
