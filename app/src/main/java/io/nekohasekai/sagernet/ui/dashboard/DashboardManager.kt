package io.nekohasekai.sagernet.ui.dashboard

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.LocalYacdDashboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class DashboardItem(
    val id: String,
    var name: String,
    var url: String,
    val isPreset: Boolean = false,
    var isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("isPreset", isPreset)
        put("isDefault", isDefault)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): DashboardItem {
            return DashboardItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Dashboard"),
                url = json.optString("url", ""),
                isPreset = json.optBoolean("isPreset", false),
                isDefault = json.optBoolean("isDefault", false),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

data class DashboardTestResult(
    val success: Boolean,
    val httpStatus: Int?,
    val latencyMs: Long,
    val clashApiReady: Boolean,
    val isHttps: Boolean,
    val summary: String,
    val detail: String
)

object DashboardManager {

    const val PRESET_ZASHBOARD_ID = "preset_zashboard"
    const val PRESET_ZASHBOARD_NAME = "Zashboard"
    const val PRESET_ZASHBOARD_URL = "https://board.zash.run.place/"

    const val PRESET_YACD_ID = "preset_yacd"
    const val PRESET_YACD_NAME = "Built-in YACD"
    const val PRESET_YACD_URL = "http://127.0.0.1:9090/ui"

    const val PRESET_METACUBEXD_ID = "preset_metacubexd"
    const val PRESET_METACUBEXD_NAME = "MetaCubeXD"
    const val PRESET_METACUBEXD_URL = "https://metacubex.github.io/metacubexd/"

    @Throws(IllegalArgumentException::class)
    fun normalizeUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("URL cannot be empty")
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("javascript:") || lower.startsWith("file:") ||
            lower.startsWith("content:") || lower.startsWith("data:") ||
            lower.startsWith("intent:") || lower.startsWith("about:")
        ) {
            throw IllegalArgumentException("Unsupported or dangerous URL scheme")
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            trimmed = if (lower.startsWith("//")) "http:$trimmed" else "http://$trimmed"
        }
        val httpUrl = trimmed.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid URL format: unable to parse host or port")
        return httpUrl.toString()
    }

    @Synchronized
    fun getDashboards(): MutableList<DashboardItem> {
        val jsonStr = DataStore.customDashboardListJson.trim()
        val list = mutableListOf<DashboardItem>()
        if (jsonStr.isNotBlank()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    list.add(DashboardItem.fromJsonObject(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                Logs.w("Failed to parse customDashboardListJson: ${e.message}")
            }
        }

        if (list.isEmpty()) {
            // First run or migrated from legacy single DataStore.yacdURL
            list.add(
                DashboardItem(
                    id = PRESET_ZASHBOARD_ID,
                    name = PRESET_ZASHBOARD_NAME,
                    url = PRESET_ZASHBOARD_URL,
                    isPreset = true,
                    isDefault = true
                )
            )
            list.add(
                DashboardItem(
                    id = PRESET_YACD_ID,
                    name = PRESET_YACD_NAME,
                    url = PRESET_YACD_URL,
                    isPreset = true,
                    isDefault = false
                )
            )
            list.add(
                DashboardItem(
                    id = PRESET_METACUBEXD_ID,
                    name = PRESET_METACUBEXD_NAME,
                    url = PRESET_METACUBEXD_URL,
                    isPreset = true,
                    isDefault = false
                )
            )

            val legacyUrl = DataStore.yacdURL.trim()
            val isStandardPreset = legacyUrl.contains("zash.run.place") ||
                    legacyUrl == PRESET_YACD_URL ||
                    legacyUrl.contains("127.0.0.1:9090") ||
                    legacyUrl.contains("metacubexd")

            if (legacyUrl.isNotBlank() && !isStandardPreset) {
                // Transparently migrate existing custom URL to list
                val custom = DashboardItem(
                    id = UUID.randomUUID().toString(),
                    name = "自定义仪表盘",
                    url = legacyUrl,
                    isPreset = false,
                    isDefault = true
                )
                list.forEach { it.isDefault = false }
                list.add(custom)
            }
            saveDashboards(list)
        } else {
            // Verify all core presets exist
            if (list.none { it.id == PRESET_ZASHBOARD_ID || it.url.contains("zash.run.place") }) {
                list.add(
                    0,
                    DashboardItem(
                        id = PRESET_ZASHBOARD_ID,
                        name = PRESET_ZASHBOARD_NAME,
                        url = PRESET_ZASHBOARD_URL,
                        isPreset = true
                    )
                )
            }
            if (list.none { it.id == PRESET_YACD_ID || it.url.contains("127.0.0.1:9090") }) {
                list.add(
                    1,
                    DashboardItem(
                        id = PRESET_YACD_ID,
                        name = PRESET_YACD_NAME,
                        url = PRESET_YACD_URL,
                        isPreset = true
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun saveDashboards(list: List<DashboardItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJsonObject()) }
        DataStore.customDashboardListJson = arr.toString()
    }

    @Synchronized
    fun addDashboard(name: String, rawUrl: String, setAsDefault: Boolean = false): DashboardItem {
        val normalized = normalizeUrl(rawUrl)
        val list = getDashboards()
        if (setAsDefault) {
            list.forEach { it.isDefault = false }
        }
        val item = DashboardItem(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Custom Dashboard" },
            url = normalized,
            isPreset = false,
            isDefault = setAsDefault
        )
        list.add(item)
        saveDashboards(list)
        if (setAsDefault) {
            DataStore.yacdURL = normalized
        }
        return item
    }

    @Synchronized
    fun updateDashboard(id: String, name: String, rawUrl: String, setAsDefault: Boolean = false): Boolean {
        val normalized = normalizeUrl(rawUrl)
        val list = getDashboards()
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return false
        val target = list[index]
        target.name = name.trim().ifBlank { target.name }
        target.url = normalized
        if (setAsDefault) {
            list.forEach { it.isDefault = false }
            target.isDefault = true
            DataStore.yacdURL = normalized
        }
        saveDashboards(list)
        return true
    }

    @Synchronized
    fun deleteDashboard(id: String): Boolean {
        val list = getDashboards()
        val item = list.firstOrNull { it.id == id } ?: return false
        if (item.isPreset) return false // Presets cannot be deleted
        list.remove(item)
        if (item.isDefault && list.isNotEmpty()) {
            list[0].isDefault = true
            DataStore.yacdURL = list[0].url
        }
        saveDashboards(list)
        return true
    }

    @Synchronized
    fun setDefault(id: String) {
        val list = getDashboards()
        var foundUrl: String? = null
        list.forEach {
            if (it.id == id) {
                it.isDefault = true
                foundUrl = it.url
            } else {
                it.isDefault = false
            }
        }
        saveDashboards(list)
        foundUrl?.let { DataStore.yacdURL = it }
    }

    fun isUrlMatching(url1: String, url2: String): Boolean {
        val u1 = url1.trim().trimEnd('/')
        val u2 = url2.trim().trimEnd('/')
        if (u1.equals(u2, ignoreCase = true)) return true
        val base1 = u1.substringBefore('#').substringBefore('?').trimEnd('/')
        val base2 = u2.substringBefore('#').substringBefore('?').trimEnd('/')
        return base1.isNotBlank() && base1.equals(base2, ignoreCase = true)
    }

    @Synchronized
    fun getActiveDashboard(): DashboardItem {
        val list = getDashboards()
        val currentUrl = DataStore.yacdURL.trim()
        val byUrl = list.firstOrNull { isUrlMatching(it.url, currentUrl) }
        if (byUrl != null) return byUrl
        val byDefault = list.firstOrNull { it.isDefault }
        if (byDefault != null) return byDefault
        return list.firstOrNull() ?: DashboardItem(
            id = PRESET_ZASHBOARD_ID,
            name = PRESET_ZASHBOARD_NAME,
            url = PRESET_ZASHBOARD_URL,
            isPreset = true,
            isDefault = true
        )
    }

    suspend fun testConnection(
        rawUrl: String,
        client: OkHttpClient,
        context: Context
    ): DashboardTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val normalizedUrl: String
        try {
            normalizedUrl = normalizeUrl(rawUrl)
        } catch (e: Exception) {
            return@withContext DashboardTestResult(
                success = false,
                httpStatus = null,
                latencyMs = 0,
                clashApiReady = false,
                isHttps = false,
                summary = context.getString(R.string.dashboard_test_url_invalid),
                detail = e.message ?: "Invalid URL"
            )
        }

        val isHttps = normalizedUrl.startsWith("https://", ignoreCase = true)
        var httpStatus: Int? = null
        var urlReachable = false
        var urlError: String? = null

        // 1. Test remote / local dashboard web access
        try {
            val request = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", "Mozilla/5.0 (Android) OwnBox-Dashboard-Test")
                .build()
            client.newCall(request).execute().use { response ->
                httpStatus = response.code
                urlReachable = response.isSuccessful || response.code in 200..399
            }
        } catch (e: Exception) {
            urlError = e.message ?: "Connection timed out"
        }
        val latencyMs = System.currentTimeMillis() - startTime

        // 2. Test local Clash API readiness (127.0.0.1:9090)
        val isServiceConnected = DataStore.serviceState.connected
        val isClashApiEnabled = DataStore.enableClashAPI || DataStore.allowAccess
        val secret = DataStore.clashApiSecret
        val clashApiReady: Boolean = if (isServiceConnected && isClashApiEnabled) {
            LocalYacdDashboard.checkApi(client, secret) == null
        } else {
            false
        }

        // 3. Formulate diagnosis summary and details
        val summaryBuilder = StringBuilder()
        val detailBuilder = StringBuilder()

        if (urlReachable) {
            summaryBuilder.append(context.getString(R.string.dashboard_test_url_ok, latencyMs))
            if (clashApiReady) {
                detailBuilder.append(context.getString(R.string.dashboard_test_clash_ready))
            } else {
                if (!isServiceConnected) {
                    detailBuilder.append(context.getString(R.string.dashboard_test_clash_service_off))
                } else if (!isClashApiEnabled) {
                    detailBuilder.append(context.getString(R.string.dashboard_test_clash_api_off))
                } else {
                    detailBuilder.append(context.getString(R.string.dashboard_test_clash_auth_fail))
                }
            }
            if (isHttps) {
                detailBuilder.append("\n").append(context.getString(R.string.dashboard_test_mixed_content_hint))
            }
        } else {
            summaryBuilder.append(context.getString(R.string.dashboard_test_url_fail))
            detailBuilder.append(urlError ?: "HTTP $httpStatus")
        }

        DashboardTestResult(
            success = urlReachable,
            httpStatus = httpStatus,
            latencyMs = latencyMs,
            clashApiReady = clashApiReady,
            isHttps = isHttps,
            summary = summaryBuilder.toString(),
            detail = detailBuilder.toString()
        )
    }
}
