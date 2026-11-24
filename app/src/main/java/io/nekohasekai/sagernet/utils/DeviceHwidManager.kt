package io.nekohasekai.sagernet.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import io.nekohasekai.sagernet.ktx.app
import java.security.MessageDigest
import java.util.UUID

/**
 * Generates stable, privacy-preserving device identification headers (`x-hwid`, `x-device-os`,
 * `x-ver-os`, `x-device-model`) for subscription servers that enforce HWID device limits
 * (e.g. Remnawave, Marzban, 3X-UI).
 */
object DeviceHwidManager {

    private const val PREFS_NAME = "ownbox_device_identity"
    private const val KEY_FALLBACK_HWID = "fallback_hwid"
    private const val HWID_SALT = "OwnBox-HWID:"
    private const val LEGACY_BUGGY_ANDROID_ID = "9774d56d682e549c"

    // Remnawave v3+ requires /^[a-zA-Z0-9=-]{10,64}$/
    private val HWID_PATTERN = Regex("^[a-zA-Z0-9=-]{10,64}$")

    @Volatile
    private var cachedHwid: String? = null

    fun buildSubscriptionHeaders(): Map<String, String> = linkedMapOf(
        "x-hwid" to getDeviceHwid(),
        "x-device-os" to getDeviceOs(),
        "x-ver-os" to getDeviceOsVersion(),
        "x-device-model" to getDeviceModel(),
    )

    fun getDeviceOs(): String = "Android"

    fun getDeviceOsVersion(): String = formatOsVersion(Build.VERSION.RELEASE)

    fun getDeviceModel(): String = formatDeviceModel(Build.MANUFACTURER, Build.MODEL)

    @SuppressLint("HardwareIds")
    fun getDeviceHwid(): String {
        cachedHwid?.let { return it }
        synchronized(this) {
            cachedHwid?.let { return it }
            val androidId = runCatching {
                Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            val resolved = deriveHwid(androidId) { getOrCreateFallbackUuid() }
            cachedHwid = resolved
            return resolved
        }
    }

    private fun getOrCreateFallbackUuid(): String {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_FALLBACK_HWID, null)?.trim().orEmpty()
        if (isValidHwid(existing)) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FALLBACK_HWID, generated).apply()
        return generated
    }

    internal fun deriveHwid(androidId: String?, fallbackProvider: () -> String): String {
        val normalized = androidId?.trim().orEmpty()
        if (normalized.isNotEmpty() && !normalized.equals(LEGACY_BUGGY_ANDROID_ID, ignoreCase = true)) {
            return sha256Hex(HWID_SALT + normalized)
        }
        val fallback = fallbackProvider().trim()
        return if (isValidHwid(fallback)) fallback else UUID.randomUUID().toString()
    }

    internal fun isValidHwid(value: String): Boolean = HWID_PATTERN.matches(value)

    internal fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4])
            sb.append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    internal fun formatOsVersion(release: String?): String =
        sanitizeHeaderValue(release, fallback = "unknown", maxLength = 32)

    internal fun formatDeviceModel(manufacturer: String?, model: String?): String {
        val cleanManufacturer = sanitizeHeaderValue(manufacturer, fallback = "", maxLength = 64)
        val cleanModel = sanitizeHeaderValue(model, fallback = "", maxLength = 64)
        val combined = when {
            cleanModel.isEmpty() -> cleanManufacturer
            cleanManufacturer.isEmpty() -> cleanModel
            cleanModel.startsWith(cleanManufacturer, ignoreCase = true) -> cleanModel
            else -> "$cleanManufacturer $cleanModel"
        }
        return sanitizeHeaderValue(combined, fallback = "Android", maxLength = 128)
    }

    internal fun sanitizeHeaderValue(raw: String?, fallback: String = "unknown", maxLength: Int = 128): String {
        if (raw.isNullOrBlank()) return fallback
        val sb = StringBuilder(raw.length)
        var lastWasSpace = true
        for (ch in raw) {
            when {
                ch == '\r' || ch == '\n' || ch == '\t' || ch == ' ' -> {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                }
                ch.code in 0x21..0x7E -> {
                    sb.append(ch)
                    lastWasSpace = false
                }
            }
        }
        val cleaned = sb.toString().trim()
        if (cleaned.isEmpty()) return fallback
        return if (cleaned.length > maxLength) cleaned.substring(0, maxLength).trimEnd() else cleaned
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
