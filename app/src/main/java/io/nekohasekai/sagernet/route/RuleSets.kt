package io.nekohasekai.sagernet.route

import android.content.Context
import android.net.Uri
import io.nekohasekai.sagernet.R
import java.util.zip.CRC32

object RuleSets {
    const val ADBLOCK_TAG = "throne-adblocksingbox"
    const val ADBLOCK_URL = "https://raw.githubusercontent.com/217heidai/adblockfilters/main/rules/adblocksingbox.srs"

    const val MIRROR_GITHUB = 0
    const val MIRROR_CLOUDFLARE = 1
    const val MIRROR_GCORE = 2
    const val MIRROR_QUANTIL = 3
    const val MIRROR_FASTLY = 4
    const val MIRROR_CDN = 5

    /** Strips optional legacy `rsip:` / `rssite:` prefixes. */
    fun cleanUrl(entry: String): String {
        val trimmed = entry.trim()
        return when {
            trimmed.startsWith("rsip:", ignoreCase = true) -> trimmed.substring(5).trim()
            trimmed.startsWith("rssite:", ignoreCase = true) -> trimmed.substring(7).trim()
            else -> trimmed
        }
    }

    /** An http(s) URL (optionally prefixed with `rsip:` / `rssite:`) whose file name contains ".srs" or ".json" or is a valid web URL. */
    fun isUrl(entry: String): Boolean {
        val cleaned = cleanUrl(entry)
        val parts = UrlText.parse(cleaned) ?: return false
        if (parts.scheme != "http" && parts.scheme != "https") return false
        val fn = UrlText.fileName(cleaned).lowercase()
        return fn.contains(".srs") || fn.contains(".json") || fn.contains(".dat") || (parts.host.contains(".") && cleaned.contains("/"))
    }

    /** Determines whether a rule-set entry represents an IP rule-set (`geoip`) vs a domain/general rule-set. */
    fun isIpEntry(entry: String, resolvedUrl: String = ""): Boolean {
        val e = entry.trim().lowercase()
        if (e.startsWith("rsip:") || e.startsWith("geoip-") || e.startsWith("geoip:")) return true
        if (e.startsWith("rssite:") || e.startsWith("geosite-") || e.startsWith("geosite:")) return false
        val u = resolvedUrl.ifEmpty { cleanUrl(e) }.lowercase()
        val fileName = UrlText.fileName(u)
        return u.contains("/geoip/") || fileName.startsWith("geoip-") || fileName.startsWith("geoip_")
    }

    /**
     * The rule-set tag of a rule_set entry: catalog names are normalized (`:` -> `-`);
     * URLs become `<file with .srs/-json>-<crc32 of the URL>`.
     */
    fun tagFor(entry: String): String {
        val e = entry.trim()
        if (!isUrl(e)) return e.replace(':', '-')
        val cleaned = cleanUrl(e)
        val crc = CRC32().apply { update(cleaned.toByteArray(Charsets.UTF_8)) }.value
        val rawBase = UrlText.fileName(cleaned).ifEmpty { "ruleset" }
            .replace(".srs", "-srs", ignoreCase = true)
            .replace(".json", "-json", ignoreCase = true)
        val base = rawBase.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "$base-" + java.lang.Long.toHexString(crc).padStart(8, '0')
    }

    /** A rule-set name as is; an .srs URL as its file name. */
    fun shortName(entry: String): String {
        val e = entry.trim()
        if (isUrl(e)) {
            val cleaned = cleanUrl(e)
            return UrlText.fileName(cleaned).ifEmpty { Uri.parse(cleaned).lastPathSegment ?: cleaned }
        }
        return e
    }

    /** Where a rule-set comes from: "owner/repo" for GitHub files, else the host. */
    fun source(url: String): String {
        val cleaned = cleanUrl(url)
        val uri = Uri.parse(cleaned)
        val host = uri.host ?: return cleaned
        if (host.equals("raw.githubusercontent.com", true) || host.equals("github.com", true)) {
            val segments = uri.pathSegments
            if (segments.size >= 2) return segments[0] + "/" + segments[1]
        }
        return host
    }

    /** Rewrites raw.githubusercontent.com URLs through the chosen jsDelivr mirror. */
    fun mirrorLink(url: String, mirror: Int): String {
        if (mirror == MIRROR_GITHUB) return url
        val parts = UrlText.parse(url) ?: return url
        if (parts.host != "raw.githubusercontent.com") return url
        val sb = StringBuilder(
            when (mirror) {
                MIRROR_GCORE -> "https://gcore.jsdelivr.net/gh"
                MIRROR_QUANTIL -> "https://quantil.jsdelivr.net/gh"
                MIRROR_FASTLY -> "https://fastly.jsdelivr.net/gh"
                MIRROR_CDN -> "https://cdn.jsdelivr.net/gh"
                else -> "https://testingcf.jsdelivr.net/gh"
            }
        )
        var index = 0
        for (segment in parts.path.split('/')) {
            if (segment.isEmpty()) continue
            sb.append(if (index == 2) '@' else '/').append(segment)
            index++
        }
        return sb.toString()
    }

    /**
     * Normalizes user-configured `rulesUpdateInterval` into a valid sing-box Go duration string
     * (e.g. `"30m"`, `"1h"`, `"24h"`), or `null` when auto-update is disabled (`"0"` / blank).
     */
    fun normalizeUpdateInterval(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t == "0" || t == "-1" || t.equals("off", true) || t.equals("false", true)) {
            return null
        }
        // Pure number -> treat as minutes (e.g. "30" -> "30m", "60" -> "60m")
        val asLong = t.toLongOrNull()
        if (asLong != null) {
            return if (asLong <= 0L) null else "${asLong}m"
        }
        // Convert days ("1d", "3d") to hours ("24h", "72h") for Go duration compatibility
        if (t.endsWith("d", ignoreCase = true)) {
            val days = t.dropLast(1).trim().toLongOrNull()
            if (days != null && days > 0L) return "${days * 24L}h"
        }
        if (t.matches(Regex("^\\d+[smhSMH]$"))) {
            val num = t.dropLast(1).toLongOrNull() ?: return null
            if (num <= 0L) return null
            return t.lowercase()
        }
        return t
    }

    /** Formats `rulesUpdateInterval` into a human-readable string for UI summaries and subtitles. */
    fun formatUpdateInterval(context: Context, raw: String?): String {
        val normalized = normalizeUpdateInterval(raw)
            ?: return context.getString(R.string.rule_set_auto_update_off)
        val unit = normalized.last().lowercaseChar()
        val value = normalized.dropLast(1).toLongOrNull()
        if (value != null) {
            return when (unit) {
                'm' -> if (value % 60L == 0L && value >= 60L) {
                    context.getString(R.string.rule_set_auto_update_every_hours, value / 60L)
                } else {
                    context.getString(R.string.rule_set_auto_update_every_minutes, value)
                }
                'h' -> context.getString(R.string.rule_set_auto_update_every_hours, value)
                's' -> if (value % 3600L == 0L && value >= 3600L) {
                    context.getString(R.string.rule_set_auto_update_every_hours, value / 3600L)
                } else if (value % 60L == 0L && value >= 60L) {
                    context.getString(R.string.rule_set_auto_update_every_minutes, value / 60L)
                } else {
                    normalized
                }
                else -> normalized
            }
        }
        return normalized
    }
}
