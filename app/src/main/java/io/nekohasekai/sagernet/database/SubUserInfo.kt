package io.nekohasekai.sagernet.database

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONObject

@Parcelize
data class SubUserInfo(
    var valid: Boolean = false,
    var hasQuota: Boolean = false,
    var upload: Long = 0L,
    var download: Long = 0L,
    /** 0 with [hasQuota] = unlimited. */
    var total: Long = 0L,
    /** Epoch seconds, 0 = none. */
    var expire: Long = 0L,
    var title: String = "",
    var webUrl: String = "",
    var supportUrl: String = "",
    var announce: String = "",
    /** profile-update-interval in hours, 0 = none. */
    var serverInterval: Int = 0,
) : Parcelable {

    val used: Long get() = upload + download

    val remaining: Long get() = if (total > used) total - used else 0L

    val percentUsed: Double
        get() = if (total <= 0) 0.0 else (used.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)

    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = expire in 1 until nowSeconds

    fun toJson(): JSONObject {
        val json = JSONObject()
        if (!valid) return json
        json.put("valid", true)
        json.put("has_quota", hasQuota)
        if (upload > 0) json.put("upload", upload)
        if (download > 0) json.put("download", download)
        if (total > 0) json.put("total", total)
        if (expire > 0) json.put("expire", expire)
        if (title.isNotEmpty()) json.put("title", title)
        if (webUrl.isNotEmpty()) json.put("web_url", webUrl)
        if (supportUrl.isNotEmpty()) json.put("support_url", supportUrl)
        if (announce.isNotEmpty()) json.put("announce", announce)
        if (serverInterval > 0) json.put("server_interval", serverInterval)
        return json
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        private val USER_INFO = Regex("""\b(upload|download|total|expire)\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

        @JvmStatic
        fun fromJson(json: JSONObject): SubUserInfo {
            if (json.length() == 0) return SubUserInfo()
            return SubUserInfo(
                valid = json.optBoolean("valid", false),
                hasQuota = json.optBoolean("has_quota", false),
                upload = json.optLong("upload", 0L),
                download = json.optLong("download", 0L),
                total = json.optLong("total", 0L),
                expire = json.optLong("expire", 0L),
                title = json.optString("title", ""),
                webUrl = json.optString("web_url", ""),
                supportUrl = json.optString("support_url", ""),
                announce = json.optString("announce", ""),
                serverInterval = json.optInt("server_interval", 0),
            )
        }

        @JvmStatic
        fun parse(text: String?): SubUserInfo {
            if (text.isNullOrBlank()) return SubUserInfo()
            return try {
                fromJson(JSONObject(text))
            } catch (e: Exception) {
                SubUserInfo()
            }
        }

        @JvmStatic
        fun parseHeader(info: String): SubUserInfo {
            val result = SubUserInfo()
            for (match in USER_INFO.findAll(info)) {
                val value = match.groupValues[2].toLongOrNull() ?: 0L
                when (match.groupValues[1].lowercase()) {
                    "upload" -> result.upload = value
                    "download" -> result.download = value
                    "total" -> {
                        result.total = value
                        result.hasQuota = true
                    }
                    else -> result.expire = if (value > 1_000_000_000_000L) value / 1000 else value
                }
                result.valid = true
            }
            return result
        }
    }
}
