package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.modernTLS
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

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
        get() = "$countryFlag $countryCode $ip".trim()

    val locationText: String
        get() {
            val parts = mutableListOf<String>()
            if (country.isNotBlank()) parts.add(country)
            if (city.isNotBlank()) parts.add(city)
            val base = parts.joinToString(" · ")
            return if (countryCode.isNotBlank()) "$countryFlag $base ($countryCode)" else "$countryFlag $base"
        }
}

object LandingIpManager {

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

    fun countryCodeToFlagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return "🌐"
        val code = countryCode.uppercase()
        val firstChar = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    suspend fun queryLandingIp(
        profileId: Long,
        forceRefresh: Boolean = false,
    ): Result<LandingIpInfo> = withContext(Dispatchers.IO) {
        if (!forceRefresh && currentCache != null && cachedProfileId == profileId) {
            return@withContext Result.success(currentCache!!)
        }

        if (isQuerying && !forceRefresh) {
            currentCache?.let { return@withContext Result.success(it) }
        }

        isQuerying = true
        val startTime = System.currentTimeMillis()

        try {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }

            // Primary query: ip-api.com
            var info: LandingIpInfo? = null
            try {
                val req = client.newRequest().apply {
                    setURL("http://ip-api.com/json?fields=status,message,country,countryCode,regionName,city,isp,org,as,query")
                    setUserAgent(USER_AGENT)
                }
                val resp = req.execute()
                val body = Util.getStringBox(resp.contentString)
                val json = JSONObject(body)
                if (json.optString("status") == "success") {
                    val ip = json.optString("query")
                    val country = json.optString("country")
                    val countryCode = json.optString("countryCode")
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

            // Fallback: api.ip.sb
            if (info == null) {
                try {
                    val req = client.newRequest().apply {
                        setURL("https://api.ip.sb/geoip")
                        setUserAgent(USER_AGENT)
                    }
                    val resp = req.execute()
                    val body = Util.getStringBox(resp.contentString)
                    val json = JSONObject(body)
                    val ip = json.optString("ip")
                    if (ip.isNotBlank()) {
                        val country = json.optString("country")
                        val countryCode = json.optString("country_code")
                        val flag = countryCodeToFlagEmoji(countryCode)
                        val city = json.optString("city")
                        val region = json.optString("region")
                        val isp = json.optString("isp")
                        val asn = "AS${json.optInt("asn", 0)} ${json.optString("asn_organization")}".trim()
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
            isQuerying = false
        }
    }
}
