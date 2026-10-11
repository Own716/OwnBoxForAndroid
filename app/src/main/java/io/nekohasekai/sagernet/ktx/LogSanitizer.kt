package io.nekohasekai.sagernet.ktx

import org.json.JSONObject
import java.util.regex.Pattern

object LogSanitizer {

    private const val REDACTED = "******"

    // Sensitive field names across JSON configs (sing-box, clash, protocols)
    private val SENSITIVE_JSON_KEYS = listOf(
        "password",
        "uuid",
        "private_key",
        "secret",
        "psk",
        "auth_str",
        "access_token",
        "token",
        "webdav_password",
        "webdavPassword",
        "credentials"
    )

    // Regex pattern for JSON key-value pairs with sensitive keys: "key": "value" or "key":"value"
    private val JSON_KEY_PATTERN = Pattern.compile(
        """(?i)"(${SENSITIVE_JSON_KEYS.joinToString("|")})"\s*:\s*"([^"]*)""""
    )

    // Regex pattern for URL userinfo: scheme://user:password@host
    private val URL_AUTH_PATTERN = Pattern.compile(
        """([a-zA-Z][a-zA-Z0-9+.-]*://)([^:@\s]+):([^@\s]+)@"""
    )

    // Regex pattern for Authorization header (Bearer / Basic)
    private val AUTH_HEADER_PATTERN = Pattern.compile(
        """(?i)(Authorization\s*[:=]\s*(?:Bearer|Basic)\s+)[^\s\r\n,;]+"""
    )

    // Regex pattern for WireGuard private keys in INI / config format
    private val WIREGUARD_KEY_PATTERN = Pattern.compile(
        """(?i)(PrivateKey\s*=\s*)\S{20,}"""
    )

    /**
     * Sanitizes sensitive fields (passwords, UUIDs, private keys, auth tokens) from the given text.
     */
    fun sanitize(input: String?): String {
        if (input.isNullOrEmpty()) return ""

        var result = input

        // 1. Sanitize JSON sensitive fields
        val jsonMatcher = JSON_KEY_PATTERN.matcher(result)
        if (jsonMatcher.find()) {
            result = jsonMatcher.replaceAll("""\"$1\": \"$REDACTED\"""")
        }

        // 2. Sanitize URL userinfo (passwords in URLs)
        val urlMatcher = URL_AUTH_PATTERN.matcher(result)
        if (urlMatcher.find()) {
            result = urlMatcher.replaceAll("""$1$2:$REDACTED@""")
        }

        // 3. Sanitize Authorization headers
        val authMatcher = AUTH_HEADER_PATTERN.matcher(result)
        if (authMatcher.find()) {
            result = authMatcher.replaceAll("""$1$REDACTED""")
        }

        // 4. Sanitize WireGuard private keys
        val wgMatcher = WIREGUARD_KEY_PATTERN.matcher(result)
        if (wgMatcher.find()) {
            result = wgMatcher.replaceAll("""$1$REDACTED""")
        }

        return result
    }

    /**
     * Generates a safe, non-sensitive summary of a sing-box / proxy configuration JSON.
     * Contains only high-level structural counts without credential payloads.
     */
    fun summarizeConfig(configJson: String?): String {
        if (configJson.isNullOrBlank()) return "EmptyConfig"
        return try {
            val json = JSONObject(configJson)
            val outbounds = json.optJSONArray("outbounds")
            val outboundCount = outbounds?.length() ?: 0
            val types = mutableSetOf<String>()
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i)
                    ob?.optString("type")?.takeIf { it.isNotBlank() }?.let { types.add(it) }
                }
            }
            val hasDns = json.has("dns")
            val hasRoute = json.has("route")
            "ConfigSummary(outbounds=$outboundCount, types=$types, dns=$hasDns, route=$hasRoute)"
        } catch (_: Exception) {
            "ConfigSummary(sanitizedLength=${configJson.length})"
        }
    }
}
