package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.util.Locale

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
) {
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

    private const val CACHE_TTL_MS = 180_000L // 3 分钟自动过期

    @Volatile
    private var currentCache: LandingIpInfo? = null

    @Volatile
    private var cachedProfileId: Long = -1L

    @Volatile
    private var isQuerying: Boolean = false

    fun clearCache() {
        currentCache = null
        cachedProfileId = -1L
    }

    fun getCachedInfo(): LandingIpInfo? = currentCache

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

    suspend fun queryLandingIp(
        profileId: Long,
        forceRefresh: Boolean = false,
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
        var client: libcore.HTTPClient? = null

        try {
            val c = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            client = c

            val ua = USER_AGENT.takeIf { it.isNotBlank() }
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

            var info: LandingIpInfo? = null

            // 1. 优先使用专为代理出站优化的高精度数据库 api.ip.sb
            try {
                val req = client.newRequest().apply {
                    setURL("https://api.ip.sb/geoip")
                    setUserAgent(ua)
                }
                val resp = req.execute()
                val body = Util.getStringBox(resp.contentString)
                val json = JSONObject(body)
                val ip = json.optString("ip")
                if (ip.isNotBlank()) {
                    val countryCode = json.optString("country_code").uppercase()
                    val rawCountry = json.optString("country")
                    val country = localizeCountry(countryCode, rawCountry)
                    val flag = countryCodeToFlagEmoji(countryCode)
                    val city = json.optString("city")
                    val region = json.optString("region")
                    val isp = json.optString("isp")
                    val asnOrg = json.optString("asn_organization")
                    val asnNum = json.optInt("asn", 0)
                    val asn = if (asnNum > 0) "AS$asnNum $asnOrg".trim() else asnOrg
                    val cost = System.currentTimeMillis() - startTime

                    info = LandingIpInfo(
                        ip = ip,
                        country = country,
                        countryCode = countryCode,
                        countryFlag = flag,
                        city = city,
                        region = region,
                        isp = isp,
                        org = json.optString("organization"),
                        asn = asn,
                        durationMs = cost,
                    )
                }
            } catch (_: Throwable) {
            }

            // 2. 备选高精度源 ipwho.is
            if (info == null) {
                try {
                    val req = client.newRequest().apply {
                        setURL("https://ipwho.is/")
                        setUserAgent(ua)
                    }
                    val resp = req.execute()
                    val body = Util.getStringBox(resp.contentString)
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val ip = json.optString("ip")
                        if (ip.isNotBlank()) {
                            val countryCode = json.optString("country_code").uppercase()
                            val rawCountry = json.optString("country")
                            val country = localizeCountry(countryCode, rawCountry)
                            val flag = countryCodeToFlagEmoji(countryCode)
                            val city = json.optString("city")
                            val region = json.optString("region")
                            val conn = json.optJSONObject("connection")
                            val isp = conn?.optString("isp").orEmpty()
                            val org = conn?.optString("org").orEmpty()
                            val asnNum = conn?.optInt("asn", 0) ?: 0
                            val asn = if (asnNum > 0) "AS$asnNum $org".trim() else org
                            val cost = System.currentTimeMillis() - startTime

                            info = LandingIpInfo(
                                ip = ip,
                                country = country,
                                countryCode = countryCode,
                                countryFlag = flag,
                                city = city,
                                region = region,
                                isp = isp,
                                org = org,
                                asn = asn,
                                durationMs = cost,
                            )
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // 3. 权威物理边缘出口校验 cloudflare.com/cdn-cgi/trace
            if (info == null) {
                try {
                    val req = client.newRequest().apply {
                        setURL("https://cloudflare.com/cdn-cgi/trace")
                        setUserAgent(ua)
                    }
                    val resp = req.execute()
                    val body = Util.getStringBox(resp.contentString)
                    var cfIp = ""
                    var cfLoc = ""
                    var cfColo = ""
                    for (line in body.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("ip=")) cfIp = trimmed.substring(3).trim()
                        else if (trimmed.startsWith("loc=")) cfLoc = trimmed.substring(4).trim().uppercase()
                        else if (trimmed.startsWith("colo=")) cfColo = trimmed.substring(5).trim().uppercase()
                    }
                    if (cfIp.isNotBlank()) {
                        val countryCode = cfLoc
                        val flag = countryCodeToFlagEmoji(countryCode)
                        val country = localizeCountry(countryCode, countryCode)
                        val cost = System.currentTimeMillis() - startTime

                        info = LandingIpInfo(
                            ip = cfIp,
                            country = country,
                            countryCode = countryCode,
                            countryFlag = flag,
                            city = if (cfColo.isNotBlank()) "Cloudflare ($cfColo)" else "",
                            region = "",
                            isp = "Cloudflare Edge",
                            org = "Cloudflare Anycast",
                            asn = if (cfColo.isNotBlank()) "Cloudflare $cfColo" else "Cloudflare",
                            durationMs = cost,
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            // 4. 兜底旧接口 ip-api.com
            if (info == null) {
                try {
                    val req = client.newRequest().apply {
                        setURL("http://ip-api.com/json?fields=status,message,country,countryCode,regionName,city,isp,org,as,query")
                        setUserAgent(ua)
                    }
                    val resp = req.execute()
                    val body = Util.getStringBox(resp.contentString)
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        val ip = json.optString("query")
                        val countryCode = json.optString("countryCode").uppercase()
                        val rawCountry = json.optString("country")
                        val country = localizeCountry(countryCode, rawCountry)
                        val flag = countryCodeToFlagEmoji(countryCode)
                        val city = json.optString("city")
                        val region = json.optString("regionName")
                        val isp = json.optString("isp")
                        val org = json.optString("org")
                        val asn = json.optString("as")
                        val cost = System.currentTimeMillis() - startTime

                        info = LandingIpInfo(
                            ip = ip,
                            country = country,
                            countryCode = countryCode,
                            countryFlag = flag,
                            city = city,
                            region = region,
                            isp = isp,
                            org = org,
                            asn = asn,
                            durationMs = cost,
                        )
                    }
                } catch (_: Throwable) {
                }
            }

            if (info != null) {
                currentCache = info
                cachedProfileId = profileId
                Result.success(info)
            } else {
                Result.failure(Exception("无法获取落地 IP 信息"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            runCatching { client?.close() }
            isQuerying = false
        }
    }
}
