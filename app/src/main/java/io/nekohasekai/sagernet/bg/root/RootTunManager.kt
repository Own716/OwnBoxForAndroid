package io.nekohasekai.sagernet.bg.root

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RouteRuleEditor
import io.nekohasekai.sagernet.utils.PackageCache
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Manages Root-privileged transparent proxy modes (`Root TUN`, `Root Redirect`, `Root Mixed`,
 * `Root Enhance`), `hev-socks5-tunnel`, `iptables` / `ip rule` setup, multi-user UID resolution,
 * and idempotent teardown.
 */
object RootTunManager {

    enum class RootMode {
        TUN,
        REDIRECT,
        MIXED,
        ENHANCE;

        val needsHevtun: Boolean get() = this != REDIRECT
        val usesTcpRedirect: Boolean get() = this != TUN

        companion object {
            fun fromServiceMode(serviceMode: String): RootMode = when (serviceMode) {
                Key.MODE_ROOT_REDIRECT -> REDIRECT
                Key.MODE_ROOT_MIXED -> MIXED
                Key.MODE_ROOT_ENHANCE -> ENHANCE
                else -> TUN
            }
        }
    }

    const val HEVTUN_SO_NAME = "libhevsockstun.so"
    const val TUN_IFACE = "ownbox0"

    // Dedicated subnet isolated from VpnService (172.19.0.1/30) and FakeDNS (198.18.0.0/15)
    const val TUN_ADDR_V4 = "172.20.0.1"
    const val TUN_PREFIX_V4 = 30
    const val TUN_ADDR_V6 = "fdfe:dcba:9877::1"
    const val TUN_PREFIX_V6 = 126

    const val FWMARK = "0x2024"
    const val ROUTE_TABLE = "2024"
    const val RULE_PRIORITY = "1000"
    const val LEGACY_RULE_PRIORITY = "9000"
    const val DEFAULT_LAN_DNS = "1.1.1.1"
    const val OOM_SCORE_ADJ = "-1000"
    private const val PER_USER_RANGE = 100000

    const val CHAIN_OUT_MANGLE = "OWNBOX_ROOT_OUT"
    const val CHAIN_PRE_MANGLE = "OWNBOX_ROOT_PRE"
    const val CHAIN_NAT_OUT = "OWNBOX_ROOT_DNS"
    const val CHAIN_DNS_PRE = "OWNBOX_ROOT_DNS_PRE"
    const val CHAIN_REDIR_NAT = "OWNBOX_ROOT_REDIR"
    const val CHAIN_NAT_POST = "OWNBOX_ROOT_NAT"
    const val CHAIN_FWD_FILTER = "OWNBOX_ROOT_FWD"
    const val CHAIN_OUT_FILTER = "OWNBOX_ROOT_FLT"
    const val CHAIN_BLK6_OUT = "OWNBOX_ROOT_BLK6"
    const val CHAIN_BLK6_FWD = "OWNBOX_ROOT_BLK6_FWD"

    const val PID_FILE_NAME = "ownbox_hevtun.pid"
    const val CONFIG_FILE_NAME = "ownbox_hevtun.yaml"
    const val LOG_FILE_NAME = "ownbox_hevtun.log"
    const val OOM_GUARD_PID_NAME = "ownbox_oomguard.pid"
    const val ACTIVE_FLAG_NAME = "ownbox_root_active.flag"

    private val LAN_SHARE_PREFS = listOf(5000, 5010, 5020, 5025, 5026, 5027, 5030, 5040, 5050, 6000)

    private val GOOGLE_PLAY_SUPPORT_PACKAGES = listOf(
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.xiaomi.providers.downloads",
        "com.oplus.providers.downloads",
        "com.coloros.providers.downloads",
        "com.google.android.gsf",
        "com.google.android.gms",
    )

    private val LAN_CIDRS_V4 = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "172.16.0.0/12",
        "192.168.0.0/16",
    )

    private val HOTSPOT_SOURCE_CIDRS_V4 = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
    )

    private val RESERVED_CIDRS_V4 = listOf(
        "0.0.0.0/8",
        "169.254.0.0/16",
        "224.0.0.0/4",
        "240.0.0.0/4",
    )

    private val BYPASS_CIDRS_V6 = listOf(
        "::1/128",
        "fe80::/10",
        "fc00::/7",
        "ff00::/8",
    )

    private const val CREDENTIAL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val secureRandom = SecureRandom()
    private val USER_INFO_REGEX = Regex("""UserInfo\{(\d+):""")
    private val USER_HANDLE_REGEX = Regex("""(\d+)""")

    data class StartParams(
        val mode: RootMode = RootMode.TUN,
        val ownUid: Int,
        val corePid: Int = 0,
        val socksPort: Int = 0,
        val socksUsername: String = "",
        val socksPassword: String = "",
        val redirectPort: Int = 0,
        val dnsRedirectPort: Int = 0,
        val lanDnsRedirectPort: Int = 0,
        val dnsDnatTargetV4: String = DEFAULT_LAN_DNS,
        val dnsDnatTargetV6: String? = null,
        val mtu: Int = 1360,
        val enableIpv6: Boolean = false,
        val bypassLan: Boolean = true,
        val blockQuic: Boolean = true,
        val rootLanSharing: Boolean = false,
        val perAppEnabled: Boolean = false,
        val perAppBypass: Boolean = true,
        val perAppUids: Set<Int> = emptySet(),
        val directBypassUids: Set<Int> = emptySet(),
        val blockedUids: Set<Int> = emptySet(),
        val tunName: String = TUN_IFACE,
        val hevtunBinPath: String = "",
        val yamlPath: String = "",
        val pidPath: String = "",
        val logPath: String = "",
        val oomGuardPidPath: String = "",
    ) {
        val effectiveLanSharing: Boolean get() = rootLanSharing || mode == RootMode.ENHANCE
    }

    /**
     * Generates a random alphanumeric secret for loopback SOCKS5 authentication.
     */
    fun generateSecret(length: Int = 24): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(CREDENTIAL_ALPHABET[secureRandom.nextInt(CREDENTIAL_ALPHABET.length)])
        }
        return sb.toString()
    }

    /**
     * Locates the extracted `libhevsockstun.so` binary in the app native library directory.
     */
    fun findHevtunBinary(context: Context): String? {
        val candidate = File(context.applicationInfo.nativeLibraryDir, HEVTUN_SO_NAME)
        return if (candidate.isFile) candidate.absolutePath else null
    }

    /**
     * Parses `DataStore.individual` and applies Google Play support package expansion when requested
     * (should be true only in per-app whitelist mode, never in bypass/blacklist mode).
     */
    fun expandPackageNames(individualRaw: String, expandGooglePlay: Boolean = true): Set<String> {
        val raw = individualRaw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        if (expandGooglePlay && "com.android.vending" in raw) {
            raw.addAll(GOOGLE_PLAY_SUPPORT_PACKAGES)
        }
        return raw
    }

    private fun collectPurePackageRules(
        rules: List<RuleEntity>,
        predicate: (RuleEntity) -> Boolean,
    ): Set<String> {
        val result = LinkedHashSet<String>()
        for (rule in rules) {
            if (predicate(rule)) {
                result.addAll(rule.packages)
            }
        }
        return result
    }

    /**
     * Collects packages from enabled rules that are strictly package-only Direct (`outbound == -1`)
     * rules so apps like QQ/WeChat are bypassed directly in iptables regardless of Smart-Route mode.
     */
    fun collectPureDirectRoutePackages(rules: List<RuleEntity>): Set<String> =
        collectPurePackageRules(rules) { isPureDirectPackageRule(it) }

    /**
     * Collects packages from enabled rules that are strictly package-only Block (`outbound == -2`
     * or `action == "reject"`) rules for native iptables REJECT enforcement in Root modes.
     */
    fun collectPureBlockRoutePackages(rules: List<RuleEntity>): Set<String> =
        collectPurePackageRules(rules) { isPureBlockPackageRule(it) }

    /**
     * Returns true when [rule] carries any match condition besides app package names.
     */
    fun hasNonPackageCriteria(rule: RuleEntity): Boolean {
        if (rule.domains.isNotBlank() ||
            rule.ip.isNotBlank() ||
            rule.port.isNotBlank() ||
            rule.sourcePort.isNotBlank() ||
            rule.network.isNotBlank() ||
            rule.source.isNotBlank() ||
            rule.protocol.isNotBlank() ||
            rule.ruleset.isNotBlank()
        ) {
            return true
        }
        if (rule.config.isBlank()) return false
        val obj = runCatching { RouteRuleEditor.json(rule.config) }.getOrNull() ?: return true
        val actionOptionKeys = setOf("action", "method", "no_drop")
        val matchKeys = RouteRuleEditor.lists + RouteRuleEditor.booleans + setOf("ip_version", "rule_set", "rules")
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in matchKeys || key !in actionOptionKeys) {
                return true
            }
        }
        return false
    }

    /**
     * Returns true when [rule] is a bypass rule filtering solely by package names.
     */
    fun isPureDirectPackageRule(rule: RuleEntity, requireEnabled: Boolean = true): Boolean {
        return (!requireEnabled || rule.enabled) &&
            rule.outbound == -1L &&
            rule.packages.isNotEmpty() &&
            RouteRuleEditor.action(rule.config, rule.outbound) == "route" &&
            !hasNonPackageCriteria(rule)
    }

    /**
     * Returns true when [rule] is a reject/block rule filtering solely by package names.
     */
    fun isPureBlockPackageRule(rule: RuleEntity, requireEnabled: Boolean = true): Boolean {
        return (!requireEnabled || rule.enabled) &&
            rule.packages.isNotEmpty() &&
            RouteRuleEditor.action(rule.config, rule.outbound) == "reject" &&
            !hasNonPackageCriteria(rule)
    }

    /**
     * Parses `pm list users` output into Android user IDs (always containing user 0).
     */
    fun parseUserIdsFromPmOutput(output: String): Set<Int> {
        val ids = LinkedHashSet<Int>()
        ids.add(0)
        USER_INFO_REGEX.findAll(output).forEach { match ->
            match.groupValues[1].toIntOrNull()?.let { ids.add(it) }
        }
        return ids
    }

    /**
     * Expands each UID across all present Android user spaces (e.g. Dual App user 999)
     * so cloned/dual apps (like dual QQ/WeChat) share the same bypass or proxy targeting.
     */
    fun expandMultiUserUids(baseUids: Set<Int>, userIds: Set<Int>, ownUid: Int): Set<Int> {
        val effectiveUsers = if (userIds.isEmpty()) setOf(0) else (userIds + 0)
        val expanded = LinkedHashSet<Int>()
        for (uid in baseUids) {
            if (uid <= 0) continue
            val appId = uid % PER_USER_RANGE
            if (uid != ownUid) {
                expanded.add(uid)
            }
            for (userId in effectiveUsers) {
                if (userId < 0) continue
                val candidate = userId * PER_USER_RANGE + appId
                if (candidate > 0 && candidate != ownUid) {
                    expanded.add(candidate)
                }
            }
        }
        return expanded
    }

    /**
     * Collects Android user profile IDs (primary user 0 + work profile + Dual App user 999).
     */
    fun resolveAllUserIds(context: Context): Set<Int> {
        val ids = LinkedHashSet<Int>()
        ids.add(0)
        runCatching {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            um?.userProfiles?.forEach { handle: UserHandle ->
                val raw = USER_HANDLE_REGEX.find(handle.toString())?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (raw != null && raw >= 0) {
                    ids.add(raw)
                }
            }
        }
        runCatching {
            val res = RootShell.runCommandResult("pm list users", timeoutSeconds = 3)
            if (res.isSuccess) {
                ids.addAll(parseUserIdsFromPmOutput(res.output))
            }
        }
        return ids
    }

    /**
     * Resolves package names in [packageNames] to UIDs across all [userIds], excluding [ownUid].
     */
    fun resolvePackageUids(
        context: Context,
        packageNames: Set<String>,
        ownUid: Int,
        userIds: Set<Int> = setOf(0),
    ): Set<Int> {
        if (packageNames.isEmpty()) return emptySet()
        PackageCache.awaitLoadSync()
        val pm = context.packageManager
        val baseUids = LinkedHashSet<Int>(packageNames.size)
        for (pkg in packageNames) {
            val uid = PackageCache[pkg] ?: runCatching {
                pm.getApplicationInfo(pkg, 0).uid
            }.getOrNull()
            if (uid != null && uid > 0) {
                baseUids.add(uid)
            }
        }
        return expandMultiUserUids(baseUids, userIds, ownUid)
    }

    /**
     * Resolves `DataStore.individual` into multi-user UIDs with optional Google Play expansion.
     */
    fun resolvePerAppUids(
        context: Context,
        individualRaw: String,
        ownUid: Int,
        userIds: Set<Int> = setOf(0),
        expandGooglePlay: Boolean = true,
    ): Set<Int> {
        return resolvePackageUids(context, expandPackageNames(individualRaw, expandGooglePlay), ownUid, userIds)
    }

    /**
     * Extracts the first plain IPv4 or IPv6 literal from a newline-delimited remote DNS list.
     * Used as a public DNAT target for private LAN resolver queries so `geoip:private` never leaks them.
     */
    fun extractPlainDnsIp(remoteDnsRaw: String, ipv6: Boolean): String? {
        for (rawLine in remoteDnsRaw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.contains("://") || line.contains("/")) continue
            val host = line.substringBefore("#").trim()
                .removePrefix("[")
                .substringBefore("]")
            if (!ipv6 && !host.contains(":") && host.count { it == '.' } == 3) {
                val validV4 = runCatching { InetAddress.getByName(host) is Inet4Address }.getOrDefault(false)
                if (validV4) return host
            } else if (ipv6 && host.contains(":")) {
                val validV6 = runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
                if (validV6) return host
            }
        }
        return null
    }

    /**
     * Builds the `hev-socks5-tunnel` YAML configuration file content aligned with `v2rayNG`.
     */
    fun buildHevConfig(params: StartParams): String {
        val sb = StringBuilder()
        sb.append("tunnel:\n")
        sb.append("  name: ").append(yamlSingleQuote(params.tunName)).append('\n')
        sb.append("  mtu: ").append(params.mtu.coerceIn(576, 65535)).append('\n')
        sb.append("  multi-queue: true\n")
        if (params.effectiveLanSharing) {
            sb.append("  offload:\n")
            sb.append("    jumbo: false\n")
            if (params.enableIpv6) {
                sb.append("    tso: false\n")
            }
        }
        sb.append("  ipv4: ").append(yamlSingleQuote(TUN_ADDR_V4)).append('\n')
        if (params.enableIpv6) {
            sb.append("  ipv6: ").append(yamlSingleQuote(TUN_ADDR_V6)).append('\n')
        }
        sb.append('\n')
        sb.append("socks5:\n")
        sb.append("  port: ").append(params.socksPort).append('\n')
        sb.append("  address: '127.0.0.1'\n")
        sb.append("  udp: 'udp'\n")
        if (params.socksUsername.isNotEmpty()) {
            sb.append("  username: ").append(yamlSingleQuote(params.socksUsername)).append('\n')
            sb.append("  password: ").append(yamlSingleQuote(params.socksPassword)).append('\n')
        }
        sb.append("  tcp-fastopen: false\n")
        return sb.toString()
    }

    /**
     * Atomically writes [content] to [targetFile] with owner-only `0600` permissions.
     */
    fun writeRestrictedFile(targetFile: File, content: String) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File.createTempFile("${targetFile.name}.tmp.", "", parent)
        try {
            tmpFile.setReadable(false, false)
            tmpFile.setReadable(true, true)
            tmpFile.setWritable(false, false)
            tmpFile.setWritable(true, true)
            tmpFile.setExecutable(false, false)
            tmpFile.writeText(content.replace("\r\n", "\n"), StandardCharsets.UTF_8)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tmpFile.renameTo(targetFile)) {
                targetFile.writeText(content.replace("\r\n", "\n"), StandardCharsets.UTF_8)
                targetFile.setReadable(false, false)
                targetFile.setReadable(true, true)
                targetFile.setWritable(false, false)
                targetFile.setWritable(true, true)
                targetFile.setExecutable(false, false)
            }
        } finally {
            if (tmpFile.exists()) {
                runCatching { tmpFile.delete() }
            }
        }
    }

    /**
     * Builds the idempotent shell setup script for the selected [RootMode].
     */
    fun buildSetupScript(params: StartParams): String {
        val sortedBypassUids = if (!params.perAppEnabled || params.perAppBypass) {
            (params.perAppUids + params.directBypassUids)
                .filter { it > 0 && it != params.ownUid }
                .distinct()
                .sorted()
        } else {
            emptyList()
        }
        val sortedAllowUids = if (params.perAppEnabled && !params.perAppBypass) {
            params.perAppUids
                .filter { it > 0 && it != params.ownUid }
                .distinct()
                .sorted()
        } else {
            emptyList()
        }
        val sortedBlockedUids = params.blockedUids
            .filter { it > 0 && it != params.ownUid }
            .distinct()
            .sorted()
        val emptyWhitelist = params.perAppEnabled && !params.perAppBypass && sortedAllowUids.isEmpty()

        val sb = StringBuilder()
        sb.append("set -u\n")
        sb.append("TUN=").append(shellSingleQuote(params.tunName)).append('\n')
        sb.append("HEV_BIN=").append(shellSingleQuote(params.hevtunBinPath)).append('\n')
        sb.append("YAML_PATH=").append(shellSingleQuote(params.yamlPath)).append('\n')
        sb.append("PID_PATH=").append(shellSingleQuote(params.pidPath)).append('\n')
        sb.append("LOG_PATH=").append(shellSingleQuote(params.logPath)).append('\n')
        sb.append("OOM_PID_PATH=").append(shellSingleQuote(params.oomGuardPidPath)).append("\n\n")

        appendTeardownBody(
            sb = sb,
            tunName = params.tunName,
            pidPath = params.pidPath,
            oomGuardPidPath = params.oomGuardPidPath,
            corePid = params.corePid,
            yamlPath = params.yamlPath,
            logPath = params.logPath,
            removeYaml = false,
            removeLog = false,
        )

        sb.append("# Disable global rp_filter so asymmetric marked / PREROUTING / REDIRECT traffic is never dropped\n")
        sb.append("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true\n\n")

        if (params.corePid > 0 && params.oomGuardPidPath.isNotBlank()) {
            sb.append("# Pin app core process against Android LMK\n")
            sb.append("nohup sh -c 'while true; do echo ").append(OOM_SCORE_ADJ)
                .append(" > /proc/").append(params.corePid)
                .append("/oom_score_adj 2>/dev/null; sleep 5; done' >/dev/null 2>&1 &\n")
            sb.append("echo \$! > \"\$OOM_PID_PATH\"\n\n")
        }

        if (params.mode.needsHevtun) {
            appendHevtunLaunch(sb, params)
        }

        val redirectExemptUids = (sortedBypassUids + sortedBlockedUids).distinct().sorted()
        if (params.mode.usesTcpRedirect) {
            appendTcpRedirectNat(sb, params, redirectExemptUids, sortedAllowUids, emptyWhitelist)
        }

        if (params.mode.needsHevtun) {
            appendMangleV4(sb, params, redirectExemptUids, sortedAllowUids, emptyWhitelist)
            if (!emptyWhitelist) {
                appendDnsDnatV4(sb, params)
            }
        }

        appendFilterOutputV4(sb, params, sortedBypassUids, sortedAllowUids, sortedBlockedUids, emptyWhitelist)

        if (params.effectiveLanSharing) {
            appendLanSharingSetup(sb, params)
        }

        appendIpv6Setup(sb, params, sortedBypassUids, sortedAllowUids, sortedBlockedUids, emptyWhitelist)
        return sb.toString()
    }

    private fun appendHevtunLaunch(sb: StringBuilder, params: StartParams) {
        val mtu = params.mtu.coerceIn(576, 65535)
        sb.append("# Ensure /dev/net/tun device node exists\n")
        sb.append("if [ ! -c /dev/net/tun ]; then\n")
        sb.append("  mkdir -p /dev/net\n")
        sb.append("  if [ -c /dev/tun ]; then\n")
        sb.append("    ln -sf /dev/tun /dev/net/tun\n")
        sb.append("  else\n")
        sb.append("    mknod /dev/net/tun c 10 200 2>/dev/null || true\n")
        sb.append("  fi\n")
        sb.append("  chmod 0666 /dev/net/tun 2>/dev/null || true\n")
        sb.append("fi\n\n")

        sb.append("# Start hev-socks5-tunnel daemon\n")
        if (params.logPath.isNotBlank()) {
            sb.append("nohup \"\$HEV_BIN\" \"\$YAML_PATH\" >\"\$LOG_PATH\" 2>&1 &\n")
        } else {
            sb.append("nohup \"\$HEV_BIN\" \"\$YAML_PATH\" >/dev/null 2>&1 &\n")
        }
        sb.append("T2S_PID=\$!\n")
        sb.append("echo \"\$T2S_PID\" > \"\$PID_PATH\"\n")
        sb.append("echo ").append(OOM_SCORE_ADJ).append(" > /proc/\"\$T2S_PID\"/oom_score_adj 2>/dev/null || true\n\n")

        sb.append("i=0\n")
        sb.append("while [ \$i -lt 20 ]; do\n")
        sb.append("  if ip link show \"\$TUN\" >/dev/null 2>&1; then\n")
        sb.append("    break\n")
        sb.append("  fi\n")
        sb.append("  sleep 0.3 2>/dev/null || sleep 1\n")
        sb.append("  i=\$((i + 1))\n")
        sb.append("done\n")
        sb.append("if ! ip link show \"\$TUN\" >/dev/null 2>&1; then\n")
        sb.append("  echo \"TUN interface \$TUN failed to appear\" >&2\n")
        if (params.logPath.isNotBlank()) {
            sb.append("  cat \"\$LOG_PATH\" >&2 2>/dev/null || true\n")
        }
        sb.append("  exit 1\n")
        sb.append("fi\n\n")

        sb.append("# Disable rp_filter on both TUN and all so marked asymmetric traffic is never dropped\n")
        sb.append("echo 0 > /proc/sys/net/ipv4/conf/\"\$TUN\"/rp_filter 2>/dev/null || true\n")
        sb.append("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true\n")
        sb.append("ip -4 addr add ").append(TUN_ADDR_V4).append('/').append(TUN_PREFIX_V4).append(" dev \"\$TUN\" 2>/dev/null || true\n")
        sb.append("ip link set dev \"\$TUN\" up mtu ").append(mtu).append(" || exit 1\n")
        sb.append("ip -4 route replace default dev \"\$TUN\" table ").append(ROUTE_TABLE).append(" || exit 1\n")
        sb.append("ip -4 rule add fwmark ").append(FWMARK).append(" table ").append(ROUTE_TABLE).append(" priority ").append(RULE_PRIORITY).append(" || exit 1\n")

        if (params.enableIpv6) {
            sb.append("if [ -d /proc/sys/net/ipv6 ]; then\n")
            sb.append("  ip -6 addr add ").append(TUN_ADDR_V6).append('/').append(TUN_PREFIX_V6).append(" dev \"\$TUN\" 2>/dev/null || true\n")
            sb.append("  ip -6 route replace default dev \"\$TUN\" table ").append(ROUTE_TABLE).append(" 2>/dev/null || true\n")
            sb.append("  ip -6 rule add fwmark ").append(FWMARK).append(" table ").append(ROUTE_TABLE).append(" priority ").append(RULE_PRIORITY).append(" 2>/dev/null || true\n")
            sb.append("fi\n")
        }
        sb.append('\n')
    }

    private fun appendTcpRedirectNat(
        sb: StringBuilder,
        params: StartParams,
        sortedBypassUids: List<Int>,
        sortedAllowUids: List<Int>,
        emptyWhitelist: Boolean,
    ) {
        if (params.redirectPort <= 0) return
        sb.append("# IPv4 nat OUTPUT TCP REDIRECT (append at end after netd chains)\n")
        sb.append("iptables -w 5 -t nat -N ").append(CHAIN_REDIR_NAT).append(" || exit 1\n")
        sb.append("iptables -w 5 -t nat -F ").append(CHAIN_REDIR_NAT).append(" || exit 1\n")
        sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -o lo -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -d 127.0.0.0/8 -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -d ").append(TUN_ADDR_V4).append('/').append(TUN_PREFIX_V4).append(" -j RETURN || exit 1\n")
        for (uid in sortedBypassUids) {
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN || exit 1\n")
        }
        if (!emptyWhitelist) {
            if (params.mode == RootMode.REDIRECT && params.dnsRedirectPort > 0) {
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -p udp --dport 53 -j REDIRECT --to-ports ").append(params.dnsRedirectPort).append(" || exit 1\n")
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -p tcp --dport 53 -j REDIRECT --to-ports ").append(params.dnsRedirectPort).append(" || exit 1\n")
            } else if (params.mode != RootMode.REDIRECT) {
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -p tcp --dport 53 -j REDIRECT --to-ports ").append(params.redirectPort).append(" || exit 1\n")
            }
        }
        for (cidr in RESERVED_CIDRS_V4) {
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -d ").append(cidr).append(" -j RETURN || exit 1\n")
        }
        if (params.bypassLan) {
            for (cidr in LAN_CIDRS_V4) {
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -d ").append(cidr).append(" -j RETURN || exit 1\n")
            }
        }
        if (!params.perAppEnabled || params.perAppBypass) {
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -p tcp -j REDIRECT --to-ports ").append(params.redirectPort).append(" || exit 1\n")
        } else {
            for (uid in sortedAllowUids) {
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_REDIR_NAT).append(" -p tcp -m owner --uid-owner ").append(uid).append(" -j REDIRECT --to-ports ").append(params.redirectPort).append(" || exit 1\n")
            }
        }
        sb.append("iptables -w 5 -t nat -A OUTPUT -j ").append(CHAIN_REDIR_NAT).append(" || exit 1\n\n")
    }

    private fun appendMangleV4(
        sb: StringBuilder,
        params: StartParams,
        sortedBypassUids: List<Int>,
        sortedAllowUids: List<Int>,
        emptyWhitelist: Boolean,
    ) {
        val protoFlag = if (params.mode == RootMode.TUN) "" else "-p udp "
        sb.append("# IPv4 mangle OUTPUT (append at end after Android netd fwmark rules)\n")
        sb.append("iptables -w 5 -t mangle -N ").append(CHAIN_OUT_MANGLE).append(" || exit 1\n")
        sb.append("iptables -w 5 -t mangle -F ").append(CHAIN_OUT_MANGLE).append(" || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m mark --mark ").append(FWMARK).append(" -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -o lo -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d 127.0.0.0/8 -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -o \"\$TUN\" -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d ").append(TUN_ADDR_V4).append('/').append(TUN_PREFIX_V4).append(" -j RETURN || exit 1\n")
        for (uid in sortedBypassUids) {
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN || exit 1\n")
        }
        if (!emptyWhitelist) {
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p udp --dport 53 -j MARK --set-xmark ").append(FWMARK).append(" || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p udp --dport 53 -j RETURN || exit 1\n")
            if (params.mode == RootMode.TUN) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p tcp --dport 53 -j MARK --set-xmark ").append(FWMARK).append(" || exit 1\n")
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p tcp --dport 53 -j RETURN || exit 1\n")
            }
        }
        for (cidr in RESERVED_CIDRS_V4) {
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d ").append(cidr).append(" -j RETURN || exit 1\n")
        }
        if (params.bypassLan) {
            for (cidr in LAN_CIDRS_V4) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d ").append(cidr).append(" -j RETURN || exit 1\n")
            }
        }
        if (!params.perAppEnabled || params.perAppBypass) {
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(' ').append(protoFlag).append("-j MARK --set-xmark ").append(FWMARK).append(" || exit 1\n")
        } else {
            for (uid in sortedAllowUids) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(' ').append(protoFlag).append("-m owner --uid-owner ").append(uid).append(" -j MARK --set-xmark ").append(FWMARK).append(" || exit 1\n")
            }
        }
        sb.append("iptables -w 5 -t mangle -A OUTPUT -j ").append(CHAIN_OUT_MANGLE).append(" || exit 1\n\n")
    }

    private fun appendDnsDnatV4(sb: StringBuilder, params: StartParams) {
        val dnsTarget = params.dnsDnatTargetV4.ifBlank { DEFAULT_LAN_DNS }
        sb.append("# Rewrite only marked LAN-resolver DNS queries to a public IP so geoip:private stays accurate\n")
        sb.append("iptables -w 5 -t nat -N ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
        sb.append("iptables -w 5 -t nat -F ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
        for (cidr in LAN_CIDRS_V4) {
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -m mark --mark ").append(FWMARK)
                .append(" -d ").append(cidr).append(" -p udp --dport 53 -j DNAT --to-destination ").append(dnsTarget).append(" 2>/dev/null || true\n")
            if (params.mode == RootMode.TUN) {
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -m mark --mark ").append(FWMARK)
                    .append(" -d ").append(cidr).append(" -p tcp --dport 53 -j DNAT --to-destination ").append(dnsTarget).append(" 2>/dev/null || true\n")
            }
        }
        sb.append("iptables -w 5 -t nat -A OUTPUT -j ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n\n")
    }

    private fun appendFilterOutputV4(
        sb: StringBuilder,
        params: StartParams,
        sortedBypassUids: List<Int>,
        sortedAllowUids: List<Int>,
        sortedBlockedUids: List<Int>,
        emptyWhitelist: Boolean,
    ) {
        val enforceQuicReject = params.blockQuic && !emptyWhitelist
        if (sortedBlockedUids.isEmpty() && !enforceQuicReject) return
        sb.append("# IPv4 filter OUTPUT (enforce pure block app rules and fast ICMP QUIC fallback)\n")
        sb.append("iptables -w 5 -t filter -N ").append(CHAIN_OUT_FILTER).append(" 2>/dev/null || true\n")
        sb.append("iptables -w 5 -t filter -F ").append(CHAIN_OUT_FILTER).append(" 2>/dev/null || true\n")
        sb.append("iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN 2>/dev/null || true\n")
        sb.append("iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -o lo -j RETURN 2>/dev/null || true\n")
        sb.append("iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -d 127.0.0.0/8 -j RETURN 2>/dev/null || true\n")
        for (uid in sortedBlockedUids) {
            sb.append("(iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -m owner --uid-owner ").append(uid)
                .append(" -j REJECT --reject-with icmp-admin-prohibited 2>/dev/null || iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER)
                .append(" -m owner --uid-owner ").append(uid).append(" -j DROP 2>/dev/null || true)\n")
        }
        if (enforceQuicReject) {
            for (uid in sortedBypassUids) {
                sb.append("iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN 2>/dev/null || true\n")
            }
            for (cidr in RESERVED_CIDRS_V4) {
                sb.append("iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -d ").append(cidr).append(" -j RETURN 2>/dev/null || true\n")
            }
            if (params.bypassLan) {
                for (cidr in LAN_CIDRS_V4) {
                    sb.append("iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -d ").append(cidr).append(" -j RETURN 2>/dev/null || true\n")
                }
            }
            if (!params.perAppEnabled || params.perAppBypass) {
                sb.append("(iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER)
                    .append(" -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable 2>/dev/null || iptables -w 5 -t filter -A ")
                    .append(CHAIN_OUT_FILTER).append(" -p udp --dport 443 -j DROP 2>/dev/null || true)\n")
            } else {
                for (uid in sortedAllowUids) {
                    sb.append("(iptables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -p udp --dport 443 -m owner --uid-owner ").append(uid)
                        .append(" -j REJECT --reject-with icmp-port-unreachable 2>/dev/null || iptables -w 5 -t filter -A ")
                        .append(CHAIN_OUT_FILTER).append(" -p udp --dport 443 -m owner --uid-owner ").append(uid).append(" -j DROP 2>/dev/null || true)\n")
                }
            }
        }
        sb.append("iptables -w 5 -t filter -A OUTPUT -j ").append(CHAIN_OUT_FILTER).append(" 2>/dev/null || true\n\n")
    }

    private fun appendLanSharingSetup(sb: StringBuilder, params: StartParams) {
        sb.append("# Hotspot / USB transparent sharing (best-effort)\n")
        sb.append("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true\n")
        if (params.mode.needsHevtun) {
            val dnsTarget = params.dnsDnatTargetV4.ifBlank { DEFAULT_LAN_DNS }
            sb.append("iptables -w 5 -N ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -F ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -A ").append(CHAIN_FWD_FILTER).append(" -i \"\$TUN\" -j ACCEPT 2>/dev/null || true\n")
            sb.append("iptables -w 5 -A ").append(CHAIN_FWD_FILTER).append(" -o \"\$TUN\" -j ACCEPT 2>/dev/null || true\n")
            sb.append("iptables -w 5 -I FORWARD -j ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t mangle -A FORWARD -o \"\$TUN\" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350 2>/dev/null || true\n")

            // Route hotspot client traffic via fwmark -> table 2024 instead of source-based ip rules
            sb.append("iptables -w 5 -t mangle -N ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t mangle -F ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -i \"\$TUN\" -j RETURN 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -i lo -j RETURN 2>/dev/null || true\n")
            for (cidr in HOTSPOT_SOURCE_CIDRS_V4) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -s ").append(cidr)
                    .append(" -p udp --dport 53 -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -s ").append(cidr)
                    .append(" -p udp --dport 53 -j RETURN 2>/dev/null || true\n")
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -s ").append(cidr)
                    .append(" -p tcp --dport 53 -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -s ").append(cidr)
                    .append(" -p tcp --dport 53 -j RETURN 2>/dev/null || true\n")
            }
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -m addrtype --dst-type LOCAL -j RETURN 2>/dev/null || true\n")
            for (cidr in RESERVED_CIDRS_V4 + LAN_CIDRS_V4 + listOf("127.0.0.0/8")) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d ").append(cidr)
                    .append(" -j RETURN 2>/dev/null || true\n")
            }
            for (cidr in HOTSPOT_SOURCE_CIDRS_V4) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -s ").append(cidr)
                    .append(" -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
            }
            sb.append("iptables -w 5 -t mangle -A PREROUTING -j ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")

            sb.append("iptables -w 5 -t nat -N ").append(CHAIN_DNS_PRE).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -F ").append(CHAIN_DNS_PRE).append(" 2>/dev/null || true\n")
            for (cidr in LAN_CIDRS_V4) {
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_DNS_PRE).append(" -m mark --mark ").append(FWMARK)
                    .append(" ! -i \"\$TUN\" -d ").append(cidr).append(" -p udp --dport 53 -j DNAT --to-destination ").append(dnsTarget).append(" 2>/dev/null || true\n")
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_DNS_PRE).append(" -m mark --mark ").append(FWMARK)
                    .append(" ! -i \"\$TUN\" -d ").append(cidr).append(" -p tcp --dport 53 -j DNAT --to-destination ").append(dnsTarget).append(" 2>/dev/null || true\n")
            }
            sb.append("iptables -w 5 -t nat -A PREROUTING -j ").append(CHAIN_DNS_PRE).append(" 2>/dev/null || true\n\n")
        } else if (params.redirectPort > 0) {
            val lanDnsPort = if (params.lanDnsRedirectPort > 0) params.lanDnsRedirectPort else params.dnsRedirectPort
            sb.append("iptables -w 5 -t nat -N ").append(CHAIN_DNS_PRE).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -F ").append(CHAIN_DNS_PRE).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_DNS_PRE).append(" -i lo -j RETURN 2>/dev/null || true\n")
            if (lanDnsPort > 0) {
                for (cidr in HOTSPOT_SOURCE_CIDRS_V4) {
                    sb.append("iptables -w 5 -t nat -A ").append(CHAIN_DNS_PRE).append(" -s ").append(cidr)
                        .append(" -p udp --dport 53 -j REDIRECT --to-ports ").append(lanDnsPort).append(" 2>/dev/null || true\n")
                    sb.append("iptables -w 5 -t nat -A ").append(CHAIN_DNS_PRE).append(" -s ").append(cidr)
                        .append(" -p tcp --dport 53 -j REDIRECT --to-ports ").append(lanDnsPort).append(" 2>/dev/null || true\n")
                }
            }
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_DNS_PRE).append(" -m addrtype --dst-type LOCAL -j RETURN 2>/dev/null || true\n")
            for (cidr in RESERVED_CIDRS_V4 + LAN_CIDRS_V4 + listOf("127.0.0.0/8")) {
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_DNS_PRE).append(" -d ").append(cidr).append(" -j RETURN 2>/dev/null || true\n")
            }
            for (cidr in HOTSPOT_SOURCE_CIDRS_V4) {
                sb.append("iptables -w 5 -t nat -A ").append(CHAIN_DNS_PRE).append(" -s ").append(cidr)
                    .append(" -p tcp -j REDIRECT --to-ports ").append(params.redirectPort).append(" 2>/dev/null || true\n")
            }
            sb.append("iptables -w 5 -t nat -A PREROUTING -j ").append(CHAIN_DNS_PRE).append(" 2>/dev/null || true\n\n")
        }
    }

    private fun appendIpv6Setup(
        sb: StringBuilder,
        params: StartParams,
        sortedBypassUids: List<Int>,
        sortedAllowUids: List<Int>,
        sortedBlockedUids: List<Int>,
        emptyWhitelist: Boolean,
    ) {
        val mangleExemptUids = (sortedBypassUids + sortedBlockedUids).distinct().sorted()
        val enforceQuicReject = params.blockQuic && !emptyWhitelist
        sb.append("if [ -d /proc/sys/net/ipv6 ]; then\n")
        if (params.enableIpv6 && params.mode.needsHevtun) {
            sb.append("  ip6tables -w 5 -t mangle -N ").append(CHAIN_OUT_MANGLE).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -F ").append(CHAIN_OUT_MANGLE).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m mark --mark ").append(FWMARK).append(" -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -o lo -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -o \"\$TUN\" -j RETURN 2>/dev/null || true\n")
            for (uid in mangleExemptUids) {
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN 2>/dev/null || true\n")
            }
            if (!emptyWhitelist) {
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p udp --dport 53 -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p tcp --dport 53 -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
            }
            for (cidr in BYPASS_CIDRS_V6) {
                if (cidr == "fc00::/7" && !params.bypassLan) continue
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d ").append(cidr).append(" -j RETURN 2>/dev/null || true\n")
            }
            if (!params.perAppEnabled || params.perAppBypass) {
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
            } else {
                for (uid in sortedAllowUids) {
                    sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(uid).append(" -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
                }
            }
            sb.append("  ip6tables -w 5 -t mangle -A OUTPUT -j ").append(CHAIN_OUT_MANGLE).append(" 2>/dev/null || true\n")

            if (sortedBlockedUids.isNotEmpty() || enforceQuicReject) {
                sb.append("  ip6tables -w 5 -t filter -N ").append(CHAIN_OUT_FILTER).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -F ").append(CHAIN_OUT_FILTER).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -o lo -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -d ::1/128 -j RETURN 2>/dev/null || true\n")
                for (uid in sortedBlockedUids) {
                    sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -m owner --uid-owner ").append(uid)
                        .append(" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null || ip6tables -w 5 -t filter -A ")
                        .append(CHAIN_OUT_FILTER).append(" -m owner --uid-owner ").append(uid).append(" -j DROP 2>/dev/null || true)\n")
                }
                if (enforceQuicReject) {
                    for (uid in sortedBypassUids) {
                        sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN 2>/dev/null || true\n")
                    }
                    for (cidr in BYPASS_CIDRS_V6) {
                        if (cidr == "fc00::/7" && !params.bypassLan) continue
                        sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -d ").append(cidr).append(" -j RETURN 2>/dev/null || true\n")
                    }
                    if (!params.perAppEnabled || params.perAppBypass) {
                        sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER)
                            .append(" -p udp --dport 443 -j REJECT --reject-with icmp6-port-unreachable 2>/dev/null || ip6tables -w 5 -t filter -A ")
                            .append(CHAIN_OUT_FILTER).append(" -p udp --dport 443 -j DROP 2>/dev/null || true)\n")
                    } else {
                        for (uid in sortedAllowUids) {
                            sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_OUT_FILTER).append(" -p udp --dport 443 -m owner --uid-owner ").append(uid)
                                .append(" -j REJECT --reject-with icmp6-port-unreachable 2>/dev/null || ip6tables -w 5 -t filter -A ")
                                .append(CHAIN_OUT_FILTER).append(" -p udp --dport 443 -m owner --uid-owner ").append(uid).append(" -j DROP 2>/dev/null || true)\n")
                        }
                    }
                }
                sb.append("  ip6tables -w 5 -t filter -A OUTPUT -j ").append(CHAIN_OUT_FILTER).append(" 2>/dev/null || true\n")
            }

            if (!emptyWhitelist && !params.dnsDnatTargetV6.isNullOrBlank()) {
                sb.append("  if ip6tables -w 5 -t nat -S OUTPUT >/dev/null 2>&1; then\n")
                sb.append("    ip6tables -w 5 -t nat -N ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
                sb.append("    ip6tables -w 5 -t nat -F ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
                for (cidr in BYPASS_CIDRS_V6) {
                    sb.append("    ip6tables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -m mark --mark ").append(FWMARK)
                        .append(" -d ").append(cidr).append(" -p udp --dport 53 -j DNAT --to-destination ").append(params.dnsDnatTargetV6).append(" 2>/dev/null || true\n")
                    sb.append("    ip6tables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -m mark --mark ").append(FWMARK)
                        .append(" -d ").append(cidr).append(" -p tcp --dport 53 -j DNAT --to-destination ").append(params.dnsDnatTargetV6).append(" 2>/dev/null || true\n")
                }
                sb.append("    ip6tables -w 5 -t nat -A OUTPUT -j ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
                sb.append("  fi\n")
            }

            if (params.effectiveLanSharing) {
                sb.append("  ip6tables -w 5 -N ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -F ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -A ").append(CHAIN_BLK6_FWD).append(" -i \"\$TUN\" -j ACCEPT 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -A ").append(CHAIN_BLK6_FWD).append(" -o \"\$TUN\" -j ACCEPT 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -A ").append(CHAIN_BLK6_FWD).append(" -j REJECT --reject-with icmp6-no-route 2>/dev/null || ip6tables -w 5 -A ").append(CHAIN_BLK6_FWD).append(" -j DROP 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -I FORWARD -j ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")

                sb.append("  ip6tables -w 5 -t mangle -N ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -F ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -i \"\$TUN\" -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -i lo -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -m addrtype --dst-type LOCAL -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -p udp --dport 53 -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -p udp --dport 53 -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -p tcp --dport 53 -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -p tcp --dport 53 -j RETURN 2>/dev/null || true\n")
                for (cidr in BYPASS_CIDRS_V6) {
                    sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d ").append(cidr).append(" -j RETURN 2>/dev/null || true\n")
                }
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -j MARK --set-xmark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A PREROUTING -j ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")
            }
        } else {
            sb.append("  ip6tables -w 5 -t filter -N ").append(CHAIN_BLK6_OUT).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -F ").append(CHAIN_BLK6_OUT).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m mark --mark ").append(FWMARK).append(" -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -o lo -j RETURN 2>/dev/null || true\n")
            for (cidr in BYPASS_CIDRS_V6) {
                sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -d ").append(cidr).append(" -j RETURN 2>/dev/null || true\n")
            }
            for (uid in sortedBlockedUids) {
                sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(uid)
                    .append(" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null || ip6tables -w 5 -t filter -A ")
                    .append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j DROP 2>/dev/null || true)\n")
            }
            for (uid in sortedBypassUids) {
                sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN 2>/dev/null || true\n")
            }
            if (params.perAppEnabled && !params.perAppBypass) {
                for (uid in sortedAllowUids) {
                    sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null || ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j DROP 2>/dev/null || true)\n")
                }
            } else {
                sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null || ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -j DROP 2>/dev/null || true)\n")
            }
            sb.append("  ip6tables -w 5 -t filter -A OUTPUT -j ").append(CHAIN_BLK6_OUT).append(" 2>/dev/null || true\n")

            if (params.effectiveLanSharing) {
                sb.append("  ip6tables -w 5 -N ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -F ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")
                sb.append("  (ip6tables -w 5 -A ").append(CHAIN_BLK6_FWD).append(" -j REJECT --reject-with icmp6-no-route 2>/dev/null || ip6tables -w 5 -A ").append(CHAIN_BLK6_FWD).append(" -j DROP 2>/dev/null || true)\n")
                sb.append("  ip6tables -w 5 -I FORWARD -j ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")
            }
        }
        sb.append("fi\n")
    }

    /**
     * Builds the idempotent cleanup script removing all OwnBox Root chains, policy rules
     * (including legacy `pref 9000` from v26.11.8.29), hotspot rules, processes, and `ownbox0`.
     */
    fun buildTeardownScript(
        tunName: String = TUN_IFACE,
        pidPath: String? = null,
        oomGuardPidPath: String? = null,
        corePid: Int = 0,
        yamlPath: String? = null,
        logPath: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("set -u\n")
        sb.append("TUN=").append(shellSingleQuote(tunName)).append('\n')
        sb.append("PID_PATH=").append(shellSingleQuote(pidPath ?: "")).append('\n')
        sb.append("OOM_PID_PATH=").append(shellSingleQuote(oomGuardPidPath ?: "")).append('\n')
        sb.append("YAML_PATH=").append(shellSingleQuote(yamlPath ?: "")).append('\n')
        sb.append("LOG_PATH=").append(shellSingleQuote(logPath ?: "")).append("\n\n")
        appendTeardownBody(
            sb = sb,
            tunName = tunName,
            pidPath = pidPath ?: "",
            oomGuardPidPath = oomGuardPidPath ?: "",
            corePid = corePid,
            yamlPath = yamlPath ?: "",
            logPath = logPath ?: "",
            removeYaml = !yamlPath.isNullOrBlank(),
            removeLog = !logPath.isNullOrBlank(),
        )
        return sb.toString()
    }

    /**
     * Returns true if any Root mode runtime marker or PID file exists on disk.
     */
    fun hasActiveState(context: Context): Boolean {
        return defaultActiveFlagFile(context).exists() ||
            defaultPidFile(context).exists() ||
            defaultOomGuardPidFile(context).exists()
    }

    /**
     * Starts the configured Root mode synchronously on a background dispatcher. Rolls back on failure.
     */
    fun start(context: Context, params: StartParams) {
        val workDir = context.noBackupFilesDir
        writeRestrictedFile(defaultActiveFlagFile(context), "${params.mode.name}\n")
        if (params.mode.needsHevtun) {
            writeRestrictedFile(File(params.yamlPath), buildHevConfig(params))
        }
        val setupResult = RootShell.runScript(
            script = buildSetupScript(params),
            tempDir = workDir,
            prefix = "ownbox_setup_",
            timeoutSeconds = 20,
        )
        if (!setupResult.isSuccess) {
            val logTail = if (params.logPath.isNotBlank()) {
                runCatching {
                    val lf = File(params.logPath)
                    if (lf.isFile) lf.readLines().takeLast(12).joinToString("\n") else ""
                }.getOrDefault("")
            } else {
                ""
            }
            stop(context)
            val detail = listOf(setupResult.output, logTail)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { "exitCode=${setupResult.exitCode}" }
            throw IllegalStateException("Root mode (${params.mode.name}) setup failed: $detail")
        }
    }

    /**
     * Tears down all Root mode iptables rules, policy routes, daemon processes, and temporary state.
     */
    fun stop(
        context: Context,
        tunName: String = TUN_IFACE,
    ): RootShell.CommandResult {
        val workDir = context.noBackupFilesDir
        val yamlFile = defaultYamlFile(context)
        val pidFile = defaultPidFile(context)
        val logFile = defaultLogFile(context)
        val oomPidFile = defaultOomGuardPidFile(context)
        val result = RootShell.runScript(
            script = buildTeardownScript(
                tunName = tunName,
                pidPath = pidFile.absolutePath,
                oomGuardPidPath = oomPidFile.absolutePath,
                corePid = runCatching { Process.myPid() }.getOrDefault(0),
                yamlPath = yamlFile.absolutePath,
                logPath = logFile.absolutePath,
            ),
            tempDir = workDir,
            prefix = "ownbox_stop_",
            timeoutSeconds = 15,
        )
        runCatching { yamlFile.delete() }
        runCatching { pidFile.delete() }
        runCatching { oomPidFile.delete() }
        runCatching { logFile.delete() }
        if (result.isSuccess) {
            runCatching { defaultActiveFlagFile(context).delete() }
        }
        return result
    }

    fun defaultYamlFile(context: Context): File = File(context.noBackupFilesDir, CONFIG_FILE_NAME)

    fun defaultPidFile(context: Context): File = File(context.noBackupFilesDir, PID_FILE_NAME)

    fun defaultLogFile(context: Context): File = File(context.noBackupFilesDir, LOG_FILE_NAME)

    fun defaultOomGuardPidFile(context: Context): File = File(context.noBackupFilesDir, OOM_GUARD_PID_NAME)

    fun defaultActiveFlagFile(context: Context): File = File(context.noBackupFilesDir, ACTIVE_FLAG_NAME)

    private fun appendTeardownBody(
        sb: StringBuilder,
        tunName: String,
        pidPath: String,
        oomGuardPidPath: String,
        corePid: Int,
        yamlPath: String,
        logPath: String,
        removeYaml: Boolean,
        removeLog: Boolean,
    ) {
        sb.append("# Clean up iptables / ip6tables hooks and custom chains\n")
        val v4Hooks = listOf(
            Triple("mangle", "OUTPUT", CHAIN_OUT_MANGLE),
            Triple("mangle", "PREROUTING", CHAIN_PRE_MANGLE),
            Triple("nat", "OUTPUT", CHAIN_REDIR_NAT),
            Triple("nat", "OUTPUT", CHAIN_NAT_OUT),
            Triple("nat", "PREROUTING", CHAIN_DNS_PRE),
            Triple("nat", "POSTROUTING", CHAIN_NAT_POST),
            Triple("filter", "OUTPUT", CHAIN_OUT_FILTER),
            Triple("filter", "FORWARD", CHAIN_FWD_FILTER),
        )
        for ((table, hook, chain) in v4Hooks) {
            sb.append("while iptables -w 5 -t ").append(table).append(" -D ").append(hook).append(" -j ").append(chain).append(" 2>/dev/null; do :; done\n")
            sb.append("iptables -w 5 -t ").append(table).append(" -F ").append(chain).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t ").append(table).append(" -X ").append(chain).append(" 2>/dev/null || true\n")
        }
        sb.append("while iptables -w 5 -t mangle -D FORWARD -o \"\$TUN\" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350 2>/dev/null; do :; done\n")

        val v6Hooks = listOf(
            Triple("mangle", "OUTPUT", CHAIN_OUT_MANGLE),
            Triple("mangle", "PREROUTING", CHAIN_PRE_MANGLE),
            Triple("nat", "OUTPUT", CHAIN_NAT_OUT),
            Triple("filter", "OUTPUT", CHAIN_OUT_FILTER),
            Triple("filter", "OUTPUT", CHAIN_BLK6_OUT),
            Triple("filter", "FORWARD", CHAIN_BLK6_FWD),
            Triple("filter", "FORWARD", CHAIN_FWD_FILTER),
        )
        sb.append("if [ -d /proc/sys/net/ipv6 ]; then\n")
        for ((table, hook, chain) in v6Hooks) {
            sb.append("  while ip6tables -w 5 -t ").append(table).append(" -D ").append(hook).append(" -j ").append(chain).append(" 2>/dev/null; do :; done\n")
            sb.append("  ip6tables -w 5 -t ").append(table).append(" -F ").append(chain).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t ").append(table).append(" -X ").append(chain).append(" 2>/dev/null || true\n")
        }
        sb.append("fi\n\n")

        sb.append("# Clean up policy routing rules (current pref ").append(RULE_PRIORITY)
            .append(", legacy pref ").append(LEGACY_RULE_PRIORITY).append(", LAN sharing) and table ")
            .append(ROUTE_TABLE).append('\n')
        for (family in listOf("-4", "-6")) {
            if (family == "-6") {
                sb.append("if [ -d /proc/sys/net/ipv6 ]; then\n")
            }
            val indent = if (family == "-6") "  " else ""
            sb.append(indent).append("while ip ").append(family).append(" rule del fwmark ").append(FWMARK).append(" lookup ").append(ROUTE_TABLE).append(" 2>/dev/null; do :; done\n")
            sb.append(indent).append("ip ").append(family).append(" rule del fwmark ").append(FWMARK).append(" table ").append(ROUTE_TABLE).append(" priority ").append(RULE_PRIORITY).append(" 2>/dev/null || true\n")
            sb.append(indent).append("ip ").append(family).append(" rule del fwmark ").append(FWMARK).append(" table ").append(ROUTE_TABLE).append(" priority ").append(LEGACY_RULE_PRIORITY).append(" 2>/dev/null || true\n")
            sb.append(indent).append("ip ").append(family).append(" route flush table ").append(ROUTE_TABLE).append(" 2>/dev/null || true\n")
            if (family == "-6") {
                sb.append("fi\n")
            }
        }
        for (pref in LAN_SHARE_PREFS) {
            sb.append("while ip -4 rule del pref ").append(pref).append(" 2>/dev/null; do :; done\n")
        }
        sb.append('\n')

        sb.append("# Stop any existing hevsockstun / oomguard processes and remove TUN\n")
        if (pidPath.isNotBlank()) {
            sb.append("if [ -s \"\$PID_PATH\" ]; then\n")
            sb.append("  OLD_PID=\$(cat \"\$PID_PATH\" 2>/dev/null)\n")
            sb.append("  if [ -n \"\$OLD_PID\" ]; then\n")
            sb.append("    kill \"\$OLD_PID\" 2>/dev/null || true\n")
            sb.append("    sleep 0.1 2>/dev/null || true\n")
            sb.append("    kill -9 \"\$OLD_PID\" 2>/dev/null || true\n")
            sb.append("  fi\n")
            sb.append("  rm -f \"\$PID_PATH\" 2>/dev/null || true\n")
            sb.append("fi\n")
        }
        if (oomGuardPidPath.isNotBlank()) {
            sb.append("if [ -s \"\$OOM_PID_PATH\" ]; then\n")
            sb.append("  OLD_OOM_PID=\$(cat \"\$OOM_PID_PATH\" 2>/dev/null)\n")
            sb.append("  if [ -n \"\$OLD_OOM_PID\" ]; then\n")
            sb.append("    kill \"\$OLD_OOM_PID\" 2>/dev/null || true\n")
            sb.append("    kill -9 \"\$OLD_OOM_PID\" 2>/dev/null || true\n")
            sb.append("  fi\n")
            sb.append("  rm -f \"\$OOM_PID_PATH\" 2>/dev/null || true\n")
            sb.append("fi\n")
        }
        if (corePid > 0) {
            sb.append("echo 0 > /proc/").append(corePid).append("/oom_score_adj 2>/dev/null || true\n")
        }
        sb.append("pkill -f ").append(shellSingleQuote(CONFIG_FILE_NAME)).append(" 2>/dev/null || true\n")
        sb.append("ip link set dev ").append(shellSingleQuote(tunName)).append(" down 2>/dev/null || true\n")
        sb.append("ip link del dev ").append(shellSingleQuote(tunName)).append(" 2>/dev/null || true\n")
        if (removeYaml && yamlPath.isNotBlank()) {
            sb.append("if [ -n \"\$YAML_PATH\" ]; then rm -f \"\$YAML_PATH\" 2>/dev/null || true; fi\n")
        }
        if (removeLog && logPath.isNotBlank()) {
            sb.append("if [ -n \"\$LOG_PATH\" ]; then rm -f \"\$LOG_PATH\" 2>/dev/null || true; fi\n")
        }
        sb.append('\n')
    }

    private fun shellSingleQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun yamlSingleQuote(value: String): String {
        return "'" + value.replace("'", "''") + "'"
    }
}
