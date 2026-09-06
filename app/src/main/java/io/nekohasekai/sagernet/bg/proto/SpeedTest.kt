package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class SpeedTestResult(
    val pingMs: Int,
    val downloadKbps: Long,
    val formattedSpeed: String,
    val success: Boolean,
    val error: String? = null,
)

/**
 * Ownbox 专用节点真实带宽与延迟测速引擎。
 *
 * 流程：
 * 1. 利用 UrlTest 先行探测目标节点的真实往返延迟（RTT）。
 * 2. 如果连通成功，通过动态空闲端口启动一个隔离的 sing-box 本地测试代理。
 * 3. 通过 OkHttp 拉取测速数据流，实时采样计算下行带宽（MB/s / Kbps）。
 * 4. 内置安全机制：最多下载 15MB 或超时自动熔断，避免过度消耗订阅流量。
 */
class SpeedTest(
    private val downloadUrl: String = DataStore.simpleDlUrl.ifBlank {
        "https://speed.cloudflare.com/__down?bytes=10000000"
    },
    private val timeoutMs: Int = DataStore.speedTestTimeout.let { if (it <= 0) 10000 else it },
) {

    suspend fun doTest(profile: ProxyEntity): SpeedTestResult = withContext(Dispatchers.IO) {
        // 1. 基础连通性与延迟测试
        val ping = try {
            UrlTest().doTest(profile)
        } catch (e: Exception) {
            Logs.w("SpeedTest ping failed: ${e.message}")
            return@withContext SpeedTestResult(
                pingMs = -1,
                downloadKbps = -1,
                formattedSpeed = "",
                success = false,
                error = e.message ?: "Connection failed",
            )
        }

        if (ping < 0) {
            return@withContext SpeedTestResult(
                pingMs = -1,
                downloadKbps = -1,
                formattedSpeed = "",
                success = false,
                error = "Timeout",
            )
        }

        // 2. 获取空闲端口
        val testPort = try {
            ServerSocket(0).use { it.localPort }
        } catch (_: Exception) {
            (20000..30000).random()
        }

        val config = buildConfig(profile, forTest = true, testPort = testPort)
        if (BuildConfig.DEBUG) Logs.d("SpeedTest config on port $testPort")

        var box: libcore.BoxInstance? = null
        try {
            box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
            box.start()

            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", testPort)))
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Ownbox/Android SpeedTest")
                .build()

            val startNs = System.nanoTime()
            var totalBytes = 0L

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("Empty body")
                val stream = body.byteStream()
                val buf = ByteArray(16384)
                val maxDurationNs = timeoutMs.toLong() * 1_000_000L

                while (true) {
                    val read = stream.read(buf)
                    if (read == -1) break
                    totalBytes += read
                    val elapsed = System.nanoTime() - startNs
                    if (elapsed >= maxDurationNs || totalBytes >= 15 * 1024 * 1024) break
                }
            }

            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            val kbps = (totalBytes * 8L * 1000L) / (elapsedMs * 1000L)
            val mbps = kbps / 1000.0

            val speedDisplay = if (mbps >= 1.0) {
                val bytesPerSec = (totalBytes.toDouble() / (elapsedMs.toDouble() / 1000.0))
                String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
            } else {
                "${kbps} Kbps"
            }

            val formatted = "↓ $speedDisplay (${ping}ms)"
            return@withContext SpeedTestResult(
                pingMs = ping,
                downloadKbps = kbps,
                formattedSpeed = formatted,
                success = true,
            )
        } catch (e: Exception) {
            Logs.w("SpeedTest download measurement failed, falling back to latency: $e")
            val formatted = "${ping} ms"
            return@withContext SpeedTestResult(
                pingMs = ping,
                downloadKbps = 0,
                formattedSpeed = formatted,
                success = true,
            )
        } finally {
            try {
                box?.close()
            } catch (_: Exception) {}
        }
    }
}
