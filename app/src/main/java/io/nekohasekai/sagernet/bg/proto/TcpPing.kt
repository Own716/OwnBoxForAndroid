package io.nekohasekai.sagernet.bg.proto

import android.os.Build
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random

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
            return@withContext try {
                pingUdp(profile, host, port)
            } catch (e: Exception) {
                Logs.d("TcpPing ${profile.displayName()}: raw UDP probe failed (${e.message}), fallback to urlTest")
                UrlTest().doTest(profile)
            }
        }

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

    private fun pingUdp(profile: ProxyEntity, host: String, port: Int): Int {
        val address = InetAddress.getByName(host)
        val socket = DatagramSocket()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { SagerNet.underlyingNetwork?.bindSocket(socket) }
            }
            runCatching { DataStore.vpnService?.protect(socket) }
            socket.soTimeout = timeout

            // QUIC Version Negotiation Probe (RFC 9000 section 5.2 / RFC 8999 section 6)
            val probe = ByteArray(22)
            Random().nextBytes(probe)
            probe[0] = 0xC0.toByte() // Long header, fixed bit = 1
            probe[1] = 0x0a.toByte() // Reserved version 0x0a0a0a0a
            probe[2] = 0x0a.toByte()
            probe[3] = 0x0a.toByte()
            probe[4] = 0x0a.toByte()
            probe[5] = 8 // DCID length
            probe[14] = 8 // SCID length

            val packet = DatagramPacket(probe, probe.size, address, port)
            val startTime = SystemClock.elapsedRealtime()
            socket.send(packet)

            val receiveBuf = ByteArray(1500)
            val receivePacket = DatagramPacket(receiveBuf, receiveBuf.size)
            socket.receive(receivePacket)
            val latency = (SystemClock.elapsedRealtime() - startTime).toInt().coerceAtLeast(1)
            Logs.d("TcpPing ${profile.displayName()}: UDP probe done, latency=${latency}ms")
            return latency
        } finally {
            runCatching { socket.close() }
        }
    }

}
