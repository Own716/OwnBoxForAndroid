package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.util.Locale

enum class IpRiskLevel(val label: String, val colorHex: String) {
    CLEAN("极低风险", "#10B981"),
    LOW("低风险", "#3B82F6"),
    MEDIUM("中度风险", "#F59E0B"),
    HIGH("高风险", "#EF4444")
}

data class LandingIpInfo(
    val ip: String,
    val country: String,
    val countryCode: String,
    val countryFlag: String,
    val city: String,
    val region: String,
    val isp: String,
    val org: String,
    val asn: String,
    val durationMs: Long,
    val queryTimestamp: Long = System.currentTimeMillis(),
    val ipVersion: String = if (ip.contains(":")) "IPv6" else "IPv4",
    val levelLabel: String = "普通", // 优 / 普通 / 风险
    val typeLabel: String = "机房",  // 住宅 / 机房 / 商业 / 移动网络
    val hitTag: String = "无",       // 无 / VPN / 代理 / Tor
    val source: String = "iplocate.io",
    val riskLevel: IpRiskLevel = if (levelLabel == "优") IpRiskLevel.CLEAN else if (levelLabel == "风险") IpRiskLevel.HIGH else IpRiskLevel.LOW,
    val riskScore: Int = if (levelLabel == "优") 10 else if (levelLabel == "风险") 85 else 45,
    val networkType: String = typeLabel,
    val threatTags: List<String> = if (hitTag == "无") listOf(typeLabel) else listOf(hitTag),
    val isProxy: Boolean = false,
    val isVpn: Boolean = false,
    val isTor: Boolean = false,
    val isHosting: Boolean = (typeLabel == "机房"),
) {
    fun getMaskedIp(): String {
        return if (ip.contains(":")) {
            val parts = ip.split(":")
            if (parts.size >= 2) "${parts[0]}:${parts[1]}:****:****" else ip
        } else {
            val parts = ip.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}.*.*" else ip
        }
    }

    val briefText: String
        get() = if (durationMs > 0) {
            "$countryFlag $countryCode $ip · HTTP 握手 ${durationMs} 毫秒".trim()
        } else {
            "$countryFlag $countryCode $ip".trim()
        }

    val locationText: String
        get() {
            val parts = mutableListOf<String>()
            if (country.isNotBlank()) parts.add(country)
            if (city.isNotBlank() && city != country) parts.add(city)
            val base = parts.joinToString(" · ")
            return if (countryCode.isNotBlank()) "$countryFlag $base ($countryCode)" else "$countryFlag $base"
        }
}

object LandingIpManager {

    private const val CACHE_TTL_MS = 60_000L

    @Volatile
    private var currentCache: LandingIpInfo? = null

    @Volatile
    var cachedProfileId: Long = -1L
        private set

    @Volatile
    private var isQuerying: Boolean = false

    fun clearCache() {
        currentCache = null
        cachedProfileId = -1L
    }

    fun getCachedInfo(): LandingIpInfo? = currentCache

    fun isCurrentlyQuerying(): Boolean = isQuerying

    fun updateCachedDuration(duration: Long) {
        currentCache = currentCache?.copy(durationMs = duration)
    }

    fun countryCodeToFlagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return "🌐"
        val code = countryCode.uppercase()
        if (!code[0].isLetter() || !code[1].isLetter()) return "🌐"
        val firstChar = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    private fun localizeCountry(countryCode: String, fallbackName: String): String {
        if (countryCode.length != 2) return fallbackName
        return runCatching {
            Locale("", countryCode.uppercase()).getDisplayCountry(Locale.getDefault()).takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallbackName
    }

    fun parseProfileRegion(name: String): Pair<String, String> {
        if (name.isBlank()) return Pair("🌐", "节点出口")
        for (i in 0 until name.length - 1) {
            val cp1 = name.codePointAt(i)
            if (cp1 in 0x1F1E6..0x1F1FF) {
                val charCount1 = Character.charCount(cp1)
                if (i + charCount1 < name.length) {
                    val cp2 = name.codePointAt(i + charCount1)
                    if (cp2 in 0x1F1E6..0x1F1FF) {
                        val flag = String(Character.toChars(cp1)) + String(Character.toChars(cp2))
                        val c1 = (cp1 - 0x1F1E6 + 'A'.code).toChar()
                        val c2 = (cp2 - 0x1F1E6 + 'A'.code).toChar()
                        val code = "$c1$c2"
                        return Pair(flag, code)
                    }
                }
            }
        }
        val lower = name.lowercase()
        val hasWord: (String) -> Boolean = { word ->
            Regex("(?i)(^|[^a-z0-9])${Regex.escape(word)}([^a-z0-9]|$)").containsMatchIn(lower)
        }
        return when {
            lower.contains("香港") || lower.contains("hong kong") || hasWord("hk") -> Pair("🇭🇰", "HK")
            lower.contains("日本") || lower.contains("japan") || lower.contains("东京") || lower.contains("大阪") || hasWord("jp") -> Pair("🇯🇵", "JP")
            lower.contains("美国") || lower.contains("united states") || lower.contains("洛杉矶") || lower.contains("硅谷") || lower.contains("纽约") || hasWord("us") || hasWord("usa") -> Pair("🇺🇸", "US")
            lower.contains("台湾") || lower.contains("taiwan") || lower.contains("台北") || hasWord("tw") -> Pair("🇹🇼", "TW")
            lower.contains("新加坡") || lower.contains("singapore") || lower.contains("狮城") || hasWord("sg") -> Pair("🇸🇬", "SG")
            lower.contains("韩国") || lower.contains("korea") || lower.contains("首尔") || hasWord("kr") -> Pair("🇰🇷", "KR")
            lower.contains("英国") || lower.contains("united kingdom") || lower.contains("伦敦") || hasWord("uk") || hasWord("gb") -> Pair("🇬🇧", "GB")
            lower.contains("德国") || lower.contains("germany") || lower.contains("法兰克福") || hasWord("de") -> Pair("🇩🇪", "DE")
            lower.contains("法国") || lower.contains("france") || lower.contains("巴黎") || hasWord("fr") -> Pair("🇫🇷", "FR")
            lower.contains("加拿大") || lower.contains("canada") || hasWord("ca") -> Pair("🇨🇦", "CA")
            lower.contains("澳大利亚") || lower.contains("australia") || lower.contains("悉尼") || hasWord("au") -> Pair("🇦🇺", "AU")
            lower.contains("俄罗斯") || lower.contains("russia") || lower.contains("莫斯科") || hasWord("ru") -> Pair("🇷🇺", "RU")
            lower.contains("土耳其") || lower.contains("turkey") || hasWord("tr") -> Pair("🇹🇷", "TR")
            lower.contains("阿根廷") || lower.contains("argentina") || hasWord("ar") -> Pair("🇦🇷", "AR")
            lower.contains("印度") || lower.contains("india") || hasWord("in") -> Pair("🇮🇳", "IN")
            lower.contains("中国") || lower.contains("china") || hasWord("cn") -> Pair("🇨🇳", "CN")
            else -> Pair("🌐", "节点出口")
        }
    }

    fun getProfileFallbackDisplay(profileId: Long): String {
        val profile = runCatching {
            io.nekohasekai.sagernet.database.ProfileManager.getProfile(profileId)
        }.getOrNull()
        val name = profile?.displayName().orEmpty()
        val (flag, region) = parseProfileRegion(name)
        return "$flag $region (点击重试)"
    }

    private fun createHttpClient(): libcore.HTTPClient {
        return Libcore.newHttpClient().apply {
            modernTLS()
            val mixedPort = DataStore.mixedPort
            if (mixedPort > 0) {
                trySocks5(mixedPort.toInt(), "", "")
            } else {
                tryProxyOutbound()
            }
        }
    }

    // 第一步：快速解析真实出口 IP 地址（多源快速嗅探）
    private fun fetchRawEgressIp(ua: String): Pair<String, String>? {
        // 1. 优先尝试 Cloudflare Anycast trace（全球边缘节点，100ms 极速响应）
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://cloudflare.com/cdn-cgi/trace")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            var ip = ""
            var loc = ""
            for (line in body.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("ip=")) ip = trimmed.substring(3).trim()
                else if (trimmed.startsWith("loc=")) loc = trimmed.substring(4).trim().uppercase()
            }
            if (ip.isNotBlank()) {
                return Pair(ip, loc)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client?.close() }
        }

        // 2. 备选尝试 ip.sb
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://api.ip.sb/geoip")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            val ip = json.optString("ip").trim()
            if (ip.isNotBlank()) {
                val cc = json.optString("country_code").uppercase()
                return Pair(ip, cc)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client?.close() }
        }

        // 3. 备选尝试 ident.me (JSON)
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://ident.me/json")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            val ip = json.optString("ip").trim()
            if (ip.isNotBlank()) {
                val cc = json.optString("country_code", json.optString("cc")).uppercase()
                return Pair(ip, cc)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client?.close() }
        }

        return null
    }

    // 第二步：向 iplocate.io 查询 IP 质量与详细信息（对齐 FlClash _fromIpLocate）
    private fun fetchFromIpLocate(rawIp: String, fallbackCc: String, ua: String, startTime: Long): LandingIpInfo? {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://iplocate.io/api/lookup/$rawIp")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            val ip = json.optString("ip", rawIp).trim()
            if (ip.isBlank()) return null

            val privacy = json.optJSONObject("privacy")
            val company = json.optJSONObject("company")
            val asnObj = json.optJSONObject("asn")

            val isHosting = privacy?.optBoolean("is_hosting", false) ?: false
            val isProxy = privacy?.optBoolean("is_proxy", false) ?: false
            val isVpn = privacy?.optBoolean("is_vpn", false) ?: false
            val isTor = privacy?.optBoolean("is_tor", false) ?: false
            val isAbuser = privacy?.optBoolean("is_abuser", false) ?: false

            val companyName = company?.optString("name").orEmpty()
            val asnName = asnObj?.optString("name").orEmpty()
            val org = companyName.ifBlank { asnName }.ifBlank { "未知" }

            val asnNum = asnObj?.optInt("asn", 0) ?: 0
            val asn = if (asnNum > 0) "AS$asnNum" else asnObj?.optString("asn").orEmpty()

            val declaredType = company?.optString("type").orEmpty().ifBlank { asnObj?.optString("type").orEmpty() }
            val typeLabel = when {
                isHosting || declaredType.equals("hosting", ignoreCase = true) -> "机房"
                declaredType.equals("business", ignoreCase = true) -> "商业"
                declaredType.equals("mobile", ignoreCase = true) -> "移动网络"
                else -> "住宅"
            }

            // 对齐 FlClash 等级算法：住宅/移动/商业 -> 优；机房 -> 普通；Tor/Abuser -> 风险
            val levelLabel = when {
                isTor || isAbuser -> "风险"
                typeLabel == "机房" -> "普通"
                else -> "优"
            }

            val hitTags = mutableListOf<String>()
            if (isTor) hitTags.add("Tor")
            if (isAbuser) hitTags.add("异常行为")
            if (isVpn) hitTags.add("VPN")
            if (isProxy) hitTags.add("代理")
            val hitTagLabel = if (hitTags.isEmpty()) "无" else hitTags.joinToString(" / ")

            val countryCode = json.optString("country_code", fallbackCc).uppercase()
            val rawCountry = json.optString("country").ifBlank { countryCode }
            val country = localizeCountry(countryCode, rawCountry)
            val flag = countryCodeToFlagEmoji(countryCode)
            val city = json.optString("city").orEmpty()
            val state = json.optString("subdivision").orEmpty()

            val cost = System.currentTimeMillis() - startTime

            return LandingIpInfo(
                ip = ip,
                country = country,
                countryCode = countryCode,
                countryFlag = flag,
                city = city,
                region = state,
                isp = org,
                org = org,
                asn = asn,
                durationMs = cost,
                levelLabel = levelLabel,
                typeLabel = typeLabel,
                hitTag = hitTagLabel,
                source = "iplocate.io",
                riskLevel = if (levelLabel == "优") IpRiskLevel.CLEAN else if (levelLabel == "风险") IpRiskLevel.HIGH else IpRiskLevel.LOW,
                riskScore = if (levelLabel == "优") 10 else if (levelLabel == "风险") 85 else 45,
                networkType = typeLabel,
                threatTags = if (hitTagLabel == "无") listOf(typeLabel) else hitTags,
                isProxy = isProxy,
                isVpn = isVpn,
                isTor = isTor,
                isHosting = (typeLabel == "机房"),
            )
        } catch (_: Throwable) {
        } finally {
            runCatching { client?.close() }
        }
        return null
    }

    // 备选第二步：向 ip-api.com 查询 IP 质量与归属（对齐 FlClash _fromIpApiCom）
    private fun fetchFromIpApiCom(rawIp: String, fallbackCc: String, ua: String, startTime: Long): LandingIpInfo? {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("http://ip-api.com/json/$rawIp?fields=status,country,countryCode,regionName,city,isp,org,as,mobile,proxy,hosting")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            if (json.optString("status") == "success") {
                val isHosting = json.optBoolean("hosting", false)
                val isMobile = json.optBoolean("mobile", false)
                val isProxy = json.optBoolean("proxy", false)

                val typeLabel = when {
                    isHosting -> "机房"
                    isMobile -> "移动网络"
                    else -> "住宅"
                }

                val levelLabel = when {
                    typeLabel == "机房" -> "普通"
                    else -> "优"
                }

                val hitTags = mutableListOf<String>()
                if (isProxy) hitTags.add("代理")
                val hitTagLabel = if (hitTags.isEmpty()) "无" else hitTags.joinToString(" / ")

                val org = json.optString("org", json.optString("isp")).ifBlank { "未知" }
                val asRaw = json.optString("as").trim()
                val asn = if (asRaw.startsWith("AS")) asRaw.split(" ").firstOrNull() ?: asRaw else asRaw

                val countryCode = json.optString("countryCode", fallbackCc).uppercase()
                val rawCountry = json.optString("country").ifBlank { countryCode }
                val country = localizeCountry(countryCode, rawCountry)
                val flag = countryCodeToFlagEmoji(countryCode)
                val city = json.optString("city").orEmpty()
                val state = json.optString("regionName").orEmpty()

                val cost = System.currentTimeMillis() - startTime

                return LandingIpInfo(
                    ip = rawIp,
                    country = country,
                    countryCode = countryCode,
                    countryFlag = flag,
                    city = city,
                    region = state,
                    isp = org,
                    org = org,
                    asn = asn,
                    durationMs = cost,
                    levelLabel = levelLabel,
                    typeLabel = typeLabel,
                    hitTag = hitTagLabel,
                    source = "ip-api.com",
                    riskLevel = if (levelLabel == "优") IpRiskLevel.CLEAN else IpRiskLevel.LOW,
                    riskScore = if (levelLabel == "优") 10 else 45,
                    networkType = typeLabel,
                    threatTags = if (hitTagLabel == "无") listOf(typeLabel) else hitTags,
                    isProxy = isProxy,
                    isVpn = false,
                    isTor = false,
                    isHosting = isHosting,
                )
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { client?.close() }
        }
        return null
    }

    // 备选第三步：向 api.ipquery.io 查询 IP 质量与归属（对齐 FlClash _fromIpQuery）
    private fun fetchFromIpQueryIo(rawIp: String, fallbackCc: String, ua: String, startTime: Long): LandingIpInfo? {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://api.ipquery.io/$rawIp?format=json")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            val ip = json.optString("ip", rawIp).trim()
            if (ip.isBlank()) return null

            val ispObj = json.optJSONObject("isp")
            val asn = ispObj?.optString("asn").orEmpty()
            val org = ispObj?.optString("org", ispObj.optString("isp")).orEmpty().ifBlank { "未知" }

            val locObj = json.optJSONObject("location")
            val countryCode = locObj?.optString("country_code", fallbackCc).orEmpty().uppercase()
            val rawCountry = locObj?.optString("country").orEmpty()
            val country = localizeCountry(countryCode, rawCountry)
            val flag = countryCodeToFlagEmoji(countryCode)
            val city = locObj?.optString("city").orEmpty()
            val state = locObj?.optString("state").orEmpty()

            val riskObj = json.optJSONObject("risk")
            val isDatacenter = riskObj?.optBoolean("is_datacenter", false) ?: false
            val isVpn = riskObj?.optBoolean("is_vpn", false) ?: false
            val isTor = riskObj?.optBoolean("is_tor", false) ?: false
            val isProxy = riskObj?.optBoolean("is_proxy", false) ?: false
            val isMobile = riskObj?.optBoolean("is_mobile", false) ?: false

            val typeLabel = when {
                isMobile -> "移动网络"
                isDatacenter -> "机房"
                else -> "住宅"
            }

            val levelLabel = when {
                isTor -> "风险"
                typeLabel == "机房" -> "普通"
                else -> "优"
            }

            val hitTags = mutableListOf<String>()
            if (isTor) hitTags.add("Tor")
            if (isVpn) hitTags.add("VPN")
            if (isProxy) hitTags.add("代理")
            val hitTagLabel = if (hitTags.isEmpty()) "无" else hitTags.joinToString(" / ")

            val cost = System.currentTimeMillis() - startTime

            return LandingIpInfo(
                ip = ip,
                country = country,
                countryCode = countryCode,
                countryFlag = flag,
                city = city,
                region = state,
                isp = org,
                org = org,
                asn = asn,
                durationMs = cost,
                levelLabel = levelLabel,
                typeLabel = typeLabel,
                hitTag = hitTagLabel,
                source = "ipquery.io",
                riskLevel = if (levelLabel == "优") IpRiskLevel.CLEAN else if (levelLabel == "风险") IpRiskLevel.HIGH else IpRiskLevel.LOW,
                riskScore = if (levelLabel == "优") 10 else if (levelLabel == "风险") 85 else 45,
                networkType = typeLabel,
                threatTags = if (hitTagLabel == "无") listOf(typeLabel) else hitTags,
                isProxy = isProxy,
                isVpn = isVpn,
                isTor = isTor,
                isHosting = isDatacenter,
            )
        } catch (_: Throwable) {
        } finally {
            runCatching { client?.close() }
        }
        return null
    }

    suspend fun queryLandingIp(
        profileId: Long,
        forceRefresh: Boolean = false,
        onUpdate: ((LandingIpInfo) -> Unit)? = null,
    ): Result<LandingIpInfo> = withContext(Dispatchers.IO) {
        if (!DataStore.serviceState.connected) {
            return@withContext Result.failure(IllegalStateException("VPN not connected"))
        }

        val now = System.currentTimeMillis()
        if (forceRefresh) {
            currentCache = null
            cachedProfileId = -1L
        } else {
            val cache = currentCache
            if (cache != null && cachedProfileId == profileId && (now - cache.queryTimestamp < CACHE_TTL_MS)) {
                return@withContext Result.success(cache)
            }
        }

        if (isQuerying && !forceRefresh) {
            currentCache?.let { return@withContext Result.success(it) }
        }

        isQuerying = true
        val startTime = System.currentTimeMillis()
        val ua = USER_AGENT.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        try {
            // 第一步：先快速拿到真实出口 IP 字符串与国家（优先 Anycast 毫秒响应）
            val ipPair = withTimeoutOrNull(2000L) { fetchRawEgressIp(ua) }
            val rawIp = ipPair?.first.orEmpty()
            val rawCc = ipPair?.second.orEmpty()

            if (rawIp.isBlank()) {
                return@withContext Result.failure(Exception("无法连接或获取出口 IP"))
            }

            // 第二步：使用真实 IP 执行多梯队质量查询（优先 iplocate.io 对齐 FlClash，超时快速切入 ip-api.com）
            var info = withTimeoutOrNull(1200L) { fetchFromIpLocate(rawIp, rawCc, ua, startTime) }

            // 备用第二梯队：ip-api.com（毫秒级极速通道）
            if (info == null) {
                info = withTimeoutOrNull(1500L) { fetchFromIpApiCom(rawIp, rawCc, ua, startTime) }
            }

            // 备用第三梯队：api.ipquery.io
            if (info == null) {
                info = withTimeoutOrNull(1500L) { fetchFromIpQueryIo(rawIp, rawCc, ua, startTime) }
            }

            // 终极保护：如果所有第三方智库均因网络受阻，绝不硬编码假数据（如 Cloudflare, Inc.），仅返回已知真实 IP
            if (info == null) {
                val flag = countryCodeToFlagEmoji(rawCc)
                val country = localizeCountry(rawCc, rawCc)
                val cost = System.currentTimeMillis() - startTime
                info = LandingIpInfo(
                    ip = rawIp,
                    country = country,
                    countryCode = rawCc,
                    countryFlag = flag,
                    city = "",
                    region = "",
                    isp = "未知",
                    org = "未知",
                    asn = "未知",
                    durationMs = cost,
                    levelLabel = "普通",
                    typeLabel = "住宅",
                    hitTag = "无",
                    source = "ident.me",
                    riskLevel = IpRiskLevel.LOW,
                    riskScore = 20,
                    networkType = "住宅",
                    threatTags = listOf("住宅"),
                )
            }

            currentCache = info
            cachedProfileId = profileId
            onUpdate?.invoke(info)
            Result.success(info)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            isQuerying = false
        }
    }
}
