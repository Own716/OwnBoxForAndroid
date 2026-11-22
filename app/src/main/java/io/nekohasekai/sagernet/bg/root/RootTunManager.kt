package io.nekohasekai.sagernet.bg.root

import android.content.Context
import io.nekohasekai.sagernet.utils.PackageCache
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Manages the `hev-socks5-tunnel` root daemon and `iptables` / `ip rule` setup & teardown.
 */
object RootTunManager {

    const val HEVTUN_SO_NAME = "libhevsockstun.so"
    const val TUN_IFACE = "ownbox0"
    const val TUN_ADDR_V4 = "172.19.0.1"
    const val TUN_PEER_V4 = "172.19.0.2"
    const val TUN_PREFIX_V4 = 30
    const val TUN_ADDR_V6 = "fdfe:dcba:9876::1"
    const val TUN_PEER_V6 = "fdfe:dcba:9876::2"
    const val TUN_PREFIX_V6 = 126

    const val FWMARK = "0x2024"
    const val ROUTE_TABLE = "2024"
    const val RULE_PRIORITY = "9000"

    const val CHAIN_OUT_MANGLE = "OWNBOX_ROOT_OUT"
    const val CHAIN_PRE_MANGLE = "OWNBOX_ROOT_PRE"
    const val CHAIN_NAT_OUT = "OWNBOX_ROOT_DNS"
    const val CHAIN_NAT_POST = "OWNBOX_ROOT_NAT"
    const val CHAIN_FWD_FILTER = "OWNBOX_ROOT_FWD"
    const val CHAIN_BLK6_OUT = "OWNBOX_ROOT_BLK6"
    const val CHAIN_BLK6_FWD = "OWNBOX_ROOT_BLK6_FWD"

    const val PID_FILE_NAME = "ownbox_hevtun.pid"
    const val CONFIG_FILE_NAME = "ownbox_hevtun.yaml"

    private val GOOGLE_PLAY_SUPPORT_PACKAGES = listOf(
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.xiaomi.providers.downloads",
        "com.google.android.gsf",
        "com.google.android.gms",
    )

    private val LAN_CIDRS_V4 = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "172.16.0.0/12",
        "192.168.0.0/16",
    )

    private val RESERVED_CIDRS_V4 = listOf(
        "0.0.0.0/8",
        "169.254.0.0/16",
        "224.0.0.0/4",
        "255.255.255.255/32",
    )

    private const val CREDENTIAL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val secureRandom = SecureRandom()

    data class StartParams(
        val ownUid: Int,
        val socksPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val mtu: Int,
        val enableIpv6: Boolean,
        val bypassLan: Boolean,
        val rootLanSharing: Boolean,
        val perAppEnabled: Boolean,
        val perAppBypass: Boolean,
        val perAppUids: Set<Int>,
        val tunName: String = TUN_IFACE,
        val hevtunBinPath: String,
        val yamlPath: String,
        val pidPath: String,
    )

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
     * Parses `DataStore.individual` and applies Google Play support package expansion.
     */
    fun expandPackageNames(individualRaw: String): Set<String> {
        val raw = individualRaw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        if ("com.android.vending" in raw) {
            raw.addAll(GOOGLE_PLAY_SUPPORT_PACKAGES)
        }
        return raw
    }

    /**
     * Resolves package names in [individualRaw] to app UIDs via [PackageCache], excluding [ownUid].
     */
    fun resolvePerAppUids(context: Context, individualRaw: String, ownUid: Int): Set<Int> {
        PackageCache.awaitLoadSync()
        val packageManager = context.packageManager
        val names = expandPackageNames(individualRaw)
        val uids = LinkedHashSet<Int>(names.size)
        for (pkg in names) {
            val uid = PackageCache[pkg] ?: runCatching {
                packageManager.getApplicationInfo(pkg, 0).uid
            }.getOrNull()
            if (uid != null && uid > 0 && uid != ownUid) {
                uids.add(uid)
            }
        }
        return uids
    }

    /**
     * Builds the `hev-socks5-tunnel` YAML configuration file content.
     */
    fun buildHevConfig(params: StartParams): String {
        val sb = StringBuilder()
        sb.append("tunnel:\n")
        sb.append("  name: ").append(yamlSingleQuote(params.tunName)).append('\n')
        sb.append("  mtu: ").append(params.mtu.coerceIn(576, 65535)).append('\n')
        sb.append("  multi-queue: false\n")
        sb.append("  ipv4: ").append(TUN_ADDR_V4).append('\n')
        if (params.enableIpv6) {
            sb.append("  ipv6: ").append(yamlSingleQuote(TUN_ADDR_V6)).append('\n')
        }
        sb.append("  icmp: 'off'\n")
        sb.append('\n')
        sb.append("socks5:\n")
        sb.append("  port: ").append(params.socksPort).append('\n')
        sb.append("  address: 127.0.0.1\n")
        sb.append("  udp: 'udp'\n")
        if (params.socksUsername.isNotEmpty()) {
            sb.append("  username: ").append(yamlSingleQuote(params.socksUsername)).append('\n')
            sb.append("  password: ").append(yamlSingleQuote(params.socksPassword)).append('\n')
        }
        sb.append('\n')
        sb.append("misc:\n")
        sb.append("  task-stack-size: 81920\n")
        sb.append("  connect-timeout: 5000\n")
        sb.append("  tcp-read-write-timeout: 300000\n")
        sb.append("  udp-read-write-timeout: 60000\n")
        sb.append("  log-file: null\n")
        sb.append("  log-level: warn\n")
        sb.append("  pid-file: ").append(yamlSingleQuote(params.pidPath)).append('\n')
        sb.append("  limit-nofile: 65535\n")
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
     * Builds the idempotent shell script that starts `hevsockstun` and configures routing + iptables.
     */
    fun buildSetupScript(params: StartParams): String {
        val tun = shellSingleQuote(params.tunName)
        val hevBin = shellSingleQuote(params.hevtunBinPath)
        val yamlPath = shellSingleQuote(params.yamlPath)
        val pidPath = shellSingleQuote(params.pidPath)
        val sortedUids = params.perAppUids.filter { it > 0 && it != params.ownUid }.sorted()
        val emptyWhitelist = params.perAppEnabled && !params.perAppBypass && sortedUids.isEmpty()

        val sb = StringBuilder()
        sb.append("set -u\n")
        sb.append("TUN=").append(tun).append('\n')
        sb.append("HEV_BIN=").append(hevBin).append('\n')
        sb.append("YAML_PATH=").append(yamlPath).append('\n')
        sb.append("PID_PATH=").append(pidPath).append("\n\n")

        appendTeardownBody(sb, params.tunName, params.pidPath, removeYaml = false)

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
        sb.append("\"\$HEV_BIN\" \"\$YAML_PATH\" || { echo 'Failed to launch hevsockstun' >&2; exit 1; }\n\n")
        sb.append("i=0\n")
        sb.append("while [ \$i -lt 30 ]; do\n")
        sb.append("  if ip link show \"\$TUN\" >/dev/null 2>&1; then\n")
        sb.append("    break\n")
        sb.append("  fi\n")
        sb.append("  sleep 0.1 2>/dev/null || sleep 1\n")
        sb.append("  i=\$((i + 1))\n")
        sb.append("done\n")
        sb.append("if ! ip link show \"\$TUN\" >/dev/null 2>&1; then\n")
        sb.append("  echo \"TUN interface \$TUN failed to appear\" >&2\n")
        sb.append("  exit 1\n")
        sb.append("fi\n\n")

        sb.append("# Configure TUN link, addresses, and policy routing table\n")
        sb.append("ip link set dev \"\$TUN\" up mtu ").append(params.mtu.coerceIn(576, 65535)).append(" || exit 1\n")
        sb.append("ip -4 addr replace ").append(TUN_ADDR_V4).append('/').append(TUN_PREFIX_V4).append(" dev \"\$TUN\" || exit 1\n")
        sb.append("ip -4 route replace default dev \"\$TUN\" table ").append(ROUTE_TABLE).append(" || exit 1\n")
        sb.append("ip -4 rule add fwmark ").append(FWMARK).append(" lookup ").append(ROUTE_TABLE).append(" pref ").append(RULE_PRIORITY).append(" || exit 1\n")
        sb.append("sysctl -w net.ipv4.conf.\"\$TUN\".rp_filter=0 >/dev/null 2>&1 || true\n")

        if (params.enableIpv6) {
            sb.append("if [ -d /proc/sys/net/ipv6 ]; then\n")
            sb.append("  ip -6 addr replace ").append(TUN_ADDR_V6).append('/').append(TUN_PREFIX_V6).append(" dev \"\$TUN\" 2>/dev/null || true\n")
            sb.append("  ip -6 route replace default dev \"\$TUN\" table ").append(ROUTE_TABLE).append(" 2>/dev/null || true\n")
            sb.append("  ip -6 rule add fwmark ").append(FWMARK).append(" lookup ").append(ROUTE_TABLE).append(" pref ").append(RULE_PRIORITY).append(" 2>/dev/null || true\n")
            sb.append("fi\n")
        }
        sb.append('\n')

        // IPv4 nat OUTPUT (rewrite outbound DNS to TUN peer so LAN router DNS never hits local netfilter ambiguity)
        if (!emptyWhitelist) {
            sb.append("# IPv4 nat OUTPUT DNS DNAT\n")
            sb.append("iptables -w 5 -t nat -N ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -F ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -o lo -j RETURN 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -d 127.0.0.0/8 -j RETURN 2>/dev/null || true\n")
            if (params.perAppEnabled && params.perAppBypass) {
                for (uid in sortedUids) {
                    sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN 2>/dev/null || true\n")
                }
            }
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -p udp --dport 53 -j DNAT --to-destination ").append(TUN_PEER_V4).append(":53 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -p tcp --dport 53 -j DNAT --to-destination ").append(TUN_PEER_V4).append(":53 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -I OUTPUT 1 -j ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n\n")
        }

        // IPv4 mangle OUTPUT
        sb.append("# IPv4 mangle OUTPUT\n")
        sb.append("iptables -w 5 -t mangle -N ").append(CHAIN_OUT_MANGLE).append(" || exit 1\n")
        sb.append("iptables -w 5 -t mangle -F ").append(CHAIN_OUT_MANGLE).append(" || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -o lo -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d 127.0.0.0/8 -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -o \"\$TUN\" -j RETURN || exit 1\n")
        sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d ").append(TUN_ADDR_V4).append('/').append(TUN_PREFIX_V4).append(" -j RETURN || exit 1\n")
        if (params.perAppEnabled && params.perAppBypass) {
            for (uid in sortedUids) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN || exit 1\n")
            }
        }
        if (!emptyWhitelist) {
            // Capture DNS before LAN bypass so LAN router DNS queries (e.g. 192.168.1.1:53) still enter TUN
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p udp --dport 53 -j MARK --set-mark ").append(FWMARK).append(" || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p udp --dport 53 -j RETURN || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p tcp --dport 53 -j MARK --set-mark ").append(FWMARK).append(" || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p tcp --dport 53 -j RETURN || exit 1\n")
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
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -j MARK --set-mark ").append(FWMARK).append(" || exit 1\n")
        } else {
            for (uid in sortedUids) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(uid).append(" -j MARK --set-mark ").append(FWMARK).append(" || exit 1\n")
            }
        }
        sb.append("iptables -w 5 -t mangle -I OUTPUT 1 -j ").append(CHAIN_OUT_MANGLE).append(" || exit 1\n\n")

        // Optional Root Hotspot / USB transparent sharing
        if (params.rootLanSharing) {
            sb.append("# Root LAN/Hotspot transparent sharing\n")
            sb.append("sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true\n")
            sb.append("iptables -w 5 -t mangle -N ").append(CHAIN_PRE_MANGLE).append(" || exit 1\n")
            sb.append("iptables -w 5 -t mangle -F ").append(CHAIN_PRE_MANGLE).append(" || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -i lo -j RETURN || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -i \"\$TUN\" -j RETURN || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -p udp --dport 67:68 -j RETURN || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d 127.0.0.0/8 -j RETURN || exit 1\n")
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d ").append(TUN_ADDR_V4).append('/').append(TUN_PREFIX_V4).append(" -j RETURN || exit 1\n")
            for (cidr in RESERVED_CIDRS_V4) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d ").append(cidr).append(" -j RETURN || exit 1\n")
            }
            for (cidr in LAN_CIDRS_V4) {
                sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d ").append(cidr).append(" -j RETURN || exit 1\n")
            }
            sb.append("iptables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -j MARK --set-mark ").append(FWMARK).append(" || exit 1\n")
            sb.append("iptables -w 5 -t mangle -I PREROUTING 1 -j ").append(CHAIN_PRE_MANGLE).append(" || exit 1\n")

            sb.append("iptables -w 5 -t filter -N ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t filter -F ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t filter -A ").append(CHAIN_FWD_FILTER).append(" -o \"\$TUN\" -j ACCEPT 2>/dev/null || true\n")
            sb.append("(iptables -w 5 -t filter -A ").append(CHAIN_FWD_FILTER).append(" -i \"\$TUN\" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -w 5 -t filter -A ").append(CHAIN_FWD_FILTER).append(" -i \"\$TUN\" -j ACCEPT 2>/dev/null || true)\n")
            sb.append("iptables -w 5 -t filter -I FORWARD 1 -j ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")

            sb.append("iptables -w 5 -t nat -N ").append(CHAIN_NAT_POST).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -F ").append(CHAIN_NAT_POST).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -A ").append(CHAIN_NAT_POST).append(" -o \"\$TUN\" -j MASQUERADE 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t nat -I POSTROUTING 1 -j ").append(CHAIN_NAT_POST).append(" 2>/dev/null || true\n\n")
        }

        // IPv6: either configure routing/ip6tables or blackhole IPv6 to avoid leaks/Happy Eyeballs stalls
        sb.append("if [ -d /proc/sys/net/ipv6 ]; then\n")
        if (params.enableIpv6) {
            if (!emptyWhitelist) {
                sb.append("  ip6tables -w 5 -t nat -N ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t nat -F ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -o lo -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -d ::1/128 -j RETURN 2>/dev/null || true\n")
                if (params.perAppEnabled && params.perAppBypass) {
                    for (uid in sortedUids) {
                        sb.append("  ip6tables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN 2>/dev/null || true\n")
                    }
                }
                sb.append("  ip6tables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -p udp --dport 53 -j DNAT --to-destination [").append(TUN_PEER_V6).append("]:53 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t nat -A ").append(CHAIN_NAT_OUT).append(" -p tcp --dport 53 -j DNAT --to-destination [").append(TUN_PEER_V6).append("]:53 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t nat -I OUTPUT 1 -j ").append(CHAIN_NAT_OUT).append(" 2>/dev/null || true\n\n")
            }

            sb.append("  ip6tables -w 5 -t mangle -N ").append(CHAIN_OUT_MANGLE).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -F ").append(CHAIN_OUT_MANGLE).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -o lo -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d ::1/128 -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -o \"\$TUN\" -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d ").append(TUN_ADDR_V6).append('/').append(TUN_PREFIX_V6).append(" -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d fe80::/10 -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d ff00::/8 -j RETURN 2>/dev/null || true\n")
            if (params.perAppEnabled && params.perAppBypass) {
                for (uid in sortedUids) {
                    sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN 2>/dev/null || true\n")
                }
            }
            if (!emptyWhitelist) {
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p udp --dport 53 -j MARK --set-mark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p udp --dport 53 -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p tcp --dport 53 -j MARK --set-mark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -p tcp --dport 53 -j RETURN 2>/dev/null || true\n")
            }
            if (params.bypassLan) {
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -d fc00::/7 -j RETURN 2>/dev/null || true\n")
            }
            if (!params.perAppEnabled || params.perAppBypass) {
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -j MARK --set-mark ").append(FWMARK).append(" 2>/dev/null || true\n")
            } else {
                for (uid in sortedUids) {
                    sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_OUT_MANGLE).append(" -m owner --uid-owner ").append(uid).append(" -j MARK --set-mark ").append(FWMARK).append(" 2>/dev/null || true\n")
                }
            }
            sb.append("  ip6tables -w 5 -t mangle -I OUTPUT 1 -j ").append(CHAIN_OUT_MANGLE).append(" 2>/dev/null || true\n")

            if (params.rootLanSharing) {
                sb.append("  sysctl -w net.ipv6.conf.all.forwarding=1 >/dev/null 2>&1 || true\n")
                sb.append("  ip6tables -w 5 -t mangle -N ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -F ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -i lo -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -i \"\$TUN\" -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d ::1/128 -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d fe80::/10 -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d ff00::/8 -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d fc00::/7 -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -d ").append(TUN_ADDR_V6).append('/').append(TUN_PREFIX_V6).append(" -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -A ").append(CHAIN_PRE_MANGLE).append(" -j MARK --set-mark ").append(FWMARK).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t mangle -I PREROUTING 1 -j ").append(CHAIN_PRE_MANGLE).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -N ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -F ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_FWD_FILTER).append(" -o \"\$TUN\" -j ACCEPT 2>/dev/null || true\n")
                sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_FWD_FILTER).append(" -i \"\$TUN\" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || ip6tables -w 5 -t filter -A ").append(CHAIN_FWD_FILTER).append(" -i \"\$TUN\" -j ACCEPT 2>/dev/null || true)\n")
                sb.append("  ip6tables -w 5 -t filter -I FORWARD 1 -j ").append(CHAIN_FWD_FILTER).append(" 2>/dev/null || true\n")
            }
        } else {
            sb.append("  ip6tables -w 5 -t filter -N ").append(CHAIN_BLK6_OUT).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -F ").append(CHAIN_BLK6_OUT).append(" 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(params.ownUid).append(" -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -o lo -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -d ::1/128 -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -d fe80::/10 -j RETURN 2>/dev/null || true\n")
            sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -d ff02::/16 -j RETURN 2>/dev/null || true\n")
            if (params.perAppEnabled && params.perAppBypass) {
                for (uid in sortedUids) {
                    sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j RETURN 2>/dev/null || true\n")
                }
            }
            if (params.perAppEnabled && !params.perAppBypass) {
                for (uid in sortedUids) {
                    sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null || ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -m owner --uid-owner ").append(uid).append(" -j DROP 2>/dev/null || true)\n")
                }
            } else {
                sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null || ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_OUT).append(" -j DROP 2>/dev/null || true)\n")
            }
            sb.append("  ip6tables -w 5 -t filter -I OUTPUT 1 -j ").append(CHAIN_BLK6_OUT).append(" 2>/dev/null || true\n")

            if (params.rootLanSharing) {
                sb.append("  ip6tables -w 5 -t filter -N ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -F ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_FWD).append(" -d fe80::/10 -j RETURN 2>/dev/null || true\n")
                sb.append("  ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_FWD).append(" -d ff02::/16 -j RETURN 2>/dev/null || true\n")
                sb.append("  (ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_FWD).append(" -j REJECT --reject-with icmp6-adm-prohibited 2>/dev/null || ip6tables -w 5 -t filter -A ").append(CHAIN_BLK6_FWD).append(" -j DROP 2>/dev/null || true)\n")
                sb.append("  ip6tables -w 5 -t filter -I FORWARD 1 -j ").append(CHAIN_BLK6_FWD).append(" 2>/dev/null || true\n")
            }
        }
        sb.append("fi\n")
        return sb.toString()
    }

    /**
     * Builds the best-effort, idempotent cleanup script removing all OwnBox Root TUN chains,
     * policy rules, TUN device, and `hevsockstun` daemon process.
     */
    fun buildTeardownScript(
        tunName: String = TUN_IFACE,
        pidPath: String? = null,
        yamlPath: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("set -u\n")
        sb.append("TUN=").append(shellSingleQuote(tunName)).append('\n')
        sb.append("PID_PATH=").append(shellSingleQuote(pidPath ?: "")).append('\n')
        sb.append("YAML_PATH=").append(shellSingleQuote(yamlPath ?: "")).append("\n\n")
        appendTeardownBody(sb, tunName, pidPath ?: "", removeYaml = !yamlPath.isNullOrBlank())
        return sb.toString()
    }

    /**
     * Starts Root TUN synchronously (run on IO/background dispatcher). Rolls back on failure.
     */
    fun start(context: Context, params: StartParams) {
        val workDir = context.noBackupFilesDir
        val yamlFile = File(params.yamlPath)
        writeRestrictedFile(yamlFile, buildHevConfig(params))
        val setupResult = RootShell.runScript(
            script = buildSetupScript(params),
            tempDir = workDir,
            prefix = "ownbox_setup_",
            timeoutSeconds = 20,
        )
        if (!setupResult.isSuccess) {
            stop(context, params.tunName, params.pidPath, params.yamlPath)
            val detail = setupResult.output.ifBlank { "exitCode=${setupResult.exitCode}" }
            throw IllegalStateException("Root TUN setup failed: $detail")
        }
    }

    /**
     * Tears down Root TUN routing, iptables rules, daemon process, and temporary config.
     */
    fun stop(
        context: Context,
        tunName: String = TUN_IFACE,
        pidPath: String? = defaultPidFile(context).absolutePath,
        yamlPath: String? = defaultYamlFile(context).absolutePath,
    ): RootShell.CommandResult {
        val workDir = context.noBackupFilesDir
        val result = RootShell.runScript(
            script = buildTeardownScript(tunName, pidPath, yamlPath),
            tempDir = workDir,
            prefix = "ownbox_stop_",
            timeoutSeconds = 15,
        )
        if (!yamlPath.isNullOrBlank()) {
            runCatching { File(yamlPath).delete() }
        }
        if (!pidPath.isNullOrBlank()) {
            runCatching { File(pidPath).delete() }
        }
        return result
    }

    fun defaultYamlFile(context: Context): File = File(context.noBackupFilesDir, CONFIG_FILE_NAME)

    fun defaultPidFile(context: Context): File = File(context.noBackupFilesDir, PID_FILE_NAME)

    private fun appendTeardownBody(
        sb: StringBuilder,
        tunName: String,
        pidPath: String,
        removeYaml: Boolean,
    ) {
        sb.append("# Clean up iptables / ip6tables hooks and custom chains\n")
        val v4Hooks = listOf(
            Triple("mangle", "OUTPUT", CHAIN_OUT_MANGLE),
            Triple("mangle", "PREROUTING", CHAIN_PRE_MANGLE),
            Triple("nat", "OUTPUT", CHAIN_NAT_OUT),
            Triple("nat", "POSTROUTING", CHAIN_NAT_POST),
            Triple("filter", "FORWARD", CHAIN_FWD_FILTER),
        )
        for ((table, hook, chain) in v4Hooks) {
            sb.append("while iptables -w 5 -t ").append(table).append(" -D ").append(hook).append(" -j ").append(chain).append(" 2>/dev/null; do :; done\n")
            sb.append("iptables -w 5 -t ").append(table).append(" -F ").append(chain).append(" 2>/dev/null || true\n")
            sb.append("iptables -w 5 -t ").append(table).append(" -X ").append(chain).append(" 2>/dev/null || true\n")
        }

        val v6Hooks = listOf(
            Triple("mangle", "OUTPUT", CHAIN_OUT_MANGLE),
            Triple("mangle", "PREROUTING", CHAIN_PRE_MANGLE),
            Triple("nat", "OUTPUT", CHAIN_NAT_OUT),
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

        sb.append("# Clean up policy routing rules and route table\n")
        sb.append("while ip -4 rule del fwmark ").append(FWMARK).append(" lookup ").append(ROUTE_TABLE).append(" 2>/dev/null; do :; done\n")
        sb.append("ip -4 route flush table ").append(ROUTE_TABLE).append(" 2>/dev/null || true\n")
        sb.append("if [ -d /proc/sys/net/ipv6 ]; then\n")
        sb.append("  while ip -6 rule del fwmark ").append(FWMARK).append(" lookup ").append(ROUTE_TABLE).append(" 2>/dev/null; do :; done\n")
        sb.append("  ip -6 route flush table ").append(ROUTE_TABLE).append(" 2>/dev/null || true\n")
        sb.append("fi\n\n")

        sb.append("# Stop any existing hevsockstun process and remove stale TUN\n")
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
        sb.append("pkill -f ").append(shellSingleQuote(CONFIG_FILE_NAME)).append(" 2>/dev/null || true\n")
        sb.append("ip link set dev ").append(shellSingleQuote(tunName)).append(" down 2>/dev/null || true\n")
        sb.append("ip link del dev ").append(shellSingleQuote(tunName)).append(" 2>/dev/null || true\n")
        if (removeYaml) {
            sb.append("if [ -n \"\$YAML_PATH\" ]; then rm -f \"\$YAML_PATH\" 2>/dev/null || true; fi\n")
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
