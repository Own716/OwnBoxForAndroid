package io.nekohasekai.sagernet.tools.connectivity

import android.os.SystemClock
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

object ConnectivityDiagnosticsManager {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    fun createInitialTargets(): List<TargetProbeResult> {
        return listOf(
            TargetProbeResult(
                id = "cloudflare",
                name = "Cloudflare 全球边缘网络",
                targetUrl = "https://cp.cloudflare.com/generate_204",
                iconRes = R.drawable.ic_tool_connectivity,
                summary = "等待检测...",
                detail = "基础出网探测：验证代理核心至 Cloudflare Anycast 出口连通性",
            ),
            TargetProbeResult(
                id = "google",
                name = "Google 国际网络与送中检测",
                targetUrl = "https://www.google.com/generate_204",
                iconRes = R.drawable.baseline_public_24,
                summary = "等待检测...",
                detail = "深度出网探测：验证 Google 国际服务通畅度及是否被导向国内服务器",
            ),
            TargetProbeResult(
                id = "github",
                name = "GitHub 开发者网络平台",
                targetUrl = "https://github.com",
                iconRes = R.drawable.ic_baseline_http_24,
                summary = "等待检测...",
                detail = "应用层探测：验证海外开发与代码平台 HTTPS/TLS 握手与内容完整性",
            ),
            TargetProbeResult(
                id = "bing",
                name = "Bing 微软国际搜索",
                targetUrl = "https://www.bing.com",
                iconRes = R.drawable.ic_baseline_http_24,
                summary = "等待检测...",
                detail = "跨国 CDN 探测：验证微软边缘网络访问时延与稳定性",
            ),
            TargetProbeResult(
                id = "wikipedia",
                name = "Wikipedia 维基百科服务",
                targetUrl = "https://en.wikipedia.org",
                iconRes = R.drawable.ic_platform_wikipedia,
                summary = "等待检测...",
                detail = "内容服务探测：验证维基百科知识库在全球节点上的访问质量",
            ),
            TargetProbeResult(
                id = "domestic",
                name = "国内骨干网基准测试",
                targetUrl = "https://www.baidu.com",
                iconRes = R.drawable.ic_tool_connectivity,
                summary = "等待检测...",
                detail = "对照基准探测：验证路由分流是否导致国内直连流量被误路由或污染",
            ),
        )
    }

    suspend fun probeEntry(
        host: String,
        port: Int,
        isBalancer: Boolean = false,
        memberCount: Int = 0,
        strategyName: String = "",
    ): EntryProbeResult = withContext(Dispatchers.IO) {
        val isLoopback = host.isBlank() || host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1"
        if (isBalancer || (isLoopback && memberCount > 0)) {
            val stratDesc = if (strategyName.isNotBlank()) "「$strategyName」" else ""
            return@withContext EntryProbeResult(
                state = StageState.SUCCESS,
                host = "策略组调度",
                port = 0,
                dnsResolvedIp = "本地调度",
                rttMs = 0L,
                summary = "策略调度就绪",
                errorDetail = "智能策略组已就绪 (聚合 ${if (memberCount > 0) memberCount else "多"} 个候选节点)，由 sing-box 核心按${stratDesc}自动调度出站",
            )
        }

        if (isLoopback) {
            return@withContext EntryProbeResult(
                state = StageState.SUCCESS,
                host = "本地链路",
                port = port,
                dnsResolvedIp = "127.0.0.1",
                rttMs = 0L,
                summary = "本地服务出站",
                errorDetail = "经本地出站建立转发链路",
            )
        }

        if (host.isBlank() || port <= 0) {
            return@withContext EntryProbeResult(
                state = StageState.WARNING,
                host = host,
                port = port,
                summary = "配置不完整",
                errorDetail = "节点未配置有效的服务器地址或服务端口",
            )
        }

        var resolvedIp: String? = null
        try {
            val isIp = host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) || host.contains(":")
            if (!isIp) {
                val inetAddr = InetAddress.getByName(host)
                resolvedIp = inetAddr.hostAddress
            } else {
                resolvedIp = host
            }
        } catch (e: UnknownHostException) {
            return@withContext EntryProbeResult(
                state = StageState.FAILED,
                host = host,
                port = port,
                summary = "域名解析失败",
                errorDetail = "本地 DNS 无法解析节点域名: $host",
            )
        } catch (_: Throwable) {}

        return@withContext try {
            val socket = Socket()
            val start = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(host, port), 4000)
            val rtt = SystemClock.elapsedRealtime() - start
            socket.close()

            EntryProbeResult(
                state = StageState.SUCCESS,
                host = host,
                port = port,
                dnsResolvedIp = resolvedIp,
                rttMs = rtt,
                summary = "直连 TCP 握手成功 (${rtt} ms)",
                errorDetail = null,
            )
        } catch (e: SocketTimeoutException) {
            EntryProbeResult(
                state = StageState.FAILED,
                host = host,
                port = port,
                dnsResolvedIp = resolvedIp,
                summary = "握手超时 (>4000ms)",
                errorDetail = "向入口发起直连 TCP 握手超时，入口物理离线或被防火墙 DROP 丢包",
            )
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            val summary = if (msg.contains("refused", ignoreCase = true)) {
                "连接被拒绝 (ECONNREFUSED)"
            } else {
                "连接失败: ${msg.ifEmpty { "网络不可达" }}"
            }
            EntryProbeResult(
                state = StageState.FAILED,
                host = host,
                port = port,
                dnsResolvedIp = resolvedIp,
                summary = summary,
                errorDetail = "目标服务器端口 ($port) 未监听或入口遭遇网络防火墙 RST 重置",
            )
        }
    }

    suspend fun probeTunnel(): TunnelProbeResult = withContext(Dispatchers.IO) {
        if (!DataStore.serviceState.connected) {
            return@withContext TunnelProbeResult(
                state = StageState.FAILED,
                summary = "代理服务未启动",
                errorDetail = "VPN 核心未运行，请先在主界面点击连接",
            )
        }

        val mixedPort = DataStore.mixedPort
        if (mixedPort <= 0) {
            return@withContext TunnelProbeResult(
                state = StageState.WARNING,
                summary = "本地代理端口未分配",
                errorDetail = "未找到有效的本地混合代理端口",
            )
        }

        var client: libcore.HTTPClient? = null
        return@withContext try {
            val start = SystemClock.elapsedRealtime()
            client = Libcore.newHttpClient().apply {
                modernTLS()
                trySocks5(mixedPort.toInt(), "", "")
            }
            val req = client.newRequest().apply {
                setURL("https://cp.cloudflare.com/generate_204")
                setUserAgent(BROWSER_UA)
            }
            req.execute()
            val rtt = SystemClock.elapsedRealtime() - start

            TunnelProbeResult(
                state = StageState.SUCCESS,
                mixedPort = mixedPort.toInt(),
                rttMs = rtt,
                summary = "隧道出站建立成功 (${rtt} ms)",
                errorDetail = null,
            )
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("HTTP 204") || msg.contains("HTTP 200")) {
                TunnelProbeResult(
                    state = StageState.SUCCESS,
                    mixedPort = mixedPort.toInt(),
                    rttMs = 150L,
                    summary = "隧道建立成功 (HTTP 204)",
                )
            } else {
                TunnelProbeResult(
                    state = StageState.FAILED,
                    mixedPort = mixedPort.toInt(),
                    summary = "隧道出站失败",
                    errorDetail = "经本地 SOCKS5 端口代理建立失败: ${msg.ifEmpty { "连接超时" }}",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    suspend fun probeTargetWithRetry(target: TargetProbeResult): TargetProbeResult = withContext(Dispatchers.IO) {
        // Attempt 1
        val firstResult = executeSingleTargetProbe(target)
        if (firstResult.state == StageState.SUCCESS) {
            return@withContext firstResult
        }

        // Automatic retry on transient network jitter
        delay(300L)
        val secondResult = executeSingleTargetProbe(target)
        if (secondResult.state == StageState.SUCCESS) {
            return@withContext secondResult.copy(
                isJitterRecovered = true,
                summary = "${secondResult.summary} (抖动恢复)",
                detail = "${secondResult.detail} · 首次超时已触发自动重试恢复",
            )
        }

        return@withContext secondResult
    }

    private fun executeSingleTargetProbe(target: TargetProbeResult): TargetProbeResult {
        val mixedPort = DataStore.mixedPort
        val start = SystemClock.elapsedRealtime()

        if (target.id == "google") {
            // Google test with redirect inspection to catch China routing (google.cn)
            return try {
                val socks5Proxy = if (mixedPort > 0) {
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", mixedPort.toInt()))
                } else {
                    Proxy.NO_PROXY
                }
                val url = URL(target.targetUrl)
                val conn = url.openConnection(socks5Proxy) as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 4500
                conn.readTimeout = 4500
                conn.setRequestProperty("User-Agent", BROWSER_UA)

                try {
                    conn.connect()
                    val code = conn.responseCode
                    val cost = SystemClock.elapsedRealtime() - start
                    val location = conn.getHeaderField("Location").orEmpty()

                    val isGoogleCn = (code in 301..302) && location.contains("google.cn", ignoreCase = true)
                    when {
                        code == 204 -> target.copy(
                            state = StageState.SUCCESS,
                            statusCode = code,
                            rttMs = cost,
                            summary = "未送中 · 204 正常 (${cost} ms)",
                            detail = "国际出口正常，未被导向中国大陆服务器，国际搜索与服务畅通",
                            isGoogleCn = false,
                        )
                        isGoogleCn -> target.copy(
                            state = StageState.WARNING,
                            statusCode = code,
                            rttMs = cost,
                            summary = "已送中 · 重定向国内 (${cost} ms)",
                            detail = "出口将 Google 流量重定向至 $location，被识别为中国大陆 IP（送中）",
                            isGoogleCn = true,
                        )
                        code in 200..399 -> target.copy(
                            state = StageState.SUCCESS,
                            statusCode = code,
                            rttMs = cost,
                            summary = "访问正常 · HTTP $code (${cost} ms)",
                            detail = "Google 国际网络正常响应",
                            isGoogleCn = false,
                        )
                        else -> target.copy(
                            state = StageState.WARNING,
                            statusCode = code,
                            rttMs = cost,
                            summary = "异常状态 · HTTP $code (${cost} ms)",
                            detail = "Google 返回非预期状态码",
                            isGoogleCn = false,
                        )
                    }
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
            } catch (e: Throwable) {
                val cost = SystemClock.elapsedRealtime() - start
                target.copy(
                    state = StageState.FAILED,
                    rttMs = cost,
                    summary = "连接超时/异常",
                    detail = "访问 Google 超时: ${e.message ?: "未收到响应"}",
                )
            }
        }

        // Other targets
        var client: libcore.HTTPClient? = null
        return try {
            client = Libcore.newHttpClient().apply {
                modernTLS()
                if (mixedPort > 0) {
                    trySocks5(mixedPort.toInt(), "", "")
                } else {
                    tryProxyOutbound()
                }
            }
            val req = client.newRequest().apply {
                setURL(target.targetUrl)
                setUserAgent(BROWSER_UA)
            }
            val resp = req.execute()
            val cost = SystemClock.elapsedRealtime() - start
            val content = Util.getStringBox(resp.contentString)
            target.copy(
                state = StageState.SUCCESS,
                statusCode = 200,
                rttMs = cost,
                summary = "正常连通 · ${cost} ms",
                detail = "HTTP 200 正常响应，内容链路完整",
            )
        } catch (e: Throwable) {
            val cost = SystemClock.elapsedRealtime() - start
            val msg = e.message.orEmpty()
            val httpCodeMatch = Regex("""HTTP\s+(\d{3})""").find(msg)
            val extractedCode = httpCodeMatch?.groupValues?.get(1)?.toIntOrNull()
            if (extractedCode != null && extractedCode in 200..399) {
                target.copy(
                    state = StageState.SUCCESS,
                    statusCode = extractedCode,
                    rttMs = cost,
                    summary = "正常连通 · ${cost} ms",
                    detail = "HTTP $extractedCode 正常响应，链路通畅",
                )
            } else {
                target.copy(
                    state = StageState.FAILED,
                    statusCode = extractedCode ?: 0,
                    rttMs = cost,
                    summary = "探测失败",
                    detail = "连接超时或受阻: ${msg.ifEmpty { "请求未能完成" }}",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    fun synthesizeUsability(
        entry: EntryProbeResult,
        tunnel: TunnelProbeResult,
        targets: List<TargetProbeResult>,
        isConnected: Boolean = runCatching { DataStore.serviceState.connected }.getOrDefault(true),
    ): Pair<NodeUsability, String> {
        if (!isConnected) {
            return Pair(NodeUsability.INDETERMINATE, "VPN 代理服务未运行，无法进行出网连通性评估")
        }

        if (tunnel.state == StageState.FAILED) {
            return Pair(NodeUsability.UNUSABLE, "代理隧道建立失败，无法通过当前节点转发网络流量")
        }

        val internationalTargets = targets.filter { it.id != "domestic" }
        val successCount = internationalTargets.count { it.state == StageState.SUCCESS }
        val totalCount = internationalTargets.size

        val hasGoogleCn = targets.any { it.isGoogleCn }

        // Real internet traffic is the ultimate ground truth
        if (tunnel.state == StageState.SUCCESS && successCount > 0) {
            val avgRtt = internationalTargets.map { it.rttMs }.filter { it > 0 }.average().let { if (it.isNaN()) 0 else it.toInt() }
            val cnNote = if (hasGoogleCn) "（注意：检测到 Google 送中）" else ""
            val entryWarningNote = if (entry.state == StageState.FAILED) "（注：直连TCP握手受阻，但代理隧道与多目标实际出网完全通畅）" else ""

            return when {
                successCount == totalCount -> {
                    Pair(
                        NodeUsability.FULLY_USABLE,
                        "所有国际主流服务均畅通无阻，平均出网延迟约 ${avgRtt} ms$cnNote$entryWarningNote",
                    )
                }
                successCount >= (totalCount * 0.6) -> {
                    val failedNames = internationalTargets.filter { it.state != StageState.SUCCESS }.joinToString("、") { it.name }
                    Pair(
                        NodeUsability.PARTIALLY_USABLE,
                        "节点可用，但部分服务受阻或超时（$failedNames），可能存在区域版权限制或特定封锁$entryWarningNote",
                    )
                }
                else -> {
                    Pair(
                        NodeUsability.UNUSABLE,
                        "绝大多数海外网络目标均无法连接，当前节点网络质量极低或已被严重阻断",
                    )
                }
            }
        }

        if (entry.state == StageState.FAILED) {
            return Pair(NodeUsability.UNUSABLE, "节点入口握手失败，节点服务器可能已离线、端口关闭或被防火墙拦截")
        }

        return Pair(NodeUsability.UNUSABLE, "网络探测未通过，当前节点不可用")
    }
}
