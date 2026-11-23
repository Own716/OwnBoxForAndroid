package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.shouldUseFakeDns
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class LeakVerdict {
    SAFE,
    LEAK_RISK,
    DIRECT_NOT_RUNNING,
    INCONCLUSIVE,
}

data class DnsResolverInfo(
    val ip: String,
    val geo: String,
)

data class FakeIpProbeResult(
    val supportedInMode: Boolean,
    val enabledInSettings: Boolean,
    val active: Boolean,
    val resolvedIps: List<String> = emptyList(),
    val note: String = "",
)

data class DnsLeakReport(
    val serviceRunning: Boolean,
    val serviceMode: String,
    val fakeIp: FakeIpProbeResult,
    val egressIp: String? = null,
    val egressLoc: String? = null,
    val proxyDns: DnsResolverInfo? = null,
    val systemDns: DnsResolverInfo? = null,
    val verdict: LeakVerdict,
)

/**
 * Performs real Fake-IP and DNS leak diagnostics adapted to the active OwnBox service mode.
 */
object DnsLeakTester {

    private const val PROBE_TIMEOUT_MS = 8_000L

    private val EXCLUDED_GREATER_CHINA_KEYWORDS = listOf(
        "hong kong",
        "hongkong",
        "macau",
        "macao",
        "taiwan",
    )

    private val DOMESTIC_CHINA_DNS_KEYWORDS = listOf(
        "china",
        "chinanet",
        "china telecom",
        "china unicom",
        "china mobile",
        "cmnet",
        "unicom",
        "alibaba",
        "aliyun",
        "alidns",
        "tencent",
        "dnspod",
        "cnnic",
        "cernet",
        "114dns",
        "baidu",
        "huawei cloud",
        "beijing",
        "shanghai",
        "guangzhou",
        "shenzhen",
        "hangzhou",
    )

    private val CN_CODE_REGEX = Regex("(?i)(?:^|[^a-z])cn(?:$|[^a-z])")

    /**
     * Checks whether [ip] belongs to sing-box's configured Fake-IP ranges (`198.18.0.0/15` or `fc00::/18`).
     */
    fun isFakeIpAddress(ip: String): Boolean {
        val cleaned = ip.trim().removePrefix("[").substringBefore("]")
        if (cleaned.isBlank()) return false
        val addr = runCatching { InetAddress.getByName(cleaned) }.getOrNull() ?: return false
        val bytes = addr.address
        return when {
            addr is Inet4Address && bytes.size == 4 -> {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                b0 == 198 && (b1 == 18 || b1 == 19)
            }
            addr is Inet6Address && bytes.size == 16 -> {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                val b2 = bytes[2].toInt() and 0xFF
                // fc00::/18 => first 18 bits: 0xFC, 0x00, top 2 bits of b2 == 0
                b0 == 0xFC && b1 == 0x00 && (b2 and 0xC0) == 0
            }
            else -> false
        }
    }

    /**
     * Parses Cloudflare `/cdn-cgi/trace` key-value lines into `(ip, COUNTRY_CODE)`.
     */
    fun parseCloudflareTrace(body: String): Pair<String, String>? {
        if (body.isBlank()) return null
        var ip = ""
        var loc = ""
        for (line in body.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("ip=") -> ip = trimmed.substring(3).trim()
                trimmed.startsWith("loc=") -> loc = trimmed.substring(4).trim().uppercase()
            }
        }
        return if (ip.isNotBlank()) ip to loc else null
    }

    /**
     * Parses `https://<32hex>.edns.ip-api.com/json` output into [DnsResolverInfo].
     */
    fun parseEdnsIpApiResponse(body: String): DnsResolverInfo? {
        if (body.isBlank()) return null
        return runCatching {
            val dns = JSONObject(body).optJSONObject("dns") ?: return@runCatching null
            val ip = dns.optString("ip").trim()
            if (ip.isBlank()) return@runCatching null
            DnsResolverInfo(ip = ip, geo = dns.optString("geo").trim())
        }.getOrNull()
    }

    /**
     * Returns true when [geo] describes a Mainland China resolver (excluding HK/MO/TW).
     */
    fun isDomesticChinaDns(geo: String): Boolean {
        val lower = geo.trim().lowercase()
        if (lower.isBlank()) return false
        if (EXCLUDED_GREATER_CHINA_KEYWORDS.any { it in lower }) {
            return false
        }
        return DOMESTIC_CHINA_DNS_KEYWORDS.any { it in lower } || CN_CODE_REGEX.containsMatchIn(lower)
    }

    /**
     * Evaluates the overall DNS leak verdict from probe results without fabricating "safe" on failure.
     */
    fun evaluateLeakVerdict(
        serviceRunning: Boolean,
        proxyEgressLoc: String?,
        proxyDns: DnsResolverInfo?,
        systemDns: DnsResolverInfo?,
    ): LeakVerdict {
        if (!serviceRunning) return LeakVerdict.DIRECT_NOT_RUNNING
        val resolvers = listOfNotNull(systemDns, proxyDns)
        if (resolvers.isEmpty()) return LeakVerdict.INCONCLUSIVE

        val egressIsChina = proxyEgressLoc.equals("CN", ignoreCase = true)
        val systemLeaked = systemDns != null && isDomesticChinaDns(systemDns.geo)
        val proxyDnsIsChina = proxyDns != null && isDomesticChinaDns(proxyDns.geo)

        if (!egressIsChina && (systemLeaked || proxyDnsIsChina)) {
            return LeakVerdict.LEAK_RISK
        }
        if (egressIsChina && systemLeaked && proxyDns != null && !proxyDnsIsChina) {
            return LeakVerdict.LEAK_RISK
        }
        return LeakVerdict.SAFE
    }

    fun modeDisplayName(serviceMode: String): String = when (serviceMode) {
        Key.MODE_VPN -> "VPN 模式"
        Key.MODE_PROXY -> "仅代理模式"
        else -> serviceMode
    }

    /**
     * Formats [report] into a concise, plain-text Chinese diagnostic summary.
     */
    fun formatReport(report: DnsLeakReport): String {
        val sb = StringBuilder()
        val stateLabel = if (report.serviceRunning) "已连接" else "未启动"
        sb.append("- 运行状态: ").append(stateLabel).append(" (").append(modeDisplayName(report.serviceMode)).append(")\n")

        sb.append("- Fake-IP 状态: ")
        when {
            !report.serviceRunning -> sb.append("代理未启动，未启用")
            report.serviceMode == Key.MODE_PROXY -> sb.append("仅代理模式无 TUN 虚拟网卡，不使用 Fake-IP")
            !report.fakeIp.enabledInSettings -> sb.append("设置中未开启 FakeDNS")
            report.fakeIp.active -> sb.append("已生效 (${report.fakeIp.resolvedIps.joinToString(", ")})")
            else -> {
                val detail = report.fakeIp.resolvedIps.ifEmpty {
                    listOf(report.fakeIp.note.ifBlank { "未返回结果" })
                }.joinToString(", ")
                sb.append("未命中 Fake-IP 网段 ($detail)")
            }
        }
        sb.append('\n')

        sb.append("- 代理出口 IP: ")
        if (!report.egressIp.isNullOrBlank()) {
            val locSuffix = report.egressLoc?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            sb.append(report.egressIp).append(locSuffix)
        } else {
            sb.append("获取超时或不可达")
        }
        sb.append('\n')

        sb.append("- 代理隧道 DNS 出口: ")
        if (report.proxyDns != null) {
            val geo = report.proxyDns.geo.ifBlank { "归属地未知" }
            sb.append(report.proxyDns.ip).append(" (").append(geo).append(")")
        } else {
            sb.append("探测超时或不可达")
        }
        sb.append('\n')

        if (report.systemDns != null) {
            val geo = report.systemDns.geo.ifBlank { "归属地未知" }
            sb.append("- VPN 系统链路 DNS 出口: ").append(report.systemDns.ip).append(" (").append(geo).append(")\n")
        }

        sb.append("- 检测结论: ")
        when (report.verdict) {
            LeakVerdict.SAFE -> sb.append("未发现 DNS 泄漏（DNS 解析与代理出口链路正常）")
            LeakVerdict.LEAK_RISK -> sb.append("存在 DNS 泄漏风险！部分 DNS 请求仍由中国大陆服务器解析。建议检查「DNS 设置 -> 远程 DNS」是否误填了 local 或国内 DNS，并在手机系统设置中关闭国内「私人 DNS」。")
            LeakVerdict.DIRECT_NOT_RUNNING -> sb.append("当前代理未运行，以上为本机直连网络结果，请先启动代理后再检测。")
            LeakVerdict.INCONCLUSIVE -> sb.append("探测超时或网络不可达，无法确认 DNS 归属，请检查节点连通性后重试。")
        }
        return sb.toString().trim()
    }

    suspend fun runFullCheck(): DnsLeakReport = withContext(Dispatchers.IO) {
        val serviceRunning = DataStore.serviceState.started
        val serviceMode = DataStore.serviceMode
        val fakeDnsEnabled = DataStore.enableFakeDns
        val supportsFakeIp = serviceRunning && shouldUseFakeDns(
            enableFakeDns = fakeDnsEnabled,
            forTest = false,
            serviceMode = serviceMode,
            proxyApps = DataStore.proxyApps,
            bypass = DataStore.bypass,
        )

        val fakeIpResult = if (supportsFakeIp) {
            probeFakeIp()
        } else {
            FakeIpProbeResult(
                supportedInMode = serviceMode == Key.MODE_VPN,
                enabledInSettings = fakeDnsEnabled,
                active = false,
            )
        }

        val egress = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            fetchEgressInfo(useProxy = serviceRunning)
        }
        val proxyDns = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            fetchEdnsInfo(useProxy = serviceRunning)
        }
        val systemDns = if (serviceRunning && serviceMode == Key.MODE_VPN) {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                fetchEdnsInfo(useProxy = false)
            }
        } else {
            null
        }

        val verdict = evaluateLeakVerdict(
            serviceRunning = serviceRunning,
            proxyEgressLoc = egress?.second,
            proxyDns = proxyDns,
            systemDns = systemDns,
        )

        DnsLeakReport(
            serviceRunning = serviceRunning,
            serviceMode = serviceMode,
            fakeIp = fakeIpResult,
            egressIp = egress?.first,
            egressLoc = egress?.second,
            proxyDns = proxyDns,
            systemDns = systemDns,
            verdict = verdict,
        )
    }

    private fun probeFakeIp(): FakeIpProbeResult {
        val nonce = UUID.randomUUID().toString().replace("-", "").take(12)
        val hostsToTry = listOf("ownbox-$nonce.google.com", "www.google.com")
        var lastError = ""
        for (host in hostsToTry) {
            try {
                val ips = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
                if (ips.isNotEmpty()) {
                    return FakeIpProbeResult(
                        supportedInMode = true,
                        enabledInSettings = true,
                        active = ips.any(::isFakeIpAddress),
                        resolvedIps = ips,
                    )
                }
            } catch (t: Throwable) {
                lastError = t.message.orEmpty()
            }
        }
        return FakeIpProbeResult(
            supportedInMode = true,
            enabledInSettings = true,
            active = false,
            note = lastError.ifBlank { "解析失败" },
        )
    }

    private fun fetchEgressInfo(useProxy: Boolean): Pair<String, String>? {
        executeGet("https://cloudflare.com/cdn-cgi/trace", useProxy)?.let { body ->
            parseCloudflareTrace(body)?.let { return it }
        }
        executeGet("https://api.ip.sb/geoip", useProxy)?.let { body ->
            val parsed = runCatching {
                val json = JSONObject(body)
                val ip = json.optString("ip").trim()
                val cc = json.optString("country_code").trim().uppercase()
                if (ip.isNotBlank()) ip to cc else null
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun fetchEdnsInfo(useProxy: Boolean): DnsResolverInfo? {
        val hex32 = UUID.randomUUID().toString().replace("-", "")
        val httpsBody = executeGet("https://$hex32.edns.ip-api.com/json", useProxy)
        parseEdnsIpApiResponse(httpsBody.orEmpty())?.let { return it }

        if (useProxy) {
            val httpHex32 = UUID.randomUUID().toString().replace("-", "")
            val httpBody = executeGet("http://$httpHex32.edns.ip-api.com/json", useProxy = true)
            parseEdnsIpApiResponse(httpBody.orEmpty())?.let { return it }
        }
        return null
    }

    private fun executeGet(url: String, useProxy: Boolean): String? {
        if (useProxy) {
            var client: libcore.HTTPClient? = null
            return try {
                client = Libcore.newHttpClient().apply {
                    modernTLS()
                    tryProxyOutbound()
                }
                val resp = client.newRequest().apply {
                    setURL(url)
                }.execute()
                Util.getStringBox(resp.contentString)
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { client?.close() }
            }
        }

        val okClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).build()
        return runCatching {
            okClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }
}
