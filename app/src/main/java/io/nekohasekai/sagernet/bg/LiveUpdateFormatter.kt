package io.nekohasekai.sagernet.bg

import android.content.Context
import android.text.format.Formatter
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.utils.RegionExtractor
import java.util.Locale

object LiveUpdateFormatter {

    const val PRESET_GROUP_FIRST = "group_first"
    const val PRESET_NODE_FIRST = "node_first"
    const val PRESET_MINIMAL_IP = "minimal_ip"
    const val PRESET_FULL_STATS = "full_stats"
    const val PRESET_CUSTOM = "custom"

    // Backward compatibility aliases
    const val PRESET_STANDARD = "standard"
    const val PRESET_COMPACT = "compact"
    const val PRESET_DETAILED = "detailed"

    // 1. Group First (Recommended for Strategy Groups)
    const val TPL_GROUP_FIRST_COLLAPSED = "{group}"
    const val TPL_GROUP_FIRST_TITLE = "{group}"
    const val TPL_GROUP_FIRST_CONTENT = "↓ {down}   ↑ {up}"
    const val TPL_GROUP_FIRST_SUBTEXT = "{profile} | {ip}"

    // 2. Node First
    const val TPL_NODE_FIRST_COLLAPSED = "{profile}"
    const val TPL_NODE_FIRST_TITLE = "{profile}"
    const val TPL_NODE_FIRST_CONTENT = "↓ {down}   ↑ {up}"
    const val TPL_NODE_FIRST_SUBTEXT = "{group} | {ip}"

    // 3. Minimal & IP
    const val TPL_MINIMAL_IP_COLLAPSED = "{profile}"
    const val TPL_MINIMAL_IP_TITLE = "{profile}"
    const val TPL_MINIMAL_IP_CONTENT = "↓ {down}  ↑ {up}"
    const val TPL_MINIMAL_IP_SUBTEXT = "{ip}"

    // 4. Full Stats
    const val TPL_FULL_STATS_COLLAPSED = "{profile}"
    const val TPL_FULL_STATS_TITLE = "[{group}] {profile}"
    const val TPL_FULL_STATS_CONTENT = "↓ {down}  ↑ {up}  |  {ip}"
    const val TPL_FULL_STATS_SUBTEXT = "{traffic_total} ({duration})"

    data class Templates(
        val collapsed: String,
        val title: String,
        val content: String,
        val subText: String,
    )

    data class LiveUpdateInfo(
        val profileName: String,
        val groupName: String,
        val downSpeed: String,
        val upSpeed: String,
        val totalDownTraffic: String,
        val totalUpTraffic: String,
        val totalTraffic: String,
        val duration: String,
        val protocol: String,
        val latency: String,
        val directDownSpeed: String,
        val directUpSpeed: String,
        val ip: String,
        val server: String,
        val country: String,
    )

    fun cleanCountryName(name: String): String {
        if (name.isEmpty()) return ""
        var cleaned = name.trim()
        if (cleaned.startsWith("中国") && cleaned.length > 2) {
            cleaned = cleaned.removePrefix("中国")
        }
        cleaned = cleaned
            .removeSuffix("特别行政区")
            .removeSuffix("特别行政區")
            .removeSuffix("联邦")
            .removeSuffix("共和国")
            .removeSuffix("共和國")
            .trim()
        return cleaned.ifEmpty { name.trim() }
    }

    fun inferCountryFromName(name: String): String {
        if (name.isEmpty()) return ""
        val n = name.lowercase(Locale.ROOT)
        return when {
            name.contains("🇯🇵") || name.contains("日本") || n.contains("japan") || n.contains("tokyo") || n.contains("osaka") || n.contains("jp") -> "日本"
            name.contains("🇭🇰") || name.contains("香港") || n.contains("hong kong") || n.contains("hongkong") || n.contains("hk") -> "香港"
            name.contains("🇹🇼") || name.contains("台湾") || name.contains("臺灣") || n.contains("taiwan") || n.contains("taipei") || n.contains("tw") -> "台湾"
            name.contains("🇸🇬") || name.contains("新加坡") || name.contains("狮城") || n.contains("singapore") || n.contains("sg") -> "新加坡"
            name.contains("🇺🇸") || name.contains("美国") || name.contains("美國") || n.contains("united states") || n.contains("usa") || n.contains("los angeles") || n.contains("silicon") || n.contains("us") -> "美国"
            name.contains("🇬🇧") || name.contains("英国") || name.contains("英國") || n.contains("united kingdom") || n.contains("london") || n.contains("uk") -> "英国"
            name.contains("🇰🇷") || name.contains("韩国") || name.contains("韓國") || n.contains("korea") || n.contains("seoul") || n.contains("kr") -> "韩国"
            name.contains("🇩🇪") || name.contains("德国") || name.contains("德國") || n.contains("germany") || n.contains("frankfurt") || n.contains("de") -> "德国"
            name.contains("🇫🇷") || name.contains("法国") || name.contains("法國") || n.contains("france") || n.contains("paris") || n.contains("fr") -> "法国"
            name.contains("🇨🇦") || name.contains("加拿大") || n.contains("canada") || n.contains("toronto") || n.contains("vancouver") || n.contains("ca") -> "加拿大"
            name.contains("🇦🇺") || name.contains("澳大利亚") || name.contains("澳洲") || n.contains("australia") || n.contains("sydney") || n.contains("au") -> "澳大利亚"
            name.contains("🇷🇺") || name.contains("俄罗斯") || name.contains("俄羅斯") || n.contains("russia") || n.contains("moscow") || n.contains("ru") -> "俄罗斯"
            name.contains("🇲🇾") || name.contains("马来西亚") || name.contains("馬來西亞") || n.contains("malaysia") || n.contains("my") -> "马来西亚"
            name.contains("🇹🇭") || name.contains("泰国") || name.contains("泰國") || n.contains("thailand") || n.contains("bangkok") || n.contains("th") -> "泰国"
            name.contains("🇵🇭") || name.contains("菲律宾") || name.contains("菲律賓") || n.contains("philippines") || n.contains("ph") -> "菲律宾"
            name.contains("🇮🇳") || name.contains("印度") || n.contains("india") || n.contains("in") -> "印度"
            name.contains("🇻🇳") || name.contains("越南") || n.contains("vietnam") || n.contains("vn") -> "越南"
            name.contains("🇹🇷") || name.contains("土耳其") || n.contains("turkey") || n.contains("istanbul") || n.contains("tr") -> "土耳其"
            name.contains("🇦🇷") || name.contains("阿根廷") || n.contains("argentina") || n.contains("ar") -> "阿根廷"
            name.contains("🇧🇷") || name.contains("巴西") || n.contains("brazil") || n.contains("br") -> "巴西"
            name.contains("🇳🇱") || name.contains("荷兰") || name.contains("荷蘭") || n.contains("netherlands") || n.contains("amsterdam") || n.contains("nl") -> "荷兰"
            name.contains("🇨🇳") || name.contains("回国") || name.contains("国内") || n.contains("china") -> "中国"
            else -> ""
        }
    }

    fun buildInfo(
        context: Context,
        profile: ProxyEntity?,
        stats: SpeedDisplayData,
        startTimeMs: Long,
        currentEgressIp: String? = null,
        currentEgressCountry: String? = null,
    ): LiveUpdateInfo {
        var profileName = ""
        var groupName = ""
        var ip = currentEgressIp.orEmpty()
        var country = currentEgressCountry.orEmpty()
        var rawCountryCode = ""
        var server = ""
        var protocol = ""
        var latency = ""

        val showGroup = runCatching { DataStore.showGroupInNotification }.getOrDefault(true)
        val showDirect = runCatching { DataStore.showDirectSpeed }.getOrDefault(true)

        if (country.isEmpty()) {
            val cachedLanding = LandingIpManager.getCachedInfo()
            if (cachedLanding != null) {
                if (ip.isEmpty() && cachedLanding.ip.isNotEmpty()) ip = cachedLanding.ip
                if (cachedLanding.country.isNotEmpty()) {
                    country = cleanCountryName(cachedLanding.country)
                } else if (cachedLanding.countryCode.isNotEmpty()) {
                    rawCountryCode = cachedLanding.countryCode
                }
            }
        }

        val isBalancer = profile?.type == ProxyEntity.TYPE_BALANCER
        val group = if (profile != null) runCatching { SagerDatabase.groupDao.getById(profile.groupId) }.getOrNull() else null
        val isGroupStrategy = group != null && (
            runCatching { DataStore.isGroupUrlTest(group.id) }.getOrDefault(false) ||
            runCatching { DataStore.isGroupLoadBalance(group.id) }.getOrDefault(false)
        )
        val isStrategy = isBalancer || isGroupStrategy

        if (isStrategy && profile != null) {
            val actualGroupName = profile.displayName()
            groupName = if (showGroup) actualGroupName else ""
            val leafNode = ActiveOutboundTracker.getActiveLeafNodeDisplay(profile)
            val leafId = ActiveOutboundTracker.activeLeafProfileId
            val leafEntity = if (leafId > 0L) runCatching { SagerDatabase.proxyDao.getById(leafId) }.getOrNull() else null

            profileName = leafNode ?: leafEntity?.displayName() ?: ActiveOutboundTracker.activeLeafProfileName.takeIf { it.isNotBlank() } ?: actualGroupName

            val refProfile = leafEntity ?: profile
            if (ip.isEmpty()) {
                ip = refProfile.displayAddress()
            }
            server = refProfile.displayAddress()
            protocol = refProfile.displayType().uppercase(Locale.getDefault())
            if (refProfile.ping > 0) latency = "${refProfile.ping}ms"
        } else if (profile != null) {
            groupName = if (showGroup) group?.displayName().orEmpty() else ""
            profileName = profile.displayName()
            if (ip.isEmpty()) {
                ip = profile.displayAddress()
            }
            server = profile.displayAddress()
            protocol = profile.displayType().uppercase(Locale.getDefault())
            if (profile.ping > 0) latency = "${profile.ping}ms"
        }

        if (country.isEmpty() && rawCountryCode.isNotEmpty()) {
            country = cleanCountryName(
                runCatching {
                    Locale("", rawCountryCode.uppercase(Locale.ROOT)).getDisplayCountry(Locale.getDefault())
                }.getOrNull().orEmpty()
            )
        }

        if (country.isEmpty()) {
            val fromRegion = RegionExtractor.extractRegion(profileName, null).ifBlank {
                RegionExtractor.extractRegion(groupName, null)
            }
            if (fromRegion.isNotBlank()) {
                country = cleanCountryName(fromRegion)
            }
        }

        if (country.isEmpty()) {
            val inferred = inferCountryFromName(profileName).ifEmpty { inferCountryFromName(groupName) }
            if (inferred.isNotEmpty()) {
                country = inferred
            }
        }

        if (country.isEmpty()) {
            country = profileName.ifEmpty { groupName }
        }

        val downSpeed = Formatter.formatFileSize(context, stats.rxRateProxy) + "/s"
        val upSpeed = Formatter.formatFileSize(context, stats.txRateProxy) + "/s"
        val totalDownTraffic = Formatter.formatFileSize(context, stats.rxTotal)
        val totalUpTraffic = Formatter.formatFileSize(context, stats.txTotal)
        val totalTraffic = Formatter.formatFileSize(context, stats.rxTotal + stats.txTotal)

        val durationSec = if (startTimeMs > 0) ((System.currentTimeMillis() - startTimeMs) / 1000).coerceAtLeast(0) else 0L
        val hours = durationSec / 3600
        val minutes = (durationSec % 3600) / 60
        val seconds = durationSec % 60
        val duration = if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }

        val directDownSpeed = if (showDirect) Formatter.formatFileSize(context, stats.rxRateDirect) + "/s" else ""
        val directUpSpeed = if (showDirect) Formatter.formatFileSize(context, stats.txRateDirect) + "/s" else ""

        return LiveUpdateInfo(
            profileName = profileName,
            groupName = groupName,
            downSpeed = downSpeed,
            upSpeed = upSpeed,
            totalDownTraffic = totalDownTraffic,
            totalUpTraffic = totalUpTraffic,
            totalTraffic = totalTraffic,
            duration = duration,
            protocol = protocol,
            latency = latency,
            directDownSpeed = directDownSpeed,
            directUpSpeed = directUpSpeed,
            ip = ip,
            server = server,
            country = country,
        )
    }

    fun getMockInfo(context: Context): LiveUpdateInfo {
        val isZh = Locale.getDefault().language == "zh"
        val showDirect = runCatching { DataStore.showDirectSpeed }.getOrDefault(true)
        val showGroup = runCatching { DataStore.showGroupInNotification }.getOrDefault(true)
        return LiveUpdateInfo(
            profileName = "Hong Kong 01",
            groupName = if (showGroup) "Premium" else "",
            downSpeed = "12.5 MB/s",
            upSpeed = "1.2 MB/s",
            totalDownTraffic = "1.4 GB",
            totalUpTraffic = "230 MB",
            totalTraffic = "1.6 GB",
            duration = "01:23:45",
            protocol = "VLESS",
            latency = "45ms",
            directDownSpeed = if (showDirect) "0 B/s" else "",
            directUpSpeed = if (showDirect) "0 B/s" else "",
            ip = "104.28.19.24",
            server = "hk01.example.com",
            country = if (isZh) "香港" else "Hong Kong",
        )
    }

    fun normalizeTemplate(template: String): String {
        return template
            .replace('｛', '{')
            .replace('｝', '}')
            .replace('（', '(')
            .replace('）', ')')
            .replace('【', '[')
            .replace('】', ']')
    }

    fun format(template: String, info: LiveUpdateInfo): String {
        if (template.isEmpty()) return ""
        val normalized = normalizeTemplate(template)

        val variableRegex = Regex("""\{\s*([a-zA-Z0-9_]+)\s*\}""")
        var res = variableRegex.replace(normalized) { match ->
            when (match.groupValues[1].lowercase(Locale.ROOT)) {
                "profile", "node", "name" -> info.profileName
                "group", "subscription", "sub" -> info.groupName
                "country", "location", "ip_location", "country_name", "region", "city" -> info.country
                "ip", "egress_ip", "outbound_ip" -> info.ip
                "server", "host", "address" -> info.server
                "down", "download", "rx", "speed_down", "down_speed" -> info.downSpeed
                "up", "upload", "tx", "speed_up", "up_speed" -> info.upSpeed
                "traffic_down", "total_down", "rx_total" -> info.totalDownTraffic
                "traffic_up", "total_up", "tx_total" -> info.totalUpTraffic
                "traffic_total", "total_traffic", "traffic" -> info.totalTraffic
                "duration", "time", "uptime" -> info.duration
                "protocol", "proto", "type" -> info.protocol
                "latency", "ping", "delay" -> info.latency
                "direct_down", "direct_rx" -> info.directDownSpeed
                "direct_up", "direct_tx" -> info.directUpSpeed
                else -> ""
            }
        }

        if (info.groupName.isEmpty()) {
            res = res.replace("[]", "").replace("【】", "")
        }
        if (info.ip.isEmpty()) {
            res = res.replace("()", "").replace("（）", "")
        }
        if (info.country.isEmpty()) {
            res = res.replace("()", "").replace("（）", "")
        }
        if (info.directDownSpeed.isEmpty() && info.directUpSpeed.isEmpty()) {
            res = res.replace(Regex("""直连[:：]\s*(↑\s*)?(↓\s*)?"""), "")
        }
        if (info.downSpeed.isEmpty() && info.upSpeed.isEmpty()) {
            res = res.replace(Regex("""代理[:：]\s*(↑\s*)?(↓\s*)?"""), "")
        }

        res = res.replace(Regex("""\|\s*\|"""), "|")
        res = res.trim().removePrefix("|").removeSuffix("|").trim()
        return res.replace(Regex("""\s+"""), " ").trim()
    }

    fun getTemplates(): Templates {
        return Templates(
            collapsed = DataStore.liveUpdateCollapsedFormat.ifEmpty { TPL_GROUP_FIRST_COLLAPSED },
            title = DataStore.liveUpdateTitleFormat.ifEmpty { TPL_GROUP_FIRST_TITLE },
            content = DataStore.liveUpdateContentFormat.ifEmpty { TPL_GROUP_FIRST_CONTENT },
            subText = DataStore.liveUpdateSubtextFormat,
        )
    }

    fun getPresetTemplates(preset: String): Templates {
        return when (preset) {
            PRESET_NODE_FIRST, PRESET_COMPACT -> Templates(
                TPL_NODE_FIRST_COLLAPSED,
                TPL_NODE_FIRST_TITLE,
                TPL_NODE_FIRST_CONTENT,
                TPL_NODE_FIRST_SUBTEXT
            )
            PRESET_MINIMAL_IP -> Templates(
                TPL_MINIMAL_IP_COLLAPSED,
                TPL_MINIMAL_IP_TITLE,
                TPL_MINIMAL_IP_CONTENT,
                TPL_MINIMAL_IP_SUBTEXT
            )
            PRESET_FULL_STATS, PRESET_DETAILED -> Templates(
                TPL_FULL_STATS_COLLAPSED,
                TPL_FULL_STATS_TITLE,
                TPL_FULL_STATS_CONTENT,
                TPL_FULL_STATS_SUBTEXT
            )
            else -> Templates(
                TPL_GROUP_FIRST_COLLAPSED,
                TPL_GROUP_FIRST_TITLE,
                TPL_GROUP_FIRST_CONTENT,
                TPL_GROUP_FIRST_SUBTEXT
            )
        }
    }
}
