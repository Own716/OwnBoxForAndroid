package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.isIpAddressV6
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.nekohasekai.sagernet.route.RouteRuleEditor

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_FRAGMENT = "fragment"
const val TAG_DNS_HOSTS = "dns-hosts"

const val LOCALHOST = "127.0.0.1"

// Only types backed by the target sing-box endpoint registry belong here. Keeping this
// whitelist explicit prevents a user-supplied legacy/custom outbound from being silently
// reinterpreted as an endpoint.
private val ENDPOINT_TYPES = setOf("wireguard")

private fun SingBoxOption.isGeneratedEndpoint(): Boolean {
    return this is Endpoint && type in ENDPOINT_TYPES
}

internal fun SingBoxOption.detourTo(nextTag: String) {
    if (this is Endpoint_WireGuardOptions) {
        val effectiveOptions = asMap()
        val listenPort = when (val value = effectiveOptions["listen_port"]) {
            is Number -> value.toInt()
            else -> value?.toString()?.toIntOrNull() ?: 0
        }
        if (listenPort > 0) {
            val endpointTag = effectiveOptions["tag"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "<untagged>"
            throw IllegalArgumentException(
                "WireGuard endpoint '$endpointTag' cannot detour to '$nextTag' while " +
                    "listen_port is enabled; set listen_port to 0 or use WireGuard only " +
                    "in a chain position that does not require another hop."
            )
        }

        // sing-box exposes endpoints through OutboundManager, so selectors, URL tests and
        // other dialers can reference this tag directly. WireGuard also embeds DialerOptions,
        // allowing its own outbound connection to follow the existing T4A chain direction.
        detour = nextTag
        return
    }

    _hack_config_map["detour"] = nextTag
}

internal data class ChainHopTag(
    val tag: String,
    val reused: Boolean,
)

internal fun resolveChainHopTag(
    profileId: Long,
    proposedTag: String,
    needGlobal: Boolean,
    globalOutbounds: MutableMap<Long, String>,
): ChainHopTag {
    if (!needGlobal) return ChainHopTag(proposedTag, reused = false)

    val existingTag = globalOutbounds[profileId]
    if (existingTag != null) return ChainHopTag(existingTag, reused = true)

    globalOutbounds[profileId] = proposedTag
    return ChainHopTag(proposedTag, reused = false)
}

internal fun RouteOptions.ensureMainRouteFinal(mainProxyTag: String) {
    if (final_.isNullOrBlank()) final_ = mainProxyTag
}

internal fun buildSelectorOutbound(defaultTag: String?, memberTags: List<String>, customTag: String? = null) =
    Outbound_SelectorOptions().apply {
        type = "selector"
        tag = customTag?.takeIf { it.isNotBlank() } ?: TAG_PROXY
        val uniqueTags = memberTags.distinct()
        default_ = defaultTag?.takeIf { it in uniqueTags }
        // Endpoint tags are valid outbound references in sing-box 1.13; keep them as direct
        // group members instead of wrapping WireGuard in a removed outbound.
        outbounds = uniqueTags
        // 确保手动/策略组切换时不掐断存量连接
        interrupt_exist_connections = false
    }

internal fun buildLoadBalanceOutbound(
    memberTags: List<String>,
    strategy: String? = null,
    testUrl: String? = null,
    intervalSec: Long? = null,
    toleranceMs: Int? = null,
    idleTimeoutStr: String? = null,
    interruptExist: Boolean? = null,
    customTag: String? = null
) =
    Outbound_SelectorOptions().apply {
        type = "loadbalance"
        tag = customTag?.takeIf { it.isNotBlank() } ?: TAG_PROXY
        outbounds = memberTags.distinct()
        // 默认一致性哈希，避免轮询引发出口 IP 突变断流
        this.strategy = strategy ?: "consistent_hash"
        url = testUrl?.takeIf { it.isNotBlank() }
            ?: runCatching { DataStore.connectionTestURL }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "https://cp.cloudflare.com/generate_204"
        val iv = (intervalSec?.takeIf { it > 0 } ?: 60L).coerceAtLeast(10L)
        interval = "${iv}s"
        tolerance = toleranceMs?.takeIf { it >= 0 } ?: 50
        idle_timeout = idleTimeoutStr?.takeIf { it.isNotBlank() } ?: "${iv}s"
        interrupt_exist_connections = interruptExist ?: false
    }

internal fun buildUrlTestOutbound(
    memberTags: List<String>,
    testUrl: String? = null,
    intervalSec: Long? = null,
    toleranceMs: Int? = null,
    idleTimeoutStr: String? = null,
    interruptExist: Boolean? = null,
    customTag: String? = null
) =
    Outbound_URLTestOptions().apply {
        type = "urltest"
        tag = customTag?.takeIf { it.isNotBlank() } ?: TAG_PROXY
        outbounds = memberTags.distinct()
        url = testUrl?.takeIf { it.isNotBlank() }
            ?: runCatching { DataStore.connectionTestURL }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "https://cp.cloudflare.com/generate_204"
        val iv = (intervalSec?.takeIf { it > 0 } ?: 60L).coerceAtLeast(10L)
        interval = "${iv}s"
        tolerance = toleranceMs?.takeIf { it >= 0 } ?: 50
        // Enforce minimum 10-minute idle_timeout regardless of memory mode.
        // This guarantees Telegram file uploads (which can take many minutes) are never
        // interrupted by the kernel reclaiming an "idle" leastPing connection pool.
        // DO NOT lower this threshold for any memory optimization — violates stability contract.
        val rawIdleTimeout = idleTimeoutStr?.takeIf { it.isNotBlank() }
        if (rawIdleTimeout != null) {
            val parsed = if (rawIdleTimeout.all { c -> c.isDigit() }) "${rawIdleTimeout}s" else rawIdleTimeout
            // Parse minutes from string like "3m", "5m", "10m", "30m"; enforce minimum 30m
            val minSecs = 30 * 60
            val parsedSecs = when {
                parsed.endsWith("m") -> (parsed.dropLast(1).toLongOrNull() ?: 0L) * 60
                parsed.endsWith("s") -> parsed.dropLast(1).toLongOrNull() ?: 0L
                else -> 0L
            }
            idle_timeout = if (parsedSecs < minSecs) "30m" else parsed
        } else {
            idle_timeout = "30m"
        }
        // CRITICAL: Never interrupt active connections on node switch.
        // interrupt_exist_connections=true would instantly kill Telegram upload streams on any
        // 5ms latency fluctuation. This must always be false — it is NOT overridden by memory mode.
        interrupt_exist_connections = false
    }

private fun endpointTag(value: Any?): String? {
    return (value as? Map<*, *>)?.get("tag")?.toString()?.takeIf { it.isNotBlank() }
}

private fun mergeEndpointList(
    existing: List<*>, incoming: List<*>, prependNew: Boolean = false
): MutableList<Any?> {
    val result = existing.toMutableList()
    val additions = mutableListOf<Any?>()

    incoming.forEach { endpoint ->
        val tag = endpointTag(endpoint)
        val existingIndex = tag?.let { candidate ->
            result.indexOfFirst { endpointTag(it) == candidate }
        } ?: -1
        val additionIndex = tag?.let { candidate ->
            additions.indexOfFirst { endpointTag(it) == candidate }
        } ?: -1

        when {
            existingIndex >= 0 -> result[existingIndex] = endpoint
            additionIndex >= 0 -> additions[additionIndex] = endpoint
            else -> additions.add(endpoint)
        }
    }

    if (prependNew) result.addAll(0, additions) else result.addAll(additions)
    return result
}

@Suppress("UNCHECKED_CAST")
private fun mergeRootConfig(dst: MutableMap<String, Any?>, json: String) {
    if (json.isBlank()) return
    val source = gson.fromJson(json, dst.javaClass) as? Map<String, Any?> ?: return
    val remaining = source.toMutableMap()

    // Root custom config precedence is automatic < global < selected profile. For endpoints,
    // a later non-empty tag replaces the earlier object in place; distinct/untagged objects
    // coexist. The existing +key/key+ list extension syntax remains prepend/append respectively.
    val replacement = remaining.remove("endpoints")
    val prepended = remaining.remove("+endpoints")
    val appended = remaining.remove("endpoints+")
    Util.mergeMap(dst, remaining)

    fun merge(value: Any?, prependNew: Boolean = false) {
        if (value !is List<*>) {
            if (value != null) dst["endpoints"] = value
            return
        }
        val current = dst["endpoints"] as? List<*> ?: emptyList<Any?>()
        dst["endpoints"] = mergeEndpointList(current, value, prependNew)
    }

    merge(replacement)
    merge(prepended, prependNew = true)
    merge(appended)
}

internal fun finalizeRootConfig(
    options: MyOptions,
    globalCustomConfig: String = "",
    profileCustomConfig: String = "",
): MutableMap<String, Any?> {
    val generatedEndpoints = options.outbounds.orEmpty().filter { it.isGeneratedEndpoint() }
    generatedEndpoints
        .filterIsInstance<Endpoint_WireGuardOptions>()
        .filter { endpoint -> endpoint.asMap()["detour"]?.toString().isNullOrBlank() }
        .forEach { endpoint -> endpoint.detourTo(TAG_DIRECT) }
    options.endpoints = options.endpoints.orEmpty() + generatedEndpoints.map { it as Endpoint }
    options.outbounds = options.outbounds.orEmpty().filterNot { it.isGeneratedEndpoint() }

    val configMap = options.asMap()
    mergeRootConfig(configMap, globalCustomConfig)
    mergeRootConfig(configMap, profileCustomConfig)
    return configMap
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    val balancerMemberMap: Map<Long, List<Long>> = emptyMap(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

private fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size < 2) return@forEach
        val domain = tokens.first()
        val addresses = tokens.drop(1).filter { it.isIpAddress() }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
}

private val BUILTIN_DNS_BOOTSTRAP_HOSTS: Map<String, List<String>> = mapOf(
    "dns.google" to listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
    "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    "one.one.one.one" to listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
    "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
    "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1"),
    "doh.pub" to listOf("1.12.12.12", "120.53.53.53"),
    "dot.pub" to listOf("1.12.12.12", "120.53.53.53")
)

private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        return try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return fallback
}

fun sanitizeSingBoxConfig(rawConfig: String): String {
    if (rawConfig.isBlank()) return rawConfig
    return rawConfig.replace(
        Regex(""""stack"\s*:\s*"(?:gvisor|mixed)"""", RegexOption.IGNORE_CASE),
        """"stack": "go""""
    )
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            val sanitized = sanitizeSingBoxConfig(bean.config)
            return ConfigBuildResult(
                sanitized,
                listOf(),
                proxy.id, //
                mapOf(tagProxy to listOf(proxy)), //
                mapOf(proxy.id to tagProxy), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val balancerMemberMap = HashMap<Long, List<Long>>()
    val globalOutbounds = HashMap<Long, String>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_FRAGMENT, TAG_MIXED, TAG_PROXY)
    val group = SagerDatabase.groupDao.getById(proxy.groupId)
    val groupTag = group?.name?.trim()?.takeIf { it.isNotBlank() } ?: TAG_PROXY
    readableNames.add(groupTag)

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun readableTag(name_: String): String {
        var name = name_
        var count = 0
        while (!readableNames.add(name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    fun ProxyEntity.resolveChain(useGroupFront: Boolean = true, useGroupLanding: Boolean = true): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = if (useGroupFront) thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) } else null
        val landingProxy = if (useGroupLanding) thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) } else null
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val isSmartRouting = !forTest && DataStore.smartMode && !DataStore.globalMode
    val extraRules = when {
        forTest -> listOf()
        isSmartRouting -> io.nekohasekai.sagernet.route.SmartRouteRules.buildSmartRules(DataStore.smartRouteCountry)
        else -> SagerDatabase.rulesDao.enabledRules()
    }
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val isGroupUrlTest = group?.let { DataStore.isGroupUrlTest(it.id) } == true
    val isGroupLoadBalance = group?.let { DataStore.isGroupLoadBalance(it.id) } == true
    val useAutoSelect = !forTest && !forExport && isGroupUrlTest
    val useLoadBalance = !forTest && !forExport && isGroupLoadBalance
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val perGroupResolver = HashMap<Long, String>()
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>()
    val hostResolvers = HashMap<String, MutableSet<String>>()
    val nonCustomFinalHosts = hashSetOf<String>()
    val groupCache = HashMap<Long, ProxyGroup?>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val dnsHosts by lazy {
        val merged = linkedMapOf<String, List<String>>()
        merged.putAll(BUILTIN_DNS_BOOTSTRAP_HOSTS)
        merged.putAll(parseDnsHosts(DataStore.dnsHosts))
        merged
    }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    // sing-box 1.13 已移除 sniff_override_destination（sniff 规则动作不再覆盖目标地址），
    // trafficSniffing 退化为开关语义（>0 即启用）。
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    // 测速配置必须与正式连接一致（对齐 husi）：沿用用户的 IPv6 模式。
    // 曾强制 ENABLE——测速拨号的协议族选择与真实路径不同，
    // v6 不通的网络里测速假 err（节点实际可用），反之假成功。
    val ipv6Mode = DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    // 旧 fork 的 "hosts" DNS 地址 = 系统解析器；官方内核无此 scheme，对应 "local"
    // （官方 legacy 升级会把裸 "hosts" 误判为 UDP 服务器域名，静默失败）。
    // 仅用于 dns-direct / 订阅 resolver 等"本就应本机直解"的场景，远程 DNS 禁止走此函数。
    fun normalizeDnsAddress(address: String): String = if (address == "hosts") "local" else address

    // 远程 DNS 必须由节点代访问（配合下方 detour=当前节点），绝不能归一化为本机直解的 local：
    // 官方内核下 local/hosts/fakeip 都是本机解析占位符（Android 上 local 走平台接口经物理网卡
    // 直连系统 DNS），用作远程即 DNS 泄露，与 fork 时代 hosts 语义不对齐。
    // 统一回退为公共 DoH 并 Toast 提示用户修改设置。
    fun normalizeRemoteDnsAddress(address: String): String {
        return when (address) {
            "hosts", "local", "localhost", "fakeip" -> {
                runOnMainDispatcher {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: \"$address\" is not supported as remote DNS and has been replaced with https://8.8.8.8/dns-query. Please update your remote DNS setting.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                "https://8.8.8.8/dns-query"
            }

            else -> address
        }
    }

    fun extractDnsTargets(dnsList: List<String>, isRemote: Boolean): Pair<Set<String>, Set<String>> {
        val domains = mutableSetOf<String>()
        val ips = mutableSetOf<String>()

        if (isRemote) {
            domains.addAll(
                listOf(
                    "dns.google",
                    "cloudflare-dns.com",
                    "one.one.one.one",
                    "dns.quad9.net",
                    "dns.opendns.com"
                )
            )
            ips.addAll(
                listOf(
                    "8.8.8.8", "8.8.4.4",
                    "1.1.1.1", "1.0.0.1",
                    "9.9.9.9", "149.112.112.112",
                    "208.67.222.222", "208.67.220.220",
                    "2001:4860:4860::8888", "2001:4860:4860::8844",
                    "2606:4700:4700::1111", "2606:4700:4700::1001",
                    "2620:fe::fe", "2620:fe::9",
                    "2620:119:35::35", "2620:119:53::53"
                )
            )
        } else {
            domains.addAll(
                listOf(
                    "dns.alidns.com",
                    "doh.pub",
                    "dot.pub",
                    "doh.360.cn",
                    "dot.360.cn"
                )
            )
            ips.addAll(
                listOf(
                    "223.5.5.5", "223.6.6.6",
                    "119.29.29.29", "1.12.12.12", "120.53.53.53",
                    "114.114.114.114", "114.114.115.115",
                    "180.76.76.76",
                    "1.2.4.8", "210.2.4.8",
                    "2400:3200::1", "2400:3200:baba::1"
                )
            )
        }

        dnsList.forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
            if (trimmed == "local" || trimmed == "hosts" || trimmed == "fakeip") return@forEach

            val host: String? = if (trimmed.contains("://")) {
                val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
                uri?.host ?: trimmed.substringAfter("://").substringBefore("/").substringBefore(":")
            } else {
                val rawNoPath = trimmed.substringBefore("/")
                if (rawNoPath.startsWith("[") && rawNoPath.contains("]")) {
                    rawNoPath.substringAfter("[").substringBefore("]")
                } else if (rawNoPath.contains(":") && rawNoPath.indexOf(":") == rawNoPath.lastIndexOf(":")) {
                    rawNoPath.substringBefore(":")
                } else {
                    rawNoPath
                }
            }

            val cleanHost = host?.trim()?.unwrapIPV6Host()?.lowercase()
            if (!cleanHost.isNullOrBlank()) {
                if (cleanHost.isIpAddress()) {
                    ips.add(cleanHost)
                } else {
                    domains.add(cleanHost)
                }
            }
        }

        val formattedCidrs = ips.mapNotNull { ip ->
            val clean = ip.trim().unwrapIPV6Host()
            if (ipv6Mode == IPv6Mode.DISABLE && clean.isIpAddressV6()) return@mapNotNull null
            if (ipv6Mode == IPv6Mode.ONLY && !clean.isIpAddressV6()) return@mapNotNull null
            if (clean.contains("/")) clean
            else if (clean.isIpAddressV6()) "$clean/128"
            else "$clean/32"
        }.toSet()

        return Pair(domains, formattedCidrs)
    }

    fun buildDnsServer(
        address: String,
        tag: String,
        detour: String? = null,
        domainResolver: String? = null,
        domainStrategy: String? = null
    ): DNSServerOptions {
        val trimmed = address.trim()
        if (trimmed == "local" || trimmed == "hosts") {
            return DNSServerOptions().apply {
                this.type = "local"
                this.tag = tag
                this.detour = detour
            }
        }
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 443
            val path = if (uri != null && !uri.rawPath.isNullOrEmpty()) uri.rawPath else "/dns-query"
            return DNSServerOptions().apply {
                this.type = "https"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.path = path
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        if (trimmed.startsWith("h3://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("h3://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 443
            val path = if (uri != null && !uri.rawPath.isNullOrEmpty()) uri.rawPath else "/dns-query"
            return DNSServerOptions().apply {
                this.type = "h3"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.path = path
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        if (trimmed.startsWith("tls://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("tls://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 853
            return DNSServerOptions().apply {
                this.type = "tls"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        if (trimmed.startsWith("quic://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("quic://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 853
            return DNSServerOptions().apply {
                this.type = "quic"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        if (trimmed.startsWith("tcp://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            val host = uri?.host ?: trimmed.removePrefix("tcp://").substringBefore("/").substringBefore(":")
            val port = if (uri != null && uri.port != -1) uri.port else 53
            return DNSServerOptions().apply {
                this.type = "tcp"
                this.tag = tag
                this.server = host
                this.server_port = port
                this.detour = detour
                if (!host.isIpAddress()) {
                    this.domain_resolver = domainResolver
                    this.domain_strategy = domainStrategy
                }
            }
        }
        val raw = if (trimmed.startsWith("udp://", ignoreCase = true)) trimmed.removePrefix("udp://") else trimmed
        val host: String
        val port: Int
        if (raw.startsWith("[") && raw.contains("]")) {
            host = raw.substringAfter("[").substringBefore("]")
            val after = raw.substringAfter("]")
            port = if (after.startsWith(":") && after.length > 1) after.substring(1).toIntOrNull() ?: 53 else 53
        } else if (raw.contains(":") && raw.indexOf(":") == raw.lastIndexOf(":")) {
            host = raw.substringBefore(":")
            port = raw.substringAfter(":").toIntOrNull() ?: 53
        } else {
            host = raw
            port = 53
        }
        return DNSServerOptions().apply {
            this.type = "udp"
            this.tag = tag
            this.server = host
            this.server_port = port
            this.detour = detour
            if (!host.isIpAddress()) {
                this.domain_resolver = domainResolver
                this.domain_strategy = domainStrategy
            }
        }
    }

    return MyOptions().apply {
        // forTest 不配 experimental：Go 侧 NewTestSingBoxInstance 不注册
        // PlatformLogWriter，官方内核据此不再强制创建 CacheFile/ClashServer
        // （官方 box.go 的 needCacheFile/needClashAPI 分支），测速完全不产生
        // cache.db——曾因此引发主进程并发共享 bbolt 文件损坏闪退，现从根上移除。
        if (!forTest) {
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    // if (DataStore.enableClashAPI) {
                    store_fakeip = true
                    // }
                }

                if (DataStore.enableClashAPI || DataStore.allowAccess) {
                    clash_api = ClashAPIOptions().apply {
                        external_controller = "127.0.0.1:9090"
                        external_ui = "../files/yacd"
                        secret = DataStore.clashApiSecret
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String, isProxied: Boolean = false): String? {
            if (ipv6Mode == IPv6Mode.DISABLE) {
                return "ipv4_only"
            }
            if (ipv6Mode == IPv6Mode.ONLY) {
                return "ipv6_only"
            }
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> if (isProxied) "prefer_ipv4" else "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                interface_name = "tun0"
                // sing-box 1.15 官方全面废弃 stack 字段，底层统一采用高性能原生 sing-tun 协议栈
                stack = null
                // MTU 双端与 VpnService 严格 100% 对齐，完全遵从用户自主配置（默认 1360 安全基准，RFC 8200 限制 1280..9000），
                // 杜绝双端脱节引发的 PMTUD 丢包黑洞，保障全网大文件与视频上传稳定满速。
                mtu = DataStore.mtu.coerceIn(1280, 9000)
                auto_route = true
                strict_route = DataStore.strictRoute
                udp_timeout = 300L
                // 分应用代理防御：同步注入应用包名白名单/黑名单
                if (DataStore.proxyApps) {
                    val individualPkgs = DataStore.individual.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    if (individualPkgs.isNotEmpty()) {
                        if (DataStore.bypass) {
                            exclude_package = individualPkgs
                        } else {
                            include_package = individualPkgs
                        }
                    }
                }
                // sing-box 1.13 移除了入站 sniff/domain_strategy 字段，
                // 改由路由规则动作实现（见下方 route.rules 构建处）；
                // inet4_address/inet6_address 与 endpoint_independent_nat 已于 1.12 移除（构造函数硬报错），
                // address 为合并后的新字段。
                address = when (ipv6Mode) {
                    IPv6Mode.DISABLE -> listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/30")
                    IPv6Mode.ONLY -> listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> listOf(
                        VpnService.PRIVATE_VLAN4_CLIENT + "/30",
                        VpnService.PRIVATE_VLAN6_CLIENT + "/126"
                    )
                }
            })
            if (!DataStore.mixedInboundDisabled) inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                if (DataStore.mixedInboundNeedsAuth) {
                    users = listOf(User().also { u ->
                        u.username = DataStore.mixedUsername
                        u.password = DataStore.mixedPassword
                    })
                }
            })
        }

        endpoints = mutableListOf()
        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            find_process = true
            rules = mutableListOf()
            rule_set = mutableListOf()

            // 默认域名解析器强制走加密抗污染直连 DNS，彻底消除出站节点服务器域名被移动运营商 DNS 劫持污染与死锁
            default_domain_resolver = "dns-direct"

            // 优化 Happy Eyeballs 并发拨号回退延迟（RFC 8305 推荐 100ms，消除上游默认 300ms 累积等待导致的建连迟钝）
            default_fallback_delay = "100ms"

            // 双网络加速与并发拨号策略
            // hybrid: 在所有可用接口并发传输；fallback: 并发快速容灾拨号（Happy Eyeballs）
            if (DataStore.dualNetworkAcceleration) {
                default_network_strategy = "hybrid"
            } else if (DataStore.concurrentDial) {
                default_network_strategy = "fallback"
            }
        }

        // returns outbound tag
        @Suppress("UNCHECKED_CAST")
        fun buildChain(
            chainId: Long,
            entity: ProxyEntity,
            useGroupFront: Boolean = true,
            useGroupLanding: Boolean = true,
            externalDetour: String? = null
        ): String {
            if (entity.type == ProxyEntity.TYPE_BALANCER) {
                val balancerBean = entity.balancerBean ?: (entity.requireBean() as? BalancerBean) ?: BalancerBean()
                val balancerFrontEntity = if (balancerBean.frontProxy > 0L && balancerBean.frontProxy != entity.id) {
                    SagerDatabase.proxyDao.getById(balancerBean.frontProxy)
                } else null
                val balancerLandingEntity = if (balancerBean.landingProxy > 0L && balancerBean.landingProxy != entity.id) {
                    SagerDatabase.proxyDao.getById(balancerBean.landingProxy)
                } else null

                val balancerFrontTag = balancerFrontEntity?.let { front ->
                    tagMap[front.id] ?: buildChain(front.id, front).also { tagMap[front.id] = it }
                }

                val memberEntities = (if (balancerBean.balancerType == BalancerBean.TYPE_GROUP) {
                    val targetGids = when {
                        balancerBean.targetGroupIds.isNotEmpty() -> balancerBean.targetGroupIds
                        balancerBean.targetGroupId > 0L -> listOf(balancerBean.targetGroupId)
                        else -> emptyList()
                    }
                    var list = targetGids.flatMap { gid ->
                        SagerDatabase.proxyDao.getByGroup(gid)
                    }.distinctBy { it.id }

                    if (!balancerBean.nameInclude.isNullOrBlank()) {
                        val regex = runCatching { Regex(balancerBean.nameInclude) }.getOrNull()
                        if (regex != null) {
                            list = list.filter { regex.containsMatchIn(it.displayName()) }
                        }
                    }
                    if (!balancerBean.nameExclude.isNullOrBlank()) {
                        val regex = runCatching { Regex(balancerBean.nameExclude) }.getOrNull()
                        if (regex != null) {
                            list = list.filterNot { regex.containsMatchIn(it.displayName()) }
                        }
                    }
                    list
                } else {
                    val rawEntities = SagerDatabase.proxyDao.getEntities(balancerBean.proxies).associateBy { it.id }
                    balancerBean.proxies.mapNotNull { rawEntities[it] }
                }).filter {
                    it.id != entity.id &&
                    it.id != (balancerFrontEntity?.id ?: 0L) &&
                    it.id != (balancerLandingEntity?.id ?: 0L) &&
                    it.type != ProxyEntity.TYPE_BALANCER &&
                    !DataStore.isGroupDisabled(it.groupId)
                }

                val useFront = if (balancerBean.balancerType == BalancerBean.TYPE_GROUP) balancerBean.useFrontProxy else true
                val useLanding = if (balancerBean.balancerType == BalancerBean.TYPE_GROUP) balancerBean.useLandingProxy else true

                val memberEffectiveDetour = balancerFrontTag ?: externalDetour

                val memberTags = memberEntities.mapNotNull { member ->
                    val customKey = if (memberEffectiveDetour != null) {
                        -member.id
                    } else if (balancerBean.balancerType == BalancerBean.TYPE_GROUP && (!useFront || !useLanding)) {
                        -member.id
                    } else {
                        member.id
                    }
                    tagMap[customKey] ?: buildChain(
                        member.id,
                        member,
                        useGroupFront = if (memberEffectiveDetour != null) false else useFront,
                        useGroupLanding = useLanding,
                        externalDetour = memberEffectiveDetour
                    ).also { tagMap[customKey] = it }
                }.ifEmpty { listOf(TAG_DIRECT) }

                val balancerTag = readableTag(entity.displayName())

                val balancerOutbound: SingBoxOption = if (balancerBean.strategy == "leastPing") {
                    val iv = balancerBean.interval.toLong().coerceAtLeast(10L)
                    val toleranceMs = balancerBean.calculateToleranceMs().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    buildUrlTestOutbound(
                        memberTags = memberTags,
                        testUrl = balancerBean.testUrl,
                        intervalSec = iv,
                        toleranceMs = toleranceMs,
                        idleTimeoutStr = "${iv}s",
                        interruptExist = false,
                        customTag = balancerTag
                    )
                } else {
                    val iv = balancerBean.interval.toLong().coerceAtLeast(10L)
                    val toleranceMs = balancerBean.calculateToleranceMs().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    val strat = when (balancerBean.strategy) {
                        "failover" -> "failover"
                        "stable" -> "stable"
                        "consistent_hash", "consistentHash" -> "consistent_hash"
                        "leastLoad" -> "leastLoad"
                        "round_robin", "roundRobin" -> "round_robin"
                        "random" -> "random"
                        else -> balancerBean.strategy?.takeIf { it.isNotBlank() } ?: "round_robin"
                    }
                    buildLoadBalanceOutbound(
                        memberTags = memberTags,
                        strategy = strat,
                        testUrl = balancerBean.testUrl,
                        intervalSec = iv,
                        toleranceMs = toleranceMs,
                        idleTimeoutStr = "${iv}s",
                        interruptExist = false,
                        customTag = balancerTag
                    )
                }

                outbounds.add(balancerOutbound)
                trafficMap[balancerTag] = listOf(entity)
                balancerMemberMap[entity.id] = memberEntities.map { it.id }

                val finalBalancerTag = if (balancerLandingEntity != null) {
                    val landingTag = buildChain(
                        chainId = -balancerLandingEntity.id,
                        entity = balancerLandingEntity,
                        useGroupFront = false,
                        useGroupLanding = false,
                        externalDetour = balancerTag
                    )
                    landingTag
                } else {
                    balancerTag
                }

                return finalBalancerTag
            }

            val profileList = entity.resolveChain(useGroupFront, useGroupLanding)
            // profileList 的顺序即应用流量经过各 outbound 的顺序：前一跳通过
            // detour 交给后一跳拨号，最后一项直接连接物理网络。
            Logs.d(
                "Outbound chain id=$chainId forTest=$forTest appToEgress=" +
                    profileList.joinToString(" -> ") { hop ->
                        val hopBean = hop.requireBean()
                        val host = serverHostOf(hopBean) ?: "<unknown>"
                        val endpoint = hopBean.displayAddress().takeIf { it.isNotBlank() }
                            ?: "$host:${hopBean.serverPort}"
                        "${hop.id}:${hop.displayType()}@$endpoint"
                    }
            )
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = if (chainId < 0) "c-m${-chainId}" else "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = if (ipv6Mode == IPv6Mode.DISABLE) "ipv4_only" else if (ipv6Mode == IPv6Mode.ONLY) "ipv6_only" else SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()
                bypassDNSBeans += bean

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id

                    val ownerGid = entity.groupId
                    val ownerGroup = groupCache.getOrPut(ownerGid) {
                        SagerDatabase.groupDao.getById(ownerGid)
                    }
                    val resolver = ownerGroup
                        ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                        ?.subscription?.serverDnsResolver
                        ?.let { sanitizeDnsEntry(it) }
                        ?.takeIf { it.isNotBlank() }

                    if (resolver != null) {
                        profileList.forEach { hop ->
                            val host = serverHostOf(hop.requireBean())
                            if (host != null && !host.isIpAddress()) {
                                if (hop.groupId == ownerGid) {
                                    perGroupResolver[ownerGid] = resolver
                                    perGroupServerHosts.getOrPut(ownerGid) { mutableSetOf() }
                                        .add(host)
                                    hostResolvers.getOrPut(host) { mutableSetOf() }.add(resolver)
                                } else {
                                    nonCustomFinalHosts.add(host)
                                }
                            }
                        }
                    } else {
                        profileList.forEach { hop ->
                            val host = serverHostOf(hop.requireBean())
                            if (host != null && !host.isIpAddress()) {
                                nonCustomFinalHosts.add(host)
                            }
                        }
                    }
                }

                if (index == 0) {
                    tagOut = readableTag(bean.displayName())
                }

                // Resolve a globally shared hop before writing the edge that references it.
                // A hop built by an earlier rule may use a readable or endpoint tag instead of
                // this chain's proposed g-<id> tag; the global map is the source of truth.
                val resolvedTag = resolveChainHopTag(
                    proxyEntity.id,
                    tagOut,
                    needGlobal,
                    globalOutbounds,
                )
                tagOut = resolvedTag.tag

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound.detourTo(tagOut)
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // The edge now points at the previously generated final tag, so the duplicate
                // object can be skipped without leaving a dangling detour.
                if (resolvedTag.reused) return@forEachIndexed

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(sanitizeSingBoxConfig(bean.config)) as SingBoxOption

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean -> {
                            val endpointMtu = bean.mtu?.takeIf { it > 0 }
                            val peerAddressFamily = when {
                                bean.serverAddress?.contains(':') == true -> "ipv6"
                                bean.serverAddress?.isNotBlank() == true -> "ipv4-or-domain"
                                else -> "missing"
                            }
                            Logs.i(
                                "WireGuardEndpointTrace profileId=${proxyEntity.id} forTest=$forTest " +
                                    "endpointMtu=${endpointMtu ?: "default(1408)"} " +
                                    "tunMtu=${DataStore.mtu} peerAddressFamily=$peerAddressFamily " +
                                    "peerPort=${bean.serverPort}"
                            )
                            buildSingBoxEndpointWireGuardBean(bean)
                        }

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && DataStore.enableTLSFragment) {
                        val outboundMap = currentOutbound.asMap()
                        val tlsOptions = outboundMap["tls"] as? Map<*, *>
                        if (tlsOptions?.get("enabled") == true) {
                            val delay = DataStore.fragmentInterval.let {
                                val first = it.split("-").firstOrNull()?.trim()
                                val num = first?.toLongOrNull() ?: 20L
                                "${num}ms"
                            }
                            currentOutbound._hack_config_map["tls"] = mapOf(
                                "fragment" to true,
                                "record_fragment" to true,
                                "fragment_fallback_delay" to delay
                            )
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    // 测速配置必须与正式连接一致（对齐 husi）：沿用统一的服务器
                    // 域名解析策略。曾强制空——测速解析出的 IP/协议族与真实路径不同。
                    _hack_config_map["domain_strategy"] = defaultServerDomainStrategy
                    _hack_config_map["fallback_delay"] = "100ms"
                    _hack_config_map["tcp_keep_alive"] = "45s"
                    _hack_config_map["tcp_keep_alive_interval"] = "30s"
                    _hack_config_map["connect_timeout"] = "5s"

                    // 确保所有出站代理节点服务器域名强制使用直连/独立 DNS 解析器（抗运营商污染的加密阿里 DoH 或订阅专属 DNS）解析，
                    // 彻底绕过 FakeIP 与路由分流规则，杜绝内核解析自身节点服务器域名时陷入 FakeIP 闭环回环死锁与国内移动基站 DNS 污染阻断
                    val outboundResolver = if (perGroupResolver.containsKey(proxyEntity.groupId)) {
                        "dns-sub-${proxyEntity.groupId}"
                    } else {
                        "dns-direct"
                    }
                    _hack_config_map["domain_resolver"] = outboundResolver

                    _hack_config_map["tag"] = tagOut
                    _hack_config_map["tcp_multi_path"] = false
                    // 全协议出站 TFO 注入安全策略：
                    // 仅针对标准原生支持 TFO 的 TCP 协议（Shadowsocks, ShadowsocksR, Trojan, SOCKS, HTTP, VMess/VLESS 非 QUIC/SplitHTTP）安全注入 TFO；
                    // AnyTLS、Naive、WireGuard、Hysteria、Hysteria2、TUIC、Juicity、Snell、SSH 以及任何自定义 ConfigBean 严禁强制注入 TFO，
                    // 彻底杜绝内核因协议不支持抛出致命异常（如 "tcp_fast_open is not supported with anytls outbound"）导致所有节点无法连接！
                    val supportsTFO = when (bean) {
                        is ShadowsocksBean, is ShadowsocksRBean, is SOCKSBean -> true
                        is ShadowTLSBean -> false
                        is StandardV2RayBean -> bean.type != "quic" && bean.type != "splithttp" && bean.type != "xhttp"
                        else -> false
                    }
                    if (supportsTFO) {
                        _hack_config_map["tcp_fast_open"] = true
                    } else {
                        _hack_config_map.remove("tcp_fast_open")
                    }

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            if (!externalDetour.isNullOrBlank() && chainOutbounds.isNotEmpty()) {
                chainOutbounds.last().detourTo(externalDetour)
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector || useAutoSelect || useLoadBalance) {
            val rawList = if (group != null && group.id != 0L) {
                SagerDatabase.proxyDao.getByGroup(group.id)
            } else {
                val all = SagerDatabase.proxyDao.getAll()
                if (all.size > 1) all else listOf(proxy)
            }.filter { entity ->
                !DataStore.isGroupDisabled(entity.groupId)
            }.ifEmpty { listOf(proxy) }

            val list = if (useAutoSelect) {
                // 解决策略组初始建立连接慢：在内核首次并发测速出结果前，
                // sing-box 默认将 outbounds[0] 作为首发出站。
                // 优先将此前已知有效且延迟最低的健康节点置于首位，未测试/离线节点排在后面，
                // 确保首包绝不阻塞死等，建立连接瞬时秒开。
                rawList.sortedWith(
                    compareBy<ProxyEntity> { if (it.ping > 0) 0 else 1 }
                        .thenBy { if (it.ping > 0) it.ping else Int.MAX_VALUE }
                )
            } else {
                rawList
            }

            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            if (useAutoSelect && tagMap.isNotEmpty()) {
                val testUrl = group?.let { DataStore.groupUrlTestUrl(it.id) }
                val intervalVal = group?.let { DataStore.groupUrlTestInterval(it.id) }
                val toleranceVal = group?.let { DataStore.groupUrlTestTolerance(it.id) }
                val idleTimeoutVal = group?.let { DataStore.groupUrlTestIdleTimeout(it.id) }
                val interruptVal = group?.let { DataStore.groupUrlTestInterrupt(it.id) }
                outbounds.add(
                    0, buildUrlTestOutbound(
                        tagMap.values.toList(),
                        testUrl = testUrl,
                        intervalSec = intervalVal,
                        toleranceMs = toleranceVal,
                        idleTimeoutStr = idleTimeoutVal,
                        interruptExist = interruptVal,
                        customTag = TAG_PROXY
                    )
                )
            } else if (useLoadBalance && tagMap.isNotEmpty()) {
                outbounds.add(0, buildLoadBalanceOutbound(tagMap.values.toList(), customTag = TAG_PROXY))
                balancerMemberMap[proxy.id] = list.mapNotNull { it.id.takeIf { id -> id != proxy.id } }
            } else {
                outbounds.add(0, buildSelectorOutbound(tagMap[proxy.id], tagMap.values.toList(), customTag = TAG_PROXY))
            }
            trafficMap[TAG_PROXY] = listOf(proxy)
        } else {
            val mainTag = buildChain(0, proxy)
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        val mainProxyTag = (if (buildSelector || useAutoSelect || useLoadBalance) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY

        if (!forTest) {
            // 关键安全隔离：本地回环、多播与链路本地地址强制直连，
            // 彻底防止 TUN 内部回环（TX/RX 对称空耗 50kB/s、CPU 狂飙 340% 的根本原因）
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf(
                    "127.0.0.0/8",
                    "::1/128",
                    "224.0.0.0/4",
                    "169.254.0.0/16",
                    "fe80::/10"
                )
                outbound = TAG_DIRECT
            })

            // 绕过内部网络（无论全局还是规则模式，只要开启绕过局域网均生效）
            if (DataStore.bypassLan) {
                route.rules.add(Rule_DefaultOptions().apply {
                    ip_cidr = listOf(
                        "10.0.0.0/8",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "fc00::/7"
                    )
                    outbound = TAG_DIRECT
                })
            }
        }

        // 在应用用户规则之前检查全局模式
        if (!forTest && DataStore.globalMode) {
            // 全局模式下的规则处理
            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf("tun-in")
                outbound = mainProxyTag
            })

            // 禁用混合入站时不生成入站系的规则
            if (!DataStore.mixedInboundDisabled) route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(TAG_MIXED)
                outbound = mainProxyTag
            })

            route.final_ = mainProxyTag
        } else {
            // 应用用户规则
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val effectivePackages = rule.packages.toMutableSet()
                if (effectivePackages.contains("com.android.vending")) {
                    effectivePackages.add("com.android.providers.downloads")
                    effectivePackages.add("com.android.providers.downloads.ui")
                    effectivePackages.add("com.xiaomi.providers.downloads")
                    effectivePackages.add("com.google.android.gms")
                }
                val officialTelegramPackages = setOf(
                    "org.telegram.messenger",
                    "org.telegram.messenger.web",
                    "org.telegram.messenger.beta"
                )
                if (effectivePackages.any { it in officialTelegramPackages }) {
                    effectivePackages.addAll(officialTelegramPackages)
                }
                val uidList = effectivePackages.mapNotNull {
                    if (!isVPN) {
                        runOnMainDispatcher {
                            Toast.makeText(
                                SagerNet.application,
                                SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    (PackageCache[it] ?: runCatching {
                        SagerNet.application.packageManager.getApplicationInfo(it, 0).uid
                    }.getOrNull())?.takeIf { uid -> uid >= 1000 }
                }.toHashSet().toList()
                val ruleSets = mutableListOf<RuleSet>()

                val domainList = if (rule.domains.isNotBlank()) rule.domains.listByLineOrComma() else null
                val ipList = if (rule.ip.isNotBlank()) rule.ip.listByLineOrComma() else null

                // 存储ruleset标签和类型信息: Pair(tag, isIPRuleset)
                val rulesetTags = mutableListOf<Pair<String, Boolean>>()
                if (rule.ruleset.isNotBlank()) {
                    val rulesetUrls = rule.ruleset.listByLineOrComma()
                    rulesetUrls.forEach { origUrl ->
                        val (url, isIPRuleset, preferredTag) = io.nekohasekai.sagernet.route.RuleSetCatalog.resolveEntry(origUrl)
                        val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval, preferredTag)
                        rulesetTags.add(Pair(tag, isIPRuleset))
                    }
                }

                val hasDomainCriteria = !domainList.isNullOrEmpty()
                val hasIpCriteria = !ipList.isNullOrEmpty() || rulesetTags.any { it.second }
                val hasDomainRuleset = rulesetTags.any { !it.second }
                val isAppOnlyDns =
                    (uidList.isNotEmpty() || effectivePackages.isNotEmpty()) &&
                        !hasDomainCriteria &&
                        !hasIpCriteria &&
                        !hasDomainRuleset &&
                        rule.port.isBlank() &&
                        rule.sourcePort.isBlank() &&
                        rule.network.isBlank() &&
                        rule.source.isBlank() &&
                        rule.protocol.isBlank()
                val routingAction = RouteRuleEditor.action(rule.config, rule.outbound)
                val extendedMatch = RouteRuleEditor.json(rule.config)
                val scopedMatch = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "inbound",
                    "ip_version", "ip_is_private", "source_ip_is_private", "invert", "port", "port_range",
                    "source_ip_cidr", "source_port", "source_port_range")
                val usesDnsOutbound = routingAction in setOf("route", "reject") && scopedMatch.none { extendedMatch.has(it) }
                val shouldAddDnsRule = usesDnsOutbound && (hasDomainCriteria || isAppOnlyDns)

                fun makeDomainDnsRuleObj(): DNSRule_DefaultOptions? {
                    if (!hasDomainCriteria) return null
                    return DNSRule_DefaultOptions().apply {
                        domainList?.let { makeSingBoxRule(it) }
                    }
                }

                fun makeAppDnsRuleObj(): List<DNSRule_DefaultOptions> {
                    val list = mutableListOf<DNSRule_DefaultOptions>()
                    if (effectivePackages.isNotEmpty()) {
                        list.add(DNSRule_DefaultOptions().apply {
                            package_name = effectivePackages.toList()
                        })
                    }
                    if (uidList.isNotEmpty()) {
                        list.add(DNSRule_DefaultOptions().apply {
                            user_id = uidList
                        })
                    }
                    return list
                }

                when (if (routingAction == "reject") -2L else rule.outbound) {
                    -1L -> {
                        if (usesDnsOutbound) {
                            makeDomainDnsRuleObj()?.let { userDNSRuleList += it.apply { server = "dns-direct" } }
                            if (isAppOnlyDns) {
                                makeAppDnsRuleObj().forEach { userDNSRuleList += it.apply { server = "dns-direct" } }
                            }
                        }
                        for ((tag, isIP) in rulesetTags.filter { usesDnsOutbound }) {
                            if (!isIP) {
                                userDNSRuleList += DNSRule_DefaultOptions().apply {
                                    rule_set = mutableListOf(tag)
                                    server = "dns-direct"
                                }
                            }
                        }
                    }

                    -2L -> {
                        if (usesDnsOutbound) {
                            makeDomainDnsRuleObj()?.let { userDNSRuleList += it.apply { action = "reject" } }
                            if (isAppOnlyDns) {
                                makeAppDnsRuleObj().forEach { userDNSRuleList += it.apply { action = "reject" } }
                            }
                        }
                        for ((tag, isIP) in rulesetTags.filter { usesDnsOutbound }) {
                            if (!isIP) {
                                userDNSRuleList += DNSRule_DefaultOptions().apply {
                                    rule_set = mutableListOf(tag)
                                    action = "reject"
                                }
                            }
                        }
                    }

                    else -> {
                        if (usesDnsOutbound) {
                            makeDomainDnsRuleObj()?.let {
                                if (useFakeDns) {
                                    userDNSRuleList += it.apply {
                                        server = "dns-fake"
                                        inbound = listOf("tun-in")
                                        query_type = listOf("A", "AAAA")
                                    }
                                } else {
                                    userDNSRuleList += it.apply { server = "dns-remote" }
                                }
                            }
                            if (isAppOnlyDns) {
                                makeAppDnsRuleObj().forEach {
                                    if (useFakeDns) {
                                        userDNSRuleList += it.apply {
                                            server = "dns-fake"
                                            inbound = listOf("tun-in")
                                            query_type = listOf("A", "AAAA")
                                        }
                                    } else {
                                        userDNSRuleList += it.apply { server = "dns-remote" }
                                    }
                                }
                            }
                        }
                        for ((tag, isIP) in rulesetTags.filter { usesDnsOutbound }) {
                            if (!isIP) {
                                if (useFakeDns) {
                                    userDNSRuleList += DNSRule_DefaultOptions().apply {
                                        rule_set = mutableListOf(tag)
                                        server = "dns-fake"
                                        inbound = listOf("tun-in")
                                        query_type = listOf("A", "AAAA")
                                    }
                                } else {
                                    userDNSRuleList += DNSRule_DefaultOptions().apply {
                                        rule_set = mutableListOf(tag)
                                        server = "dns-remote"
                                    }
                                }
                            }
                        }
                    }
                }

                val targetOutbound = when (val outId = rule.outbound) {
                    0L -> mainProxyTag
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) mainProxyTag else tagMap[outId] ?: ""
                }

                fun applyCommonFilters(ruleObj: Rule_DefaultOptions) {
                    if (rule.port.isNotBlank()) {
                        ruleObj.port = mutableListOf<Int>()
                        ruleObj.port_range = mutableListOf<String>()
                        rule.port.listByLineOrComma().forEach {
                            if (it.contains(":")) {
                                ruleObj.port_range.add(it)
                            } else {
                                it.toIntOrNull()?.let { p -> ruleObj.port.add(p) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        ruleObj.source_port = mutableListOf<Int>()
                        ruleObj.source_port_range = mutableListOf<String>()
                        rule.sourcePort.listByLineOrComma().forEach {
                            if (it.contains(":")) {
                                ruleObj.source_port_range.add(it)
                            } else {
                                it.toIntOrNull()?.let { p -> ruleObj.source_port.add(p) }
                            }
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        ruleObj.network = rule.network.listByLineOrComma()
                    }
                    if (rule.source.isNotBlank()) {
                        ruleObj.source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        ruleObj.protocol = rule.protocol.listByLineOrComma()
                    }
                    if (targetOutbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    } else {
                        ruleObj.outbound = targetOutbound
                    }
                    RouteRuleEditor.applyAction(ruleObj, rule.config)
                }

                val generatedSubRules = mutableListOf<Rule_DefaultOptions>()
                val hasDomain = hasDomainCriteria || hasDomainRuleset
                val hasIp = hasIpCriteria
                val hasApp = uidList.isNotEmpty() || rule.packages.isNotEmpty()

                // Generate independent sub-rules to achieve true OR semantics in sing-box:
                // Traffic matching Domain OR IP OR App will route to targetOutbound independently,
                // without AND criteria deadlocks between package and domain.
                if (hasDomain) {
                    val domainSubRule = Rule_DefaultOptions().apply {
                        domainList?.let { makeSingBoxRule(it, false) }
                        val domainRulesetTags = rulesetTags.filter { !it.second }.map { it.first }
                        if (domainRulesetTags.isNotEmpty()) {
                            rule_set = (rule_set ?: mutableListOf()).apply { addAll(domainRulesetTags) }
                        }
                        if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                        applyCommonFilters(this)
                    }
                    if (!domainSubRule.checkEmpty()) generatedSubRules.add(domainSubRule)
                }

                if (hasIp) {
                    val ipSubRule = Rule_DefaultOptions().apply {
                        ipList?.let { makeSingBoxRule(it, true) }
                        val ipRulesetTags = rulesetTags.filter { it.second }.map { it.first }
                        if (ipRulesetTags.isNotEmpty()) {
                            rule_set = (rule_set ?: mutableListOf()).apply { addAll(ipRulesetTags) }
                        }
                        if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                        applyCommonFilters(this)
                    }
                    if (!ipSubRule.checkEmpty()) generatedSubRules.add(ipSubRule)
                }

                if (hasApp) {
                    val appSubRule = Rule_DefaultOptions().apply {
                        if (uidList.isNotEmpty()) {
                            user_id = uidList
                        }
                        applyCommonFilters(this)
                    }
                    if (!appSubRule.checkEmpty()) generatedSubRules.add(appSubRule)

                    if (effectivePackages.isNotEmpty()) {
                        val pkgSubRule = Rule_DefaultOptions().apply {
                            package_name = effectivePackages.toList()
                            applyCommonFilters(this)
                        }
                        if (!pkgSubRule.checkEmpty()) generatedSubRules.add(pkgSubRule)
                    }
                }

                if (!hasDomain && !hasIp && !hasApp) {
                    val fallbackRule = Rule_DefaultOptions().apply {
                        applyCommonFilters(this)
                    }
                    if (!fallbackRule.checkEmpty()) generatedSubRules.add(fallbackRule)
                }

                val invertedGroup = RouteRuleEditor.invertedGroup(generatedSubRules)
                if (invertedGroup != null) {
                    route.rules.add(invertedGroup)
                    route.rule_set.addAll(ruleSets)
                    continue
                }
                for (subRule in generatedSubRules) {
                    if (subRule.action !in setOf("reject", "sniff", "resolve", "hijack-dns", "route-options") && subRule.outbound.isNullOrBlank()) {
                        runOnMainDispatcher {
                            Toast.makeText(
                                SagerNet.application,
                                "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        route.rules.add(subRule)
                        route.rule_set.addAll(ruleSets)
                        Logs.d("[ConfigBuilder] Route rule '${rule.displayName()}' condition added -> ${subRule.outbound ?: subRule.action}")
                    }
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) {
            outbounds.add(Outbound().apply {
                tag = freedom
                type = "direct"
                // Ensure both direct and bypass outbounds bind to Android default physical network interface
                _hack_config_map["network_strategy"] = "default"
                if (ipv6Mode == IPv6Mode.DISABLE) {
                    _hack_config_map["domain_strategy"] = "ipv4_only"
                } else if (ipv6Mode == IPv6Mode.ONLY) {
                    _hack_config_map["domain_strategy"] = "ipv6_only"
                } else if (ipv6Mode == IPv6Mode.PREFER) {
                    _hack_config_map["domain_strategy"] = "prefer_ipv6"
                }
            })
        }

        fun isExclusiveCustomHost(host: String): Boolean {
            return hostResolvers[host]?.size == 1 && !nonCustomFinalHosts.contains(host)
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                if (!isExclusiveCustomHost(serverAddr)) {
                    domainListDNSDirectForce.add("full:${serverAddr}")
                }
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress() && !dnsHosts.containsKey(host)) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(DNSServerOptions().apply {
            type = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        val directAddress = directDNS.firstOrNull()?.takeIf { it.isNotBlank() } ?: "https://223.5.5.5/dns-query"
        val normalizedDirect = normalizeDnsAddress(directAddress)
        dns.servers.add(
            buildDnsServer(
                address = normalizedDirect,
                tag = "dns-direct",
                detour = TAG_DIRECT,
                domainResolver = "dns-local",
                domainStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct"))
            )
        )

        val remoteAddress = remoteDns.firstOrNull()?.takeIf { it.isNotBlank() } ?: "https://dns.google/dns-query"
        val normalizedRemote = normalizeRemoteDnsAddress(remoteAddress)
        val remoteUri = runCatching { java.net.URI(normalizedRemote) }.getOrNull()
        val remoteHost = remoteUri?.host ?: normalizedRemote.substringAfter("://").substringBefore("/").substringBefore(":")
        val remoteDomainResolver = if (dnsHosts.containsKey(remoteHost)) TAG_DNS_HOSTS else "dns-direct"
        dns.servers.add(
            buildDnsServer(
                address = normalizedRemote,
                tag = "dns-remote",
                detour = mainProxyTag,
                domainResolver = remoteDomainResolver,
                domainStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote"), isProxied = true)
            )
        )
        if (dnsHosts.isNotEmpty()) {
            dns.servers.add(DNSServerOptions().apply {
                type = "hosts"
                tag = TAG_DNS_HOSTS
                _hack_config_map["predefined"] = dnsHosts
            })
        }

        perGroupResolver.forEach { (gid, resolver) ->
            val serverTag = "dns-sub-$gid"
            val address = normalizeDnsAddress(resolver)
            dns.servers.add(
                buildDnsServer(
                    address = address,
                    tag = serverTag,
                    detour = TAG_DIRECT,
                    domainResolver = "dns-direct",
                    domainStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("server"))
                )
            )
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"
        if (ipv6Mode == IPv6Mode.DISABLE) {
            dns.strategy = "ipv4_only"
        } else if (ipv6Mode == IPv6Mode.ONLY) {
            dns.strategy = "ipv6_only"
        } else if (ipv6Mode == IPv6Mode.PREFER) {
            dns.strategy = "prefer_ipv6"
        }

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (it.server == "dns-block") {
                    it.server = null
                    it.action = "reject"
                }
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            val testRules = mutableListOf<DNSRule_DefaultOptions>()
            if (ipv6Mode == IPv6Mode.DISABLE) {
                testRules.add(DNSRule_DefaultOptions().apply {
                    query_type = listOf("AAAA")
                    action = "reject"
                })
            } else if (ipv6Mode == IPv6Mode.ONLY) {
                testRules.add(DNSRule_DefaultOptions().apply {
                    query_type = listOf("A")
                    action = "reject"
                })
            }
            if (dnsHosts.isNotEmpty()) {
                testRules.add(DNSRule_DefaultOptions().apply {
                    server = TAG_DNS_HOSTS
                    makeSingBoxRule(dnsHosts.keys.map { "full:$it" })
                })
            }
            perGroupResolver.forEach { (gid, _) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach
                testRules.add(DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(hosts)
                    server = "dns-sub-$gid"
                    disable_cache = true
                })
            }
            // 确保测试时所有出站代理节点服务器域名强制走 dns-direct 解析，彻底消除自解死锁
            if (domainListDNSDirectForce.isNotEmpty()) {
                testRules.add(DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                    disable_cache = true
                })
            }
            // 兜底直连与系统出站解析，覆盖所有测试出站，杜绝死锁回退至 dns-remote
            testRules.add(DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            dns.rules = testRules
        } else {
            // built-in DNS rules
            if (ipv6Mode == IPv6Mode.DISABLE) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    query_type = listOf("AAAA")
                    action = "reject"
                })
            } else if (ipv6Mode == IPv6Mode.ONLY) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    query_type = listOf("A")
                    action = "reject"
                })
            }

            // Google Play 商店与核心海外服务防误杀 DNS 专线规则（置于 domesticCoreDomainSuffixes 之前）：
            // 确保 googleapis.cn / gvt1-cn.com 不被国内 .cn 规则误判为直连，由海外远程安全 DNS 解析
            val googlePlayDomains = listOf(
                "googleapis.cn",
                "google.cn",
                "play.googleapis.com",
                "market.android.com",
                "android.clients.google.com",
                "playstoregatewayadapter-pa.googleapis.com",
                "firebaselogging-pa.googleapis.com",
                "xn--ngstr-lra8j.com",
                "xn--ngstr-cn-8za9o.com"
            )
            val googlePlayDomainSuffixes = listOf(
                "gvt1.com", "gvt2.com", "gvt3.com", "gvt5.com", "gvt6.com", "gvt7.com", "gvt9.com",
                "gvt1-cn.com", "gvt2-cn.com",
                "googleusercontent.com",
                "ggpht.com"
            )
            val googlePlayPackages = listOf(
                "com.android.vending",
                "com.google.android.gms",
                "com.google.android.gsf"
            )

            dns.rules.add(DNSRule_DefaultOptions().apply {
                domain = googlePlayDomains
                server = "dns-remote"
            })
            dns.rules.add(DNSRule_DefaultOptions().apply {
                domain_suffix = googlePlayDomainSuffixes
                server = "dns-remote"
            })

            // 所属国家/地区核心应用与常用域名后缀静态直连保底名单
            val domesticCoreDomainSuffixes = io.nekohasekai.sagernet.route.SmartRouteRules.domesticCoreDomainSuffixes(DataStore.smartRouteCountry)
            if (!DataStore.globalMode) {
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    domain_suffix = domesticCoreDomainSuffixes
                    server = "dns-direct"
                })
            }

            // 提取 directDNS 与 remoteDns 的域名及 IP/CIDR 目标
            val (rawDirectDomains, directIps) = extractDnsTargets(directDNS, false)
            val (rawRemoteDomains, remoteIps) = extractDnsTargets(remoteDns, true)
            val directDomains = rawDirectDomains - rawRemoteDomains
            val remoteDomains = rawRemoteDomains

            // 构建最优先前置路由规则（须位于所有用户规则之前）
            val topRouteRules = mutableListOf<Rule_DefaultOptions>()

            // 1. hijack-dns 拦截入站 DNS 流量进入内置 DNS 引擎（最高优先级，确保 DNS 解析不被后续嗅探/解析规则阻塞干扰）
            topRouteRules.add(Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            topRouteRules.add(Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })

            // 2. 局域网绕过前置保护：置于用户规则与代理规则之前，防止误吞私网 IP (192.168.x.x 等)
            if (DataStore.bypassLanInCore) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }

            // Telegram 官方数据中心（DC 1~5）及核心媒体 CDN 纯 IP 段
            val telegramDcIps = listOf(
                "91.108.4.0/22",
                "91.108.8.0/22",
                "91.108.12.0/22",
                "91.108.16.0/22",
                "91.108.20.0/22",
                "91.108.56.0/22",
                "149.154.160.0/20",
                "2001:b88::/32",
                "2001:67c:4e8::/48"
            )

            // 3. sing-box 1.13：sniff 嗅探时机前置于所有域名规则之前，确保未开 FakeDNS 时网络包进站瞬间能嗅探出真实域名
            if (needSniff) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_cidr = telegramDcIps
                    invert = true
                    action = "sniff"
                    timeout = "100ms"
                    sniffer = listOf("tls", "http", "quic")
                })
            }

            // 4. resolve 动作：仅当用户显式开启“解析目标地址”时执行
            if (DataStore.resolveDestination) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    action = "resolve"
                    strategy = genDomainStrategy(true)
                })
            }

            // 5. Google Play 商店与全套下载 CDN 专线路由
            topRouteRules.add(Rule_DefaultOptions().apply {
                domain = googlePlayDomains
                outbound = mainProxyTag
            })
            topRouteRules.add(Rule_DefaultOptions().apply {
                domain_suffix = googlePlayDomainSuffixes
                outbound = mainProxyTag
            })
            topRouteRules.add(Rule_DefaultOptions().apply {
                package_name = googlePlayPackages
                outbound = mainProxyTag
            })

            // 6. 本国主流服务直连保底规则（优先于 QUIC 阻断规则，放行国内应用 HTTP/3 直连）
            if (!DataStore.globalMode) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    domain_suffix = domesticCoreDomainSuffixes
                    outbound = TAG_DIRECT
                })
            }

            // 7. 细化拦截 QUIC (UDP 443)：置于国内直连之后，仅阻断走代理流量，迫使海外 TikTok / YouTube 回退至稳定 TCP
            if (DataStore.blockQuic) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    network = listOf("udp")
                    port = listOf(443)
                    action = "reject"
                })
            }

            // 4. IP 版本禁用规则
            if (ipv6Mode == IPv6Mode.DISABLE) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_version = 6
                    action = "reject"
                })
            } else if (ipv6Mode == IPv6Mode.ONLY) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_version = 4
                    action = "reject"
                })
            }

            // 5. 直连 DNS 硬隔离规则（锁定 direct，绝不走代理）
            if (directDomains.isNotEmpty()) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    domain = directDomains.toList()
                    outbound = TAG_DIRECT
                })
            }
            if (directIps.isNotEmpty()) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_cidr = directIps.toList()
                    outbound = TAG_DIRECT
                })
            }

            // 6. 远程 DNS 硬隔离规则（强制锁定 mainProxyTag，绝不回退或走国内直连）
            if (remoteDomains.isNotEmpty()) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    domain = remoteDomains.toList()
                    outbound = mainProxyTag
                })
            }
            if (remoteIps.isNotEmpty()) {
                topRouteRules.add(Rule_DefaultOptions().apply {
                    ip_cidr = remoteIps.toList()
                    outbound = mainProxyTag
                })
            }

            route.rules.addAll(0, topRouteRules)

            // Telegram 官方数据中心（DC 1~5）兜底规则：置于所有用户自定义分流规则之后，
            // 确保用户为 Telegram 配置的指定节点规则优先命中，未配置时兜底走主代理
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = telegramDcIps
                outbound = mainProxyTag
            })

            // block mcast: 终端单播发送组播请求，仅匹配目标 IP，移除导致逻辑永远不命中的 source_ip_cidr
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj (sing-box 1.14: fakeip configured as a server in dns.servers)
            if (useFakeDns) {
                dns.servers.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    inet4_range = "198.18.0.0/15"
                    if (ipv6Mode != IPv6Mode.DISABLE) {
                        inet6_range = "fc00::/18"
                    }
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    disable_cache = true
                    query_type = when (ipv6Mode) {
                        IPv6Mode.DISABLE -> listOf("A")
                        IPv6Mode.ONLY -> listOf("AAAA")
                        else -> listOf("A", "AAAA")
                    }
                })
            }
            if (dnsHosts.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    server = TAG_DNS_HOSTS
                    makeSingBoxRule(dnsHosts.keys.map { "full:$it" })
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                    disable_cache = true
                })
            }
            perGroupResolver.forEach { (gid, _) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach

                val serverTag = "dns-sub-$gid"
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(hosts)
                    server = serverTag
                })
            }
        }

        if (!forTest && ipv6Mode == IPv6Mode.DISABLE) {
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                query_type = listOf("AAAA")
                action = "reject"
            })
        } else if (!forTest && ipv6Mode == IPv6Mode.ONLY) {
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                query_type = listOf("A")
                action = "reject"
            })
        }

        // Synchronize route.rules and route.final_ against available outbound tags to avoid "tag not found"
        val availableOutboundTags = outbounds.mapNotNull { it.asMap()["tag"]?.toString() }.toSet()
        route.rules.filterIsInstance<Rule_DefaultOptions>().forEach { r ->
            val out = r.outbound
            if (!out.isNullOrBlank() && out !in availableOutboundTags) {
                if (out == TAG_PROXY && availableOutboundTags.contains(groupTag)) {
                    r.outbound = groupTag
                } else if (out == groupTag && availableOutboundTags.contains(TAG_PROXY)) {
                    r.outbound = TAG_PROXY
                } else if (group?.name?.isNotBlank() == true && out == group.name && availableOutboundTags.contains(groupTag)) {
                    r.outbound = groupTag
                } else {
                    Logs.w("ConfigBuilder: rule outbound tag '$out' not found in outbounds, fallback to '$mainProxyTag'")
                    r.outbound = mainProxyTag
                }
            }
        }
        if (!route.final_.isNullOrBlank() && route.final_ !in availableOutboundTags) {
            route.final_ = mainProxyTag
        }

        // Legacy outbounds implicitly used their first item as the default route. Endpoints are
        // partitioned out of that list, so an unset final would silently fall back to direct.
        route.ensureMainRouteFinal(mainProxyTag)
        val routeFinalState = when (route.final_) {
            null, "" -> "unset"
            mainProxyTag -> "main"
            else -> "other"
        }
        Logs.i(
            "RouteFinalTrace profileId=${proxy.id} forTest=$forTest " +
                "globalMode=${!forTest && DataStore.globalMode} selector=$buildSelector " +
                "finalState=$routeFinalState ruleCount=${route.rules.size}"
        )

    }.let { options ->
        val configMap = finalizeRootConfig(
            options,
            globalCustomConfig = if (forTest) "" else DataStore.globalCustomConfig,
            profileCustomConfig = proxy.requireBean().customConfigJson,
        )
        val endpointTags = (configMap["endpoints"] as? List<*>)
            .orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("tag")?.toString() }
        val outboundNodes = (configMap["outbounds"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
        val outboundTags = outboundNodes.mapNotNull { it["tag"]?.toString() }
        val availableTags = (endpointTags + outboundTags).toSet()
        val references = buildList {
            val endpointNodes = (configMap["endpoints"] as? List<*>)
                .orEmpty()
                .mapNotNull { it as? Map<*, *> }
            (endpointNodes + outboundNodes).forEach { node ->
                val source = node["tag"]?.toString() ?: "<untagged>"
                node["detour"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                    add("$source->$it")
                }
                if (node["type"] == "selector") {
                    (node["outbounds"] as? List<*>)?.forEach { target ->
                        target?.toString()?.takeIf { it.isNotBlank() }?.let {
                            add("$source=>$it")
                        }
                    }
                }
            }
            val route = configMap["route"] as? Map<*, *>
            route?.get("final")?.toString()?.takeIf { it.isNotBlank() }?.let {
                add("route.final->$it")
            }
        }
        val unresolvedTargets = references.mapNotNull { reference ->
            reference.substringAfterLast("->", reference.substringAfterLast("=>"))
                .takeUnless { it in availableTags }
        }.distinct()
        Logs.i(
            "ChainTopologyTrace profileId=${proxy.id} forTest=$forTest " +
                "endpoints=$endpointTags outbounds=$outboundTags " +
                "references=$references unresolved=$unresolvedTargets"
        )
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector || useAutoSelect || useLoadBalance) group?.id ?: 0L else -1L,
            balancerMemberMap
        )
    }

}
