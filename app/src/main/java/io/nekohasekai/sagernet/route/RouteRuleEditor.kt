package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.makeSingBoxRule
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Advanced fields stay in the existing config column and Parcel, without changing legacy rows. */
object RouteRuleEditor {
    val lists = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "inbound", "sniffer", "network_type")
    val booleans = setOf("ip_is_private", "source_ip_is_private", "invert", "no_drop", "network_is_expensive")
    val scalars = setOf("action", "method", "strategy", "ip_version", "override_address", "override_port")
    val fields = lists + booleans + scalars
    val actions = setOf("route", "route-options", "reject", "hijack-dns", "sniff", "resolve")
    val nonTerminal = setOf("route-options", "sniff", "resolve")
    fun json(config: String) = if (config.isBlank()) JSONObject() else JSONObject(config)
    fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun values(obj: JSONObject, key: String): List<String> = when (val value = obj.opt(key)) {
        is JSONArray -> (0 until value.length()).map { value.getString(it) }
        is String -> listOf(value)
        else -> emptyList()
    }
    fun text(config: String, key: String): String {
        val obj = json(config)
        return if (key in lists) values(obj, key).joinToString("\n") else obj.opt(key)?.toString().orEmpty()
    }
    /** Touch only the selected field; custom/unknown properties are never removed. */
    fun change(config: String, key: String, value: Any?): String {
        require(key in fields)
        val obj = json(config)
        val text = value?.toString().orEmpty().trim()
        when {
            key in booleans -> if (value == true) obj.put(key, true) else obj.remove(key)
            text.isEmpty() -> obj.remove(key)
            key in lists -> obj.put(key, JSONArray(lines(text)))
            key == "ip_version" || key == "override_port" -> obj.put(key, text.toInt())
            else -> obj.put(key, text)
        }
        return if (obj.length() == 0) "" else obj.toString(2)
    }
    fun action(config: String, outbound: Long): String {
        val action = json(config).optString("action").ifBlank { "route" }
        return if (action == "route" && outbound == -2L) "reject" else action
    }
    fun needsOutbound(config: String, outbound: Long): Boolean = action(config, outbound) in setOf("route", "bypass")

    fun address(text: String): Boolean {
        val parts = text.split('/')
        if (parts.size > 2 || parts[0].isBlank()) return false
        val host = parts[0]
        val ipv6 = ':' in host
        if (ipv6) {
            if (!host.matches(Regex("[0-9a-fA-F:.]+"))) return false
            try { InetAddress.getByName(host) } catch (_: Exception) { return false }
        } else {
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || !it.all(Char::isDigit) || (it.toIntOrNull() ?: -1) !in 0..255 }) return false
        }
        return parts.size == 1 || parts[1].toIntOrNull()?.let { it in 0..if (ipv6) 128 else 32 } == true
    }
    fun port(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' } && text.toIntOrNull() in 0..65535
    fun portOrRange(text: String): Boolean {
        if (':' !in text) return port(text)
        val p = text.split(':')
        if (p.size != 2 || p.all { it.isEmpty() }) return false
        if (p.any { it.isNotEmpty() && !port(it) }) return false
        return (p[0].toIntOrNull() ?: 0) <= (p[1].toIntOrNull() ?: 65535)
    }
    fun isValidLocalRuleSet(entry: String): Boolean {
        val trimmed = entry.trim()
        if (!trimmed.startsWith("local:")) return false
        val target = trimmed.removePrefix("local:").trim()
        if (target.isEmpty() || target == "." || target == "..") return false
        if ('\u0000' in target) return false
        return target.split('/', '\\').none { it == ".." }
    }

    enum class Error { VALUE, JSON, UNSUPPORTED, EMPTY, ACTION_OPTIONS }
    data class Problem(val field: String, val error: Error)
    fun problems(rule: RuleEntity): List<Problem> {
        val out = mutableListOf<Problem>()
        val obj = try { json(rule.config) } catch (_: Exception) { return listOf(Problem("config", Error.JSON)) }
        fun check(key: String, entries: List<String>, valid: (String) -> Boolean) {
            if (entries.any { !valid(it) }) out += Problem(key, Error.VALUE)
        }
        check("ip_cidr", rule.ip.listByLineOrComma().filterNot { it.startsWith("geoip:") || it.startsWith("geoip-") }, ::address)
        check("source_ip_cidr", rule.source.listByLineOrComma(), ::address)
        check("port", rule.port.listByLineOrComma(), ::portOrRange)
        check("source_port", rule.sourcePort.listByLineOrComma(), ::portOrRange)
        check("network", rule.network.listByLineOrComma()) { it in setOf("tcp", "udp", "icmp") }
        val protocols = setOf("tls", "http", "quic", "dns", "stun", "bittorrent", "dtls", "ssh", "rdp", "ntp")
        check("protocol", rule.protocol.listByLineOrComma()) { it in protocols }
        check("sniffer", values(obj, "sniffer")) { it in protocols }
        check("package_name", rule.packages.toList()) { it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) }
        val catalog = RuleSetCatalog.current()
        check("rule_set", rule.ruleset.listByLineOrComma()) { entry ->
            val trimmed = entry.trim()
            when {
                isValidLocalRuleSet(trimmed) -> true
                catalog.urlOf(trimmed) != null -> true
                RuleSets.isUrl(trimmed) -> {
                    val cleaned = RuleSets.cleanUrl(trimmed)
                    val parsed = cleaned.toHttpUrlOrNull()
                    parsed != null && parsed.scheme == "https" &&
                        parsed.username.isEmpty() && parsed.password.isEmpty()
                }
                trimmed.startsWith("geoip:") || trimmed.startsWith("geoip-") ||
                    trimmed.startsWith("geosite:") || trimmed.startsWith("geosite-") -> true
                else -> false
            }
        }
        check("ip_version", listOfNotNull(obj.opt("ip_version")?.toString())) { it in setOf("4", "6") }
        check("override_port", listOfNotNull(obj.opt("override_port")?.toString()), ::port)
        check("inbound", values(obj, "inbound")) { it in setOf("tun-in", "mixed-in") }
        check("network_type", values(obj, "network_type")) { it in setOf("wifi", "cellular", "ethernet", "other") }
        val knownSets = (rule.domains.listByLineOrComma() + rule.ip.listByLineOrComma())
            .filter { it.startsWith("geosite:") || it.startsWith("geosite-") || it.startsWith("geoip:") || it.startsWith("geoip-") }.toMutableSet()
        rule.ruleset.listByLineOrComma().forEach {
            val trimmed = it.trim()
            val url = RuleSets.cleanUrl(trimmed)
            knownSets += trimmed
            knownSets += RuleSets.tagFor(trimmed)
            if (trimmed.startsWith("local:")) {
                knownSets += RuleSetCatalog.localRuleSetTag(trimmed)
            } else {
                knownSets += "ruleset-${kotlin.math.abs(url.hashCode())}"
            }
        }
        check("rule_set", values(obj, "rule_set")) {
            it in knownSets || catalog.urlOf(it) != null || RuleSets.isUrl(it) || isValidLocalRuleSet(it)
        }
        val act = action(rule.config, rule.outbound)
        if (act !in actions && obj.has("action")) out += Problem("action", Error.UNSUPPORTED)
        if (act == "reject" && (obj.optString("method") !in setOf("", "default", "drop", "reply") ||
                (obj.optString("method") == "drop" && obj.optBoolean("no_drop")))) out += Problem("method", Error.ACTION_OPTIONS)
        if (act == "route-options" && !obj.has("override_address") && !obj.has("override_port")) out += Problem("override_address", Error.ACTION_OPTIONS)
        if (obj.has("strategy") && obj.optString("strategy") !in setOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")) out += Problem("strategy", Error.VALUE)
        val allowedOptions = when (act) {
            "route", "bypass", "route-options" -> setOf("override_address", "override_port")
            "reject" -> setOf("method", "no_drop")
            "resolve" -> setOf("strategy")
            "sniff" -> setOf("sniffer")
            else -> emptySet()
        }
        for (key in setOf("override_address", "override_port", "method", "no_drop", "strategy", "sniffer")) {
            if (obj.has(key) && key !in allowedOptions) out += Problem(key, Error.ACTION_OPTIONS)
        }
        for (key in setOf("override_destination", "sniff_override_destination", "reject_method", "process_name", "process_path", "process_path_regex", "wifi_ssid", "wifi_bssid")) {
            if (obj.has(key)) out += Problem(key, Error.UNSUPPORTED)
        }
        if (!hasConditions(rule)) out += Problem("match", Error.EMPTY)
        return out
    }
    fun hasConditions(rule: RuleEntity): Boolean {
        if (listOf(rule.domains, rule.ip, rule.port, rule.sourcePort, rule.network, rule.source, rule.protocol, rule.ruleset).any { it.isNotBlank() } || rule.packages.isNotEmpty()) return true
        val obj = json(rule.config)
        return (lists - "sniffer").any { values(obj, it).isNotEmpty() } || obj.optBoolean("ip_is_private") ||
            obj.optBoolean("source_ip_is_private") || obj.optBoolean("network_is_expensive") ||
            obj.optInt("ip_version") in setOf(4, 6) || obj.has("rule_set") || obj.has("rules")
    }
    /** Remove a generated outbound only when an explicit non-routing action requires it. */
    fun applyAction(rule: SingBoxOptions.Rule_DefaultOptions, config: String) {
        if (config.isBlank()) return
        val obj = json(config)
        val act = obj.optString("action")
        if (act.isNotEmpty()) {
            val effective = if (act == "route" && rule.action == "reject" && rule.outbound == null) "reject" else act
            rule.action = effective
            if (effective !in setOf("route", "bypass")) rule.outbound = null
            if (effective != act) {
                obj.put("action", effective)
                rule._hack_custom_config = obj.toString()
                return
            }
        }
        rule._hack_custom_config = config
    }
    /** Legacy domain/IP alternatives remain OR; invert applies to the combined predicate once. */
    fun invertedGroup(rules: List<SingBoxOptions.Rule_DefaultOptions>): SingBoxOptions.Rule? {
        if (rules.size < 2 || !rules.any { json(it._hack_custom_config.orEmpty()).optBoolean("invert") }) return null
        val actionKeys = setOf("action", "outbound", "method", "no_drop", "strategy", "sniffer", "timeout", "override_address", "override_port")
        val first = JSONObject(rules.first().asMap())
        val children = JSONArray()
        rules.forEach { rule ->
            val child = JSONObject(rule.asMap())
            (actionKeys + "invert").forEach { child.remove(it) }
            children.put(child)
        }
        val group = JSONObject().put("type", "logical").put("mode", "or").put("rules", children).put("invert", true)
        actionKeys.forEach { if (first.has(it)) group.put(it, first.get(it)) }
        return SingBoxOptions.Rule().apply { _hack_custom_config = group.toString() }
    }
    /** No socket/TUN is started. Placeholder rule-sets avoid downloads while the real core checks RE2/schema. */
    fun validationConfig(rule: RuleEntity): String {
        val r = SingBoxOptions.Rule_DefaultOptions()
        if (rule.domains.isNotBlank()) r.makeSingBoxRule(rule.domains.listByLineOrComma(), false)
        if (rule.ip.isNotBlank()) r.makeSingBoxRule(rule.ip.listByLineOrComma(), true)
        if (rule.source.isNotBlank()) r.source_ip_cidr = rule.source.listByLineOrComma()
        fun ports(text: String, source: Boolean) {
            val values = text.listByLineOrComma()
            val ports = values.filterNot { ':' in it }.map { it.toInt() }
            val ranges = values.filter { ':' in it }
            if (source) { if (ports.isNotEmpty()) r.source_port = ports; if (ranges.isNotEmpty()) r.source_port_range = ranges }
            else { if (ports.isNotEmpty()) r.port = ports; if (ranges.isNotEmpty()) r.port_range = ranges }
        }
        ports(rule.port, false); ports(rule.sourcePort, true)
        if (rule.network.isNotBlank()) r.network = rule.network.listByLineOrComma()
        if (rule.protocol.isNotBlank()) r.protocol = rule.protocol.listByLineOrComma()
        if (rule.packages.isNotEmpty()) r.package_name = rule.packages.toList()
        r.outbound = if (rule.outbound == -1L) "bypass" else "proxy"
        if (rule.outbound == -2L) { r.outbound = null; r.action = "reject" }
        if (rule.ruleset.isNotBlank()) r.rule_set = (r.rule_set.orEmpty() + listOf("validation-remote")).distinct()
        applyAction(r, rule.config)
        val projected = JSONObject(r.asMap())
        val tags = values(projected, "rule_set")
        val outbounds = JSONArray().put(JSONObject().put("type", "direct").put("tag", "proxy"))
            .put(JSONObject().put("type", "direct").put("tag", "bypass"))
        val inbounds = JSONArray()
        for (tag in values(projected, "inbound")) inbounds.put(JSONObject().put("type", "mixed").put("tag", tag).put("listen", "127.0.0.1"))
        val sets = JSONArray()
        for (tag in tags) sets.put(JSONObject().put("type", "inline").put("tag", tag)
            .put("rules", JSONArray().put(JSONObject().put("domain", JSONArray().put("validation.invalid")))))
        return JSONObject().put("inbounds", inbounds).put("outbounds", outbounds)
            .put("route", JSONObject().put("rules", JSONArray().put(projected)).put("rule_set", sets)).toString()
    }
}
