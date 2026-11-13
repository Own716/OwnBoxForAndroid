package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared readable backup codecs & settings default materialization for [BackupHelper] and [BackupFragment].
 */
object BackupCodec {

    fun resolveProfilesKey(obj: JSONObject): String? = when {
        obj.has("profiles") -> "profiles"
        obj.has("proxies") -> "proxies"
        else -> null
    }

    fun ruleToJson(rule: RuleEntity): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("config", rule.config)
        put("userOrder", rule.userOrder)
        put("enabled", rule.enabled)
        put("domains", rule.domains)
        put("ip", rule.ip)
        put("port", rule.port)
        put("sourcePort", rule.sourcePort)
        put("network", rule.network)
        put("source", rule.source)
        put("protocol", rule.protocol)
        put("ruleset", rule.ruleset)
        put("outbound", rule.outbound)
        put("packages", JSONArray().apply {
            rule.packages.forEach { put(it) }
        })
    }

    fun ruleFromJson(obj: JSONObject): RuleEntity {
        val packagesSet = linkedSetOf<String>()
        val packagesArr = obj.optJSONArray("packages")
        if (packagesArr != null) {
            for (i in 0 until packagesArr.length()) {
                val pkg = packagesArr.optString(i, "").trim()
                if (pkg.isNotEmpty()) packagesSet.add(pkg)
            }
        }
        return RuleEntity(
            id = obj.optLong("id", 0L),
            name = obj.optString("name", ""),
            config = obj.optString("config", ""),
            userOrder = obj.optLong("userOrder", 0L),
            enabled = obj.optBoolean("enabled", false),
            domains = obj.optString("domains", ""),
            ip = obj.optString("ip", ""),
            port = obj.optString("port", ""),
            sourcePort = obj.optString("sourcePort", ""),
            network = obj.optString("network", ""),
            source = obj.optString("source", ""),
            protocol = obj.optString("protocol", ""),
            ruleset = obj.optString("ruleset", ""),
            outbound = obj.optLong("outbound", 0L),
            packages = packagesSet,
        )
    }

    fun decodeRules(
        jsonRules: JSONArray,
        legacyBase64Decoder: (String) -> RuleEntity,
    ): List<RuleEntity> {
        val rules = ArrayList<RuleEntity>(jsonRules.length())
        for (i in 0 until jsonRules.length()) {
            when (val item = jsonRules.get(i)) {
                is JSONObject -> rules.add(ruleFromJson(item))
                is String -> rules.add(legacyBase64Decoder(item))
                else -> throw IllegalArgumentException("Unsupported rule backup entry at index $i")
            }
        }
        return rules
    }

    /**
     * Ensures core routing/VPN/DNS settings defaults are explicitly materialized in backup exports
     * so cross-version restores do not silently inherit changed application defaults.
     */
    fun exportSettingsWithDefaults(
        storedPairs: List<KeyValuePair>,
        defaults: Map<String, Any> = captureCurrentCoreDefaults(),
    ): List<KeyValuePair> {
        val merged = LinkedHashMap<String, KeyValuePair>(storedPairs.size + defaults.size)
        for (pair in storedPairs) {
            merged[pair.key] = pair
        }
        for ((key, value) in defaults) {
            if (!merged.containsKey(key)) {
                val pair = KeyValuePair(key)
                when (value) {
                    is Boolean -> pair.put(value)
                    is Long -> pair.put(value)
                    is Int -> pair.put(value.toLong())
                    is Float -> pair.put(value)
                    is String -> pair.put(value)
                    is Set<*> -> pair.put(value.filterIsInstance<String>().toSet())
                    else -> continue
                }
                merged[key] = pair
            }
        }
        return merged.values.toList()
    }

    fun captureCurrentCoreDefaults(): Map<String, Any> = linkedMapOf(
        Key.SMART_MODE to DataStore.smartMode,
        Key.GLOBAL_MODE to DataStore.globalMode,
        Key.SMART_ROUTE_COUNTRY to DataStore.smartRouteCountry,
        Key.BLOCK_QUIC to DataStore.blockQuic,
        Key.TRAFFIC_SNIFFING to DataStore.trafficSniffing.toString(),
        Key.RESOLVE_DESTINATION to DataStore.resolveDestination,
        Key.BYPASS_LAN to DataStore.bypassLan,
        Key.BYPASS_LAN_IN_CORE to DataStore.bypassLanInCore,
        Key.STRICT_ROUTE to DataStore.strictRoute,
        Key.DUAL_NETWORK_ACCELERATION to DataStore.dualNetworkAcceleration,
        Key.CONCURRENT_DIAL to DataStore.concurrentDial,
        Key.ENABLE_DNS_ROUTING to DataStore.enableDnsRouting,
        Key.ENABLE_FAKEDNS to DataStore.enableFakeDns,
        Key.IPV6_MODE to DataStore.ipv6Mode.toString(),
        Key.SERVICE_MODE to DataStore.serviceMode,
        Key.TUN_IMPLEMENTATION to DataStore.tunImplementation.toString(),
        Key.MTU to DataStore.mtu.toString(),
        Key.RULES_PROVIDER to DataStore.rulesProvider.toString(),
        Key.REMOTE_DNS to DataStore.remoteDns,
        Key.DIRECT_DNS to DataStore.directDns,
        Key.CONNECTION_TEST_URL to DataStore.connectionTestURL,
        Key.SMART_AUTO_STOP to DataStore.smartAutoStop,
        Key.SMART_AUTO_STOP_NETWORKS to DataStore.smartAutoStopNetworks,
        Key.NETWORK_CHANGE_RESET_CONNECTIONS to DataStore.networkChangeResetConnections,
        Key.WAKE_RESET_CONNECTIONS to DataStore.wakeResetConnections,
        Key.GLOBAL_ALLOW_INSECURE to DataStore.globalAllowInsecure,
    )
}
