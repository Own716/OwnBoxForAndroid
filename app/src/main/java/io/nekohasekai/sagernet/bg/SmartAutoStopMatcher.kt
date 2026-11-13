package io.nekohasekai.sagernet.bg

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Pure JVM matcher & validator for Smart Auto-Stop (Issue #91).
 *
 * Supported comma-separated rule formats (up to [MAX_RULES]):
 * - Exact IPv4 / IPv6 address: e.g. `192.168.1.50` or `fd00::1`
 * - CIDR subnet: e.g. `192.168.1.0/24` or `10.0.0.0/8`
 * - Gateway prefix: `gateway:192.168.1.1` or `gateway:192.168.0.0/16` (case-insensitive)
 */
object SmartAutoStopMatcher {

    const val MAX_RULES = 5
    private const val GATEWAY_PREFIX = "gateway:"

    data class ParsedRule(
        val raw: String,
        val isGateway: Boolean,
        val networkBytes: ByteArray,
        val prefixLength: Int,
    )

    data class NetworkSnapshot(
        val ipAddresses: List<String>,
        val gatewayAddresses: List<String>,
        val hasPhysicalNetwork: Boolean,
    ) {
        val networkKey: String
            get() = ipAddresses
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .joinToString(",")
    }

    data class MatchResult(
        val matchedRule: String? = null,
        val matchedAddress: String? = null,
    ) {
        val isMatched: Boolean get() = matchedRule != null
    }

    sealed class ValidationResult {
        data class Valid(val rules: List<String>) : ValidationResult()
        data class TooManyRules(val count: Int, val max: Int = MAX_RULES) : ValidationResult()
        data class InvalidRule(val rule: String) : ValidationResult()
    }

    fun parseRules(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', '，', '\n', ';')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    fun normalizeRules(raw: String?): String = parseRules(raw).joinToString(",")

    fun validateRules(raw: String?, maxRules: Int = MAX_RULES): ValidationResult {
        val rules = parseRules(raw)
        if (rules.size > maxRules) {
            return ValidationResult.TooManyRules(rules.size, maxRules)
        }
        for (rule in rules) {
            if (compileRule(rule) == null) {
                return ValidationResult.InvalidRule(rule)
            }
        }
        return ValidationResult.Valid(rules)
    }

    fun evaluate(rules: List<String>, snapshot: NetworkSnapshot): MatchResult {
        if (!snapshot.hasPhysicalNetwork || rules.isEmpty()) {
            return MatchResult()
        }
        val compiled = rules.mapNotNull { compileRule(it) }
        if (compiled.isEmpty()) return MatchResult()

        for (rule in compiled) {
            val candidates = if (rule.isGateway) snapshot.gatewayAddresses else snapshot.ipAddresses
            for (candidate in candidates) {
                val addr = parseStrictAddress(candidate.trim()) ?: continue
                if (matchesSubnet(addr, rule.networkBytes, rule.prefixLength)) {
                    return MatchResult(matchedRule = rule.raw, matchedAddress = candidate.trim())
                }
            }
        }
        return MatchResult()
    }

    fun compileRule(rawRule: String): ParsedRule? {
        val trimmed = rawRule.trim()
        if (trimmed.isEmpty()) return null
        val isGateway = trimmed.startsWith(GATEWAY_PREFIX, ignoreCase = true)
        val body = if (isGateway) trimmed.substring(GATEWAY_PREFIX.length).trim() else trimmed
        if (body.isEmpty()) return null

        val slashIndex = body.indexOf('/')
        val addrPart: String
        val explicitPrefix: Int?
        if (slashIndex >= 0) {
            addrPart = body.substring(0, slashIndex).trim()
            val prefixStr = body.substring(slashIndex + 1).trim()
            explicitPrefix = prefixStr.toIntOrNull() ?: return null
        } else {
            addrPart = body
            explicitPrefix = null
        }

        val addrBytes = parseStrictAddress(addrPart) ?: return null
        val maxPrefix = addrBytes.size * 8
        val prefix = explicitPrefix ?: maxPrefix
        if (prefix !in 0..maxPrefix) return null

        return ParsedRule(
            raw = trimmed,
            isGateway = isGateway,
            networkBytes = maskAddress(addrBytes, prefix),
            prefixLength = prefix,
        )
    }

    private fun parseStrictAddress(value: String): ByteArray? {
        if (value.isEmpty()) return null
        if (!value.contains(':')) {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3 || (p.length > 1 && p.startsWith('0'))) return null
                val num = p.toIntOrNull() ?: return null
                if (num !in 0..255) return null
                bytes[i] = num.toByte()
            }
            return runCatching {
                val inet = InetAddress.getByAddress(bytes)
                if (inet is Inet4Address) inet.address else null
            }.getOrNull()
        }

        // Strict IPv6 check without triggering DNS lookup
        val clean = value.substringBefore('%')
        if (!clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return null
        }
        return runCatching {
            val inet = InetAddress.getByName(clean)
            if (inet is Inet6Address) inet.address else null
        }.getOrNull()
    }

    private fun maskAddress(address: ByteArray, prefixLength: Int): ByteArray {
        val masked = ByteArray(address.size)
        var remaining = prefixLength
        for (i in address.indices) {
            if (remaining <= 0) {
                masked[i] = 0
            } else if (remaining >= 8) {
                masked[i] = address[i]
                remaining -= 8
            } else {
                val mask = (0xFF shl (8 - remaining)) and 0xFF
                masked[i] = (address[i].toInt() and mask).toByte()
                remaining = 0
            }
        }
        return masked
    }

    private fun matchesSubnet(address: ByteArray, maskedNetwork: ByteArray, prefixLength: Int): Boolean {
        if (address.size != maskedNetwork.size) return false
        var remaining = prefixLength
        for (i in address.indices) {
            if (remaining <= 0) return true
            if (remaining >= 8) {
                if (address[i] != maskedNetwork[i]) return false
                remaining -= 8
            } else {
                val mask = (0xFF shl (8 - remaining)) and 0xFF
                return (address[i].toInt() and mask) == (maskedNetwork[i].toInt() and mask)
            }
        }
        return true
    }
}
