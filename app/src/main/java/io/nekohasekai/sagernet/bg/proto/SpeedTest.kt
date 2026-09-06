package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SpeedTestMode
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Speed test backend for Ownbox.
 *
 * Modes:
 *  - SIMPLE_DOWNLOAD: stream a fixed URL through the proxy socks5 port, measure bytes/s
 *  - DOWNLOAD_ONLY  : same as simple download (alias, kept for future full-speedtest server)
 *  - UPLOAD_ONLY    : reserved (returns -1, not yet implemented without a server)
 *  - FULL           : reserved (DL+UL, not yet implemented without a server)
 *
 * Result is in Kbps (kilobits per second). Returns -1 on error / unsupported mode.
 */
class SpeedTest {

    private val mode = DataStore.speedTestMode
    private val timeoutMs = DataStore.speedTestTimeout.toLong()
    private val downloadUrl = DataStore.simpleDlUrl.ifBlank {
        "https://speed.cloudflare.com/__down?bytes=10000000"
    }

    /**
     * Run a speed test for [profile].
     * The proxy must already be running and listening on [DataStore.mixedPort].
     *
     * @return speed in Kbps, or -1 on failure / unsupported mode
     */
    suspend fun doTest(profile: ProxyEntity): Long = withContext(Dispatchers.IO) {
        return@withContext when (mode) {
            SpeedTestMode.SIMPLE_DOWNLOAD,
            SpeedTestMode.DOWNLOAD_ONLY -> simpleDownload()
            SpeedTestMode.UPLOAD_ONLY -> {
                Logs.w("SpeedTest: UPLOAD_ONLY not yet implemented")
                -1L
            }
            SpeedTestMode.FULL -> {
                Logs.w("SpeedTest: FULL mode not yet implemented")
                -1L
            }
            else -> {
                Logs.w("SpeedTest: unknown mode $mode")
                -1L
            }
        }
    }

    /**
     * Simple download speed test via local socks5 proxy port.
     * Streams bytes from [downloadUrl] and calculates average download speed in Kbps.
     */
    private fun simpleDownload(): Long {
        val proxyPort = DataStore.mixedPort
        val socks5Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))

        val client = OkHttpClient.Builder()
            .proxy(socks5Proxy)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(downloadUrl)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logs.w("SpeedTest: HTTP ${response.code}")
                    return -1L
                }
                val body = response.body ?: return -1L
                val startMs = System.currentTimeMillis()
                var totalBytes = 0L
                val buffer = ByteArray(8192)
                val inputStream = body.byteStream()
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                }
                val elapsedMs = System.currentTimeMillis() - startMs
                if (elapsedMs <= 0 || totalBytes <= 0) return -1L
                // bytes/ms → bits/ms × 1000 → bits/s ÷ 1000 → Kbps
                val kbps = (totalBytes * 8L * 1000L) / (elapsedMs * 1000L)
                Logs.d("SpeedTest: downloaded ${totalBytes}B in ${elapsedMs}ms = ${kbps}Kbps")
                kbps
            }
        } catch (e: Exception) {
            Logs.w("SpeedTest: $e")
            -1L
        }
    }
}
