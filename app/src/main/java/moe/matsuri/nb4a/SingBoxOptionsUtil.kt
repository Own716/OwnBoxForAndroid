package moe.matsuri.nb4a

import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.SingBoxOptions.RuleSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import kotlin.Exception

object SingBoxOptionsUtil {

    fun domainStrategy(tag: String): String {
        if (DataStore.ipv6Mode == io.nekohasekai.sagernet.IPv6Mode.DISABLE) {
            return "ipv4_only"
        }
        if (DataStore.ipv6Mode == io.nekohasekai.sagernet.IPv6Mode.ONLY) {
            return "ipv6_only"
        }
        fun auto2(key: String, newS: String): String {
            return (DataStore.configurationStore.getString(key) ?: "").replace("auto", newS)
        }
        return when (tag) {
            "dns-remote" -> {
                auto2("domain_strategy_for_remote", "")
            }

            "dns-direct" -> {
                auto2("domain_strategy_for_direct", "")
            }

            // server
            else -> {
                auto2("domain_strategy_for_server", if (DataStore.ipv6Mode == io.nekohasekai.sagernet.IPv6Mode.PREFER) "prefer_ipv6" else "prefer_ipv4")
            }
        }
    }

}

fun SingBoxOptions.DNSRule_DefaultOptions.makeSingBoxRule(list: List<String>) {
    rule_set = mutableListOf<String>()
    domain = mutableListOf<String>()
    domain_suffix = mutableListOf<String>()
    domain_regex = mutableListOf<String>()
    domain_keyword = mutableListOf<String>()
    list.forEach {
        if (it.startsWith("geosite:") || it.startsWith("geosite-")) {
            rule_set.plusAssign(it)
        } else if (it.startsWith("full:")) {
            domain.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix.plusAssign(it.lowercase())
        }
    }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.DNSRule_DefaultOptions.checkEmpty(): Boolean {
    if (rule_set?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    if (package_name?.isNotEmpty() == true) return false
    return true
}

fun generateRuleSet(ruleSetString: List<String>, ruleSet: MutableList<RuleSet>) {
    val extDir = SagerNet.application.getExternalFilesDir(null) ?: SagerNet.application.filesDir
    val filesDir = SagerNet.application.filesDir
    val hasGeoipDb = java.io.File(extDir, "geoip.db").isFile || java.io.File(filesDir, "geoip.db").isFile
    val hasGeositeDb = java.io.File(extDir, "geosite.db").isFile || java.io.File(filesDir, "geosite.db").isFile
    val interval = io.nekohasekai.sagernet.route.RuleSets.normalizeUpdateInterval(DataStore.rulesUpdateInterval)

    ruleSetString.forEach { entry ->
        if (ruleSet.any { it.tag == entry }) return@forEach
        when {
            entry.startsWith("geoip:") || entry.startsWith("geoip-") -> {
                val catalogUrl = io.nekohasekai.sagernet.route.RuleSetCatalog.current().urlOf(entry)
                if (!hasGeoipDb && catalogUrl != null) {
                    val effectiveUrl = io.nekohasekai.sagernet.route.RuleSets.mirrorLink(catalogUrl, io.nekohasekai.sagernet.route.RuleSets.MIRROR_CLOUDFLARE)
                    ruleSet.add(RuleSet().apply {
                        type = "remote"
                        tag = entry
                        format = "binary"
                        url = effectiveUrl
                        update_interval = interval
                        download_detour = "proxy"
                    })
                } else {
                    ruleSet.add(RuleSet().apply {
                        type = "local"
                        tag = entry
                        format = "binary"
                        path = entry
                    })
                }
            }

            entry.startsWith("geosite:") || entry.startsWith("geosite-") -> {
                val catalogUrl = io.nekohasekai.sagernet.route.RuleSetCatalog.current().urlOf(entry)
                if (!hasGeositeDb && catalogUrl != null) {
                    val effectiveUrl = io.nekohasekai.sagernet.route.RuleSets.mirrorLink(catalogUrl, io.nekohasekai.sagernet.route.RuleSets.MIRROR_CLOUDFLARE)
                    ruleSet.add(RuleSet().apply {
                        type = "remote"
                        tag = entry
                        format = "binary"
                        url = effectiveUrl
                        update_interval = interval
                        download_detour = "proxy"
                    })
                } else {
                    ruleSet.add(RuleSet().apply {
                        type = "local"
                        tag = entry
                        format = "binary"
                        path = entry
                    })
                }
            }
        }
    }
}

fun SingBoxOptions.Rule_DefaultOptions.makeSingBoxRule(list: List<String>, isIP: Boolean) {
    if (isIP) {
        if (ip_cidr == null) ip_cidr = mutableListOf<String>()
        if (rule_set == null) rule_set = mutableListOf<String>()
    } else {
        if (rule_set == null) rule_set = mutableListOf<String>()
        if (domain == null) domain = mutableListOf<String>()
        if (domain_suffix == null) domain_suffix = mutableListOf<String>()
        if (domain_regex == null) domain_regex = mutableListOf<String>()
        if (domain_keyword == null) domain_keyword = mutableListOf<String>()
    }
    list.forEach {
        if (isIP) {
            if (it.startsWith("geoip:") || it.startsWith("geoip-")) {
                if (it == "geoip:private" || it == "geoip-private") {
                    ip_is_private = true
                } else {
                    rule_set.plusAssign(it)
                }
            } else {
                ip_cidr.plusAssign(it)
            }
            return@forEach
        }
        if (it.startsWith("geosite:") || it.startsWith("geosite-")) {
            rule_set.plusAssign(it)
        } else if (it.startsWith("full:")) {
            domain.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix.plusAssign(it.lowercase())
        }
    }
    ip_cidr?.removeIf { it.isNullOrBlank() }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (ip_cidr?.isEmpty() == true) ip_cidr = null
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.Rule_DefaultOptions.checkEmpty(): Boolean {
    if (ip_cidr?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    if (package_name?.isNotEmpty() == true) return false
    if (process_name?.isNotEmpty() == true) return false
    if (protocol?.isNotEmpty() == true) return false
    if (network?.isNotEmpty() == true) return false
    //
    if (port?.isNotEmpty() == true) return false
    if (port_range?.isNotEmpty() == true) return false
    if (source_port?.isNotEmpty() == true) return false
    if (source_port_range?.isNotEmpty() == true) return false
    if (source_ip_cidr?.isNotEmpty() == true) return false
    //
    if (!_hack_custom_config.isNullOrBlank()) return false
    return true
}

fun processRulesetUrl(origUrl: String): Pair<String, Boolean> {
    val resolved = io.nekohasekai.sagernet.route.RuleSetCatalog.resolveEntry(origUrl)
    return Pair(resolved.first, resolved.second)
}

fun generateRemoteRuleSet(
    url: String,
    ruleSets: MutableList<RuleSet>,
    updateInterval: String,
    preferredTag: String? = null
): String {
    val cleanUrl = if (url.startsWith("file://")) url.removePrefix("file://") else url
    val localFile = java.io.File(cleanUrl)
    val isLocal = localFile.isAbsolute && (localFile.exists() || cleanUrl.startsWith("/"))
    val tag = preferredTag?.takeIf { it.isNotBlank() } ?: run {
        val hashCode = kotlin.math.abs(url.hashCode())
        "ruleset-$hashCode"
    }

    if (ruleSets.none { it.tag == tag }) {
        if (isLocal) {
            ruleSets.add(RuleSet().apply {
                type = "local"
                this.tag = tag
                format = if (cleanUrl.endsWith(".json")) "source" else "binary"
                path = cleanUrl
            })
        } else {
            val normalizedInterval = io.nekohasekai.sagernet.route.RuleSets.normalizeUpdateInterval(updateInterval)
            val effectiveUrl = io.nekohasekai.sagernet.route.RuleSets.mirrorLink(url, io.nekohasekai.sagernet.route.RuleSets.MIRROR_CLOUDFLARE)
            val isJson = effectiveUrl.contains(".json", ignoreCase = true)
            ruleSets.add(RuleSet().apply {
                type = "remote"
                this.tag = tag
                format = if (isJson) "source" else "binary"
                this.url = effectiveUrl
                update_interval = normalizedInterval
                download_detour = "proxy"
            })
        }
    }

    return tag
}
