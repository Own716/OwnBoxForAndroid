package io.nekohasekai.sagernet.database

import android.os.Binder
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.boolean
import io.nekohasekai.sagernet.ktx.int
import io.nekohasekai.sagernet.ktx.long
import io.nekohasekai.sagernet.ktx.parsePort
import io.nekohasekai.sagernet.ktx.string
import io.nekohasekai.sagernet.ktx.stringSet
import io.nekohasekai.sagernet.ktx.stringToInt
import io.nekohasekai.sagernet.ktx.stringToIntIfExists
import io.nekohasekai.sagernet.ktx.stringToLong
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.TempDatabase

object DataStore : OnPreferenceDataStoreChangeListener {

    // share service state in main & bg process
    @Volatile
    var serviceState = BaseService.State.Idle

    @Volatile
    var mixedInboundAuthed: Boolean = false

    val configurationStore = RoomPreferenceDataStore(PublicDatabase.kvPairDao)
    val profileCacheStore = RoomPreferenceDataStore(TempDatabase.profileCacheDao)

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) { currentGroupId() } // "ungrouped" group id = 1

    // only in bg process
    var vpnService: VpnService? = null
    var baseService: BaseService.Interface? = null

    // main

    var runningTest = false

    fun currentGroupId(): Long {
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            val exists = SagerDatabase.groupDao.getById(currentSelected)
            if (exists != null) return currentSelected
        }
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isNotEmpty()) {
            val groupId = groups[0].id
            selectedGroup = groupId
            return groupId
        }
        return 0L
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            group = SagerDatabase.groupDao.getById(currentSelected)
        }
        if (group != null) return group
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            return ProxyGroup(ungrouped = true).apply { id = 0L }
        } else {
            group = groups[0]
            selectedGroup = group.id
            return group
        }
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        if (current.id > 0L && current.type == GroupType.BASIC) return current.id
        val groups = SagerDatabase.groupDao.allGroups()
        val basicGroup = groups.find { it.type == GroupType.BASIC }
        if (basicGroup != null) return basicGroup.id
        val ungrouped = groups.find { it.ungrouped }
        if (ungrouped != null) return ungrouped.id
        return SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
    }

    var appTLSVersion by configurationStore.string(Key.APP_TLS_VERSION)
    var enableClashAPI by configurationStore.boolean(Key.ENABLE_CLASH_API) { true }
    var showBottomBar by configurationStore.boolean(Key.SHOW_BOTTOM_BAR)
    var confirmProfileDelete by configurationStore.boolean(Key.CONFIRM_PROFILE_DELETE) { true }
    var groupLayoutMode by configurationStore.stringToInt(Key.GROUP_LAYOUT_MODE) { 0 }
    var profileCardStyle by configurationStore.stringToInt(Key.PROFILE_CARD_STYLE) { 0 }
    var profileViewMode by configurationStore.stringToInt(Key.PROFILE_VIEW_MODE) { 0 }
    var showBalancerSummary by configurationStore.boolean(Key.SHOW_BALANCER_SUMMARY) { true }
    var activeLeafProfileId by configurationStore.long("activeLeafProfileId") { 0L }
    var activeLeafProfileName by configurationStore.string("activeLeafProfileName") { "" }
    var activeBalancerId by configurationStore.long("activeBalancerId") { 0L }
    var showSubscriptionInfoCard by configurationStore.boolean(Key.SHOW_SUBSCRIPTION_INFO_CARD) { true }
    var customThemeColor by configurationStore.int("custom_theme_color") { 0x00E676 }
    var hapticFeedback by configurationStore.boolean(Key.HAPTIC_FEEDBACK) { true }
    var showAllGroupsTab by configurationStore.boolean(Key.SHOW_ALL_GROUPS_TAB) { false }
    var allGroupsOrder by configurationStore.int(Key.ALL_GROUPS_ORDER) { GroupOrder.ORIGIN }

    var allowInsecureOnRequest by configurationStore.boolean(Key.ALLOW_INSECURE_ON_REQUEST)
    var pinnedDiagnosticsPlatforms by configurationStore.string("pinned_diagnostics_platforms") { "gemini,cloudflare,reddit" }

    fun getPinnedDiagnostics(): List<String> {
        val list = pinnedDiagnosticsPlatforms.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isNotEmpty()) list.take(3) else listOf("gemini", "cloudflare", "reddit")
    }

    fun setPinnedDiagnostics(list: List<String>) {
        pinnedDiagnosticsPlatforms = list.take(3).joinToString(",")
    }
    var networkChangeResetConnections by configurationStore.boolean(Key.NETWORK_CHANGE_RESET_CONNECTIONS) { false }
    var wakeResetConnections by configurationStore.boolean(Key.WAKE_RESET_CONNECTIONS)

    //

    var isExpert by configurationStore.boolean(Key.APP_EXPERT)
    var appTheme by configurationStore.int(Key.APP_THEME) { Theme.LIGHT_GRAY }
    var useSystemTheme by configurationStore.boolean(Key.USE_SYSTEM_THEME) { false }
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME)
    var appIconMode by configurationStore.stringToInt(Key.APP_ICON_MODE) { 0 }
    var appLanguage by configurationStore.string(Key.APP_LANGUAGE) { "" }
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }

    var trafficSniffing by configurationStore.stringToInt(Key.TRAFFIC_SNIFFING) { 1 }
    var resolveDestination by configurationStore.boolean(Key.RESOLVE_DESTINATION)

    var mtu by configurationStore.stringToInt(Key.MTU) { 1360 }

    var bypassLan by configurationStore.boolean(Key.BYPASS_LAN)
    var bypassLanInCore by configurationStore.boolean(Key.BYPASS_LAN_IN_CORE)
    var blockQuic by configurationStore.boolean("block_quic") { true }
    var trustedNetworkSmartEnabled by configurationStore.boolean("trusted_network_smart_enabled") { false }
    var trustedWifiSsids by configurationStore.stringSet("trusted_wifi_ssids") { emptySet() }
    var trustedLanIps by configurationStore.stringSet("trusted_lan_ips") { emptySet() }
    var trustedWanIps by configurationStore.stringSet("trusted_wan_ips") { emptySet() }
    var concurrentDial by configurationStore.boolean(Key.CONCURRENT_DIAL)
    var dualNetworkAcceleration by configurationStore.boolean(Key.DUAL_NETWORK_ACCELERATION)
    var autoSelectLowestLatency by configurationStore.boolean(Key.AUTO_SELECT_LOWEST_LATENCY)

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)
    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL)
    var showGroupInNotification by configurationStore.boolean(Key.SHOW_GROUP_IN_NOTIFICATION)
    var liveUpdateEnabled by configurationStore.boolean(Key.LIVE_UPDATE_ENABLED)
    var liveUpdateShowCapsule by configurationStore.boolean(Key.LIVE_UPDATE_SHOW_CAPSULE) { true }
    var liveUpdatePreset by configurationStore.string(Key.LIVE_UPDATE_PRESET) { "group_first" }
    var liveUpdateCollapsedFormat by configurationStore.string(Key.LIVE_UPDATE_COLLAPSED_FORMAT) { "{group}" }
    var liveUpdateTitleFormat by configurationStore.string(Key.LIVE_UPDATE_TITLE_FORMAT) { "{group}" }
    var liveUpdateContentFormat by configurationStore.string(Key.LIVE_UPDATE_CONTENT_FORMAT) { "↓ {down}   ↑ {up}" }
    var liveUpdateSubtextFormat by configurationStore.string(Key.LIVE_UPDATE_SUBTEXT_FORMAT) { "{profile} | {ip}" }
    var notificationActions by configurationStore.stringSet(Key.NOTIFICATION_ACTIONS) { setOf("stop", "next", "switch") }

    var globalCustomConfig by configurationStore.string(Key.GLOBAL_CUSTOM_CONFIG) { "" }

    var remoteDns by configurationStore.string(Key.REMOTE_DNS) { "https://dns.google/dns-query" }
    var directDns by configurationStore.string(Key.DIRECT_DNS) { "https://223.5.5.5/dns-query" }
    var enableDnsRouting by configurationStore.boolean(Key.ENABLE_DNS_ROUTING) { true }
    var enableFakeDns by configurationStore.boolean(Key.ENABLE_FAKEDNS) { true }

    var rulesProvider by configurationStore.stringToInt(Key.RULES_PROVIDER)
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL)
    var logBufSize by configurationStore.int(Key.LOG_BUF_SIZE) { 0 }
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK)
    var hideFromRecentApps by configurationStore.boolean(Key.HIDE_FROM_RECENT_APPS)
    // 记录用户选择"不再显示"的预览版版本号，仅对该版本隐藏提示
    var previewHintDismissedVersion by configurationStore.string(Key.PREVIEW_HINT_DISMISSED_VERSION) { "" }

    var rulesGeositeUrl by configurationStore.string(Key.RULES_GEOSITE_URL) { "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db" }
    var rulesGeoipUrl by configurationStore.string(Key.RULES_GEOIP_URL) { "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db" }
    var rulesUpdateInterval by configurationStore.string(Key.RULES_UPDATE_INTERVAL) { "0" } // 默认为0，不自动更新
    var activeGeoipDb by configurationStore.string("active_geoip_db") { "geoip.db" }
    var activeGeositeDb by configurationStore.string("active_geosite_db") { "geosite.db" }
    fun getAssetUpdateUrl(fileName: String): String = configurationStore.getString("asset_url_$fileName", "") ?: ""
    fun setAssetUpdateUrl(fileName: String, url: String) = configurationStore.putString("asset_url_$fileName", url)
    var customRuleSets by configurationStore.stringSet("custom_rule_sets") { emptySet() }

    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }

    var mixedPort: Int
        get() = getLocalPort(Key.MIXED_PORT, 2080)
        set(value) = saveLocalPort(Key.MIXED_PORT, value)

    var disableMixedInbound by configurationStore.boolean(Key.DISABLE_MIXED_INBOUND)

    // 仅在 TUN 模式下真正生效；系统代理模式必须保留 mixed 入站
    val mixedInboundDisabled: Boolean
        get() = disableMixedInbound && serviceMode == Key.MODE_VPN

    // 混合入站账密由用户设置决定：用户名留空即不启用认证（本机回环免密直连）
    var mixedUsername by configurationStore.string(Key.MIXED_USERNAME) { "" }
    var mixedPassword by configurationStore.string(Key.MIXED_PASSWORD) { "" }

    val mixedInboundNeedsAuth: Boolean
        get() = serviceMode == Key.MODE_VPN && !mixedInboundDisabled && mixedUsername.isNotBlank()

    val mixedInboundUser: String get() = if (mixedInboundAuthed) mixedUsername else ""
    val mixedInboundPass: String get() = if (mixedInboundAuthed) mixedPassword else ""

    var defaultSubscriptionUserAgent: String
        get() {
            val stored = configurationStore.getString(Key.DEFAULT_SUBSCRIPTION_USER_AGENT, "")
            return if (!stored.isNullOrBlank()) stored else "NekoBox/Android/1.4.2 (Prefer ClashMeta Format)"
        }
        set(value) = configurationStore.putString(Key.DEFAULT_SUBSCRIPTION_USER_AGENT, value)

    var hideUnavailableProfiles by configurationStore.boolean(Key.HIDE_UNAVAILABLE_PROFILES) { false }

    fun migrateSubscriptionUserAgents(targetUa: String? = null, forceAll: Boolean = false) {
        val compatDefaultUa = "NekoBox/Android/1.4.2 (Prefer ClashMeta Format)"
        if (targetUa == null && !forceAll) {
            val storedDefault = configurationStore.getString(Key.DEFAULT_SUBSCRIPTION_USER_AGENT, "")
            if (storedDefault == "sing-box/1.15.0") {
                configurationStore.putString(Key.DEFAULT_SUBSCRIPTION_USER_AGENT, compatDefaultUa)
            }
        }
        val newUa = targetUa?.takeIf { it.isNotBlank() } ?: defaultSubscriptionUserAgent
        try {
            val groupDao = SagerDatabase.groupDao
            val allGroups = groupDao.allGroups()
            var changed = false
            for (group in allGroups) {
                val sub = group.subscription ?: continue
                if (sub.lockUserAgent == true) continue
                val ua = sub.customUserAgent
                if (forceAll || ua.isNullOrBlank() || (targetUa == null && ua == "sing-box/1.15.0")) {
                    sub.customUserAgent = newUa
                    groupDao.updateGroup(group)
                    changed = true
                }
            }
            if (changed) {
                Logs.d("Updated subscription User-Agents to: $newUa")
            }
        } catch (e: Throwable) {
            Logs.w(e)
        }
    }

    fun migrateProfilesOnAppUpdate() {
        val lastCode = configurationStore.getInt("last_app_version_code", 0)
        val currentCode = BuildConfig.VERSION_CODE
        if (lastCode == currentCode) return

        runOnDefaultDispatcher {
            try {
                Logs.i("Migrating existing profiles on app update: $lastCode -> $currentCode")
                val proxyDao = SagerDatabase.proxyDao
                val allProfiles = proxyDao.getAll()
                val toUpdate = mutableListOf<ProxyEntity>()
                for (entity in allProfiles) {
                    try {
                        val bean = entity.requireBean()
                        var modified = false
                        when (bean) {
                            is ShadowsocksBean -> {
                                val normalized = io.nekohasekai.sagernet.fmt.shadowsocks.normalizeShadowsocksCipher(bean.method)
                                if (bean.method != normalized) {
                                    bean.method = normalized
                                    modified = true
                                }
                            }
                            is StandardV2RayBean -> {
                                if (bean.type == "xhttp" && bean.xhttpMode.isNullOrBlank()) {
                                    bean.xhttpMode = "auto"
                                    modified = true
                                }
                            }
                        }
                        if (modified && bean.serverAddress.isNotBlank() && bean.serverAddress != "127.0.0.1") {
                            entity.putBean(bean)
                            toUpdate.add(entity)
                        }
                    } catch (t: Throwable) {
                        Logs.w("Skipping unreadable profile during upgrade migration: ${entity.id}", t)
                    }
                }
                if (toUpdate.isNotEmpty()) {
                    proxyDao.updateProxy(toUpdate)
                    Logs.i("Migrated and updated ${toUpdate.size} modified profiles on app update")
                }
                configurationStore.putInt("last_app_version_code", currentCode)
            } catch (e: Throwable) {
                Logs.w("Failed to execute migrateProfilesOnAppUpdate", e)
            }
        }
    }

    fun initGlobal() {
        if (configurationStore.getString(Key.MIXED_PORT) == null) {
            mixedPort = mixedPort
        }
        if (configurationStore.getString(Key.SMART_ROUTE_COUNTRY) == null) {
            smartRouteCountry = "cn"
        }
    }


    private fun getLocalPort(key: String, default: Int): Int {
        return parsePort(configurationStore.getString(key), default + userIndex)
    }

    private fun saveLocalPort(key: String, value: Int) {
        configurationStore.putString(key, "$value")
    }

    var ipv6Mode by configurationStore.stringToInt(Key.IPV6_MODE) { IPv6Mode.DISABLE }

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS)
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED) { true }
    var showLandingIp by configurationStore.boolean(Key.SHOW_LANDING_IP) { true }
    var hideLandingIpAddress by configurationStore.boolean("hide_landing_ip_address") { false }

    var uiIgnoreSystemFontScale by configurationStore.boolean(Key.UI_IGNORE_SYSTEM_FONT_SCALE) { false }
    var uiGlobalScale by configurationStore.int(Key.UI_GLOBAL_SCALE) { 100 }
    var uiFontScale by configurationStore.int(Key.UI_FONT_SCALE) { 100 }
    var uiToolbarTitleScale by configurationStore.int(Key.UI_TOOLBAR_TITLE_SCALE) { 100 }
    var uiToolbarIconScale by configurationStore.int(Key.UI_TOOLBAR_ICON_SCALE) { 100 }
    var uiTabBarScale by configurationStore.int(Key.UI_TAB_BAR_SCALE) { 100 }
    var uiDrawerScale by configurationStore.int(Key.UI_DRAWER_SCALE) { 100 }
    var uiProfileCardScale by configurationStore.int(Key.UI_PROFILE_CARD_SCALE) { 100 }
    var uiBottomBarScale by configurationStore.int(Key.UI_BOTTOM_BAR_SCALE) { 100 }

    val persistAcrossReboot by configurationStore.boolean(Key.PERSIST_ACROSS_REBOOT) { false }

    var httpProxyBypass by configurationStore.string(Key.HTTP_PROXY_BYPASS) { "" }
    var dnsHosts by configurationStore.string(Key.DNS_HOSTS) { "" }
    var strictRoute by configurationStore.boolean(Key.STRICT_ROUTE) { false }
    // false = extreme low memory GC mode (default); true = high performance, allow high RAM
    var performancePriorityMode by configurationStore.boolean(Key.PERFORMANCE_PRIORITY_MODE) { false }
    private var rawConnectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) {
        SagerNet.application.getString(R.string.default_connection_test_url)
    }
    var connectionTestURL: String
        get() {
            val url = rawConnectionTestURL.trim()
            if (url.isBlank()) {
                val newUrl = SagerNet.application.getString(R.string.default_connection_test_url)
                rawConnectionTestURL = newUrl
                return newUrl
            }
            return url
        }
        set(value) {
            rawConnectionTestURL = value.trim()
        }
    var connectionTestConcurrent by configurationStore.int(Key.CONNECTION_TEST_CONCURRENT) {
        SagerNet.application.getString(R.string.default_connection_test_concurrent).toInt()
    }
    var connectionTestTimeout by configurationStore.int(Key.CONNECTION_TEST_TIMEOUT) { 3000 }
    var speedTestMode by configurationStore.string(Key.SPEED_TEST_MODE) {
        SagerNet.application.getString(R.string.default_speed_test_mode)
    }
    var speedTestTimeoutMs by configurationStore.stringToInt(Key.SPEED_TEST_TIMEOUT_MS) {
        SagerNet.application.getString(R.string.default_speed_test_timeout_ms).toInt()
    }
    var speedTestServerListURL by configurationStore.string(Key.SPEED_TEST_SERVER_LIST_URL) {
        SagerNet.application.getString(R.string.default_speed_test_server_list_url)
    }
    var speedTestFallbackServerListURL by configurationStore.string(Key.SPEED_TEST_FALLBACK_SERVER_LIST_URL) {
        SagerNet.application.getString(R.string.default_speed_test_fallback_server_list_url)
    }
    var simpleDownloadURL by configurationStore.string(Key.SIMPLE_DOWNLOAD_URL) {
        SagerNet.application.getString(R.string.default_simple_download_url)
    }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS)
    var compactProfileRows by configurationStore.boolean(Key.COMPACT_PROFILE_ROWS)
    var compactGroupCards by configurationStore.boolean(Key.COMPACT_GROUP_CARDS) { true }

    var tunImplementation: Int
        get() = TunImplementation.SING_TUN
        set(_) {
            configurationStore.putString(Key.TUN_IMPLEMENTATION, TunImplementation.SING_TUN.toString())
        }
    var profileTrafficStatistics by configurationStore.boolean(Key.PROFILE_TRAFFIC_STATISTICS) { true }

    var yacdURL by configurationStore.string("yacdURL") { "http://127.0.0.1:9090/ui" }
    var customDashboardListJson by configurationStore.string("customDashboardListJson") { "" }

    val clashApiSecret: String
        get() = PublicDatabase.instance.runInTransaction(java.util.concurrent.Callable {
            val key = "clashApiSecret"
            PublicDatabase.kvPairDao[key]?.string?.takeIf { it.length == 64 } ?: run {
                val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                val value = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
                PublicDatabase.kvPairDao.put(io.nekohasekai.sagernet.database.preference.KeyValuePair(key).put(value))
                value
            }
        })

    // protocol

    var globalAllowInsecure by configurationStore.boolean(Key.GLOBAL_ALLOW_INSECURE) { false }

    var enableTLSFragment by configurationStore.boolean(Key.ENABLE_TLS_FRAGMENT) { false }
    var fragmentLength by configurationStore.string(Key.FRAGMENT_LENGTH) { "100-200" }
    var fragmentInterval by configurationStore.string(Key.FRAGMENT_INTERVAL) { "10-20" }

    // old cache, DO NOT ADD

    var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
    var profileName by profileCacheStore.string(Key.PROFILE_NAME)
    var serverAddress by profileCacheStore.string(Key.SERVER_ADDRESS)
    var serverPort by profileCacheStore.stringToInt(Key.SERVER_PORT)
    var serverPorts by profileCacheStore.string("serverPorts")
    var serverUsername by profileCacheStore.string(Key.SERVER_USERNAME)
    var serverPassword by profileCacheStore.string(Key.SERVER_PASSWORD)
    var serverPassword1 by profileCacheStore.string(Key.SERVER_PASSWORD1)
    var serverMethod by profileCacheStore.string(Key.SERVER_METHOD)

    var sharedStorage by profileCacheStore.string("sharedStorage")

    var serverProtocol by profileCacheStore.string(Key.SERVER_PROTOCOL)
    var serverObfs by profileCacheStore.string(Key.SERVER_OBFS)
    var serverObfsType by profileCacheStore.string(Key.SERVER_OBFS_TYPE) { "salamander" }
    var serverProtocolParam by profileCacheStore.string(Key.SERVER_PROTOCOL_PARAM)
    var serverObfsParam by profileCacheStore.string(Key.SERVER_OBFS_PARAM)

    var serverNetwork by profileCacheStore.string(Key.SERVER_NETWORK)
    var serverHost by profileCacheStore.string(Key.SERVER_HOST)
    var serverPath by profileCacheStore.string(Key.SERVER_PATH)
    var serverSNI by profileCacheStore.string(Key.SERVER_SNI)
    var serverEncryption by profileCacheStore.string(Key.SERVER_ENCRYPTION)
    var serverALPN by profileCacheStore.string(Key.SERVER_ALPN)
    var serverCertificates by profileCacheStore.string(Key.SERVER_CERTIFICATES)
    var serverMTU by profileCacheStore.stringToInt(Key.SERVER_MTU)
    var serverHeaders by profileCacheStore.string(Key.SERVER_HEADERS)
    var serverAllowInsecure by profileCacheStore.boolean(Key.SERVER_ALLOW_INSECURE)

    var serverAuthType by profileCacheStore.stringToInt(Key.SERVER_AUTH_TYPE)
    var serverUploadSpeed by profileCacheStore.stringToInt(Key.SERVER_UPLOAD_SPEED)
    var serverDownloadSpeed by profileCacheStore.stringToInt(Key.SERVER_DOWNLOAD_SPEED)
    var serverStreamReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_STREAM_RECEIVE_WINDOW)
    var serverConnectionReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_CONNECTION_RECEIVE_WINDOW)
    var serverDisableMtuDiscovery by profileCacheStore.boolean(Key.SERVER_DISABLE_MTU_DISCOVERY)
    var serverHopInterval by profileCacheStore.stringToInt(Key.SERVER_HOP_INTERVAL) { 10 }

    var protocolVersion by profileCacheStore.stringToInt(Key.PROTOCOL_VERSION) { 2 } // default is SOCKS5

    var serverProtocolInt by profileCacheStore.stringToInt(Key.SERVER_PROTOCOL)
    var serverPrivateKey by profileCacheStore.string(Key.SERVER_PRIVATE_KEY)
    var serverInsecureConcurrency by profileCacheStore.stringToInt(Key.SERVER_INSECURE_CONCURRENCY)

    var serverUDPRelayMode by profileCacheStore.string(Key.SERVER_UDP_RELAY_MODE)
    var serverCongestionController by profileCacheStore.string(Key.SERVER_CONGESTION_CONTROLLER)
    var serverDisableSNI by profileCacheStore.boolean(Key.SERVER_DISABLE_SNI)
    var serverReduceRTT by profileCacheStore.boolean(Key.SERVER_REDUCE_RTT)

    var serverUserId by profileCacheStore.string(Key.SERVER_USER_ID)
    var serverPinnedCertChainSha256 by profileCacheStore.string(Key.SERVER_PINNED_CERT_CHAIN_SHA256)

    var routeName by profileCacheStore.string(Key.ROUTE_NAME)
    var routeDomain by profileCacheStore.string(Key.ROUTE_DOMAIN)
    var routeIP by profileCacheStore.string(Key.ROUTE_IP)
    var routePort by profileCacheStore.string(Key.ROUTE_PORT)
    var routeSourcePort by profileCacheStore.string(Key.ROUTE_SOURCE_PORT)
    var routeNetwork by profileCacheStore.string(Key.ROUTE_NETWORK)
    var routeSource by profileCacheStore.string(Key.ROUTE_SOURCE)
    var routeProtocol by profileCacheStore.string(Key.ROUTE_PROTOCOL)
    var routeRuleset by profileCacheStore.string(Key.ROUTE_RULESET)
    var routeOutbound by profileCacheStore.stringToInt(Key.ROUTE_OUTBOUND)
    var routeOutboundRule by profileCacheStore.long(Key.ROUTE_OUTBOUND + "Long")
    var routePackages by profileCacheStore.string(Key.ROUTE_PACKAGES)

    var frontProxy by profileCacheStore.long(Key.GROUP_FRONT_PROXY + "Long")
    var landingProxy by profileCacheStore.long(Key.GROUP_LANDING_PROXY + "Long")
    var frontProxyTmp by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxyTmp by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)
    var serverCustom by profileCacheStore.string(Key.SERVER_CUSTOM)
    var serverCustomOutbound by profileCacheStore.string(Key.SERVER_CUSTOM_OUTBOUND)

    var balancerType by profileCacheStore.stringToInt("balancerType") { 0 }
    var balancerTargetGroup by profileCacheStore.stringToLong("balancerTargetGroup") { 0L }
    var balancerTargetGroups by profileCacheStore.string("balancerTargetGroups") { "" }
    var balancerStrategy by profileCacheStore.string("balancerStrategy") { "random" }
    var balancerTestUrl by profileCacheStore.string("balancerTestUrl") { "" }
    var balancerInterval by profileCacheStore.stringToInt("balancerInterval") { 300 }
    var balancerTolerance by profileCacheStore.stringToInt("balancerTolerance") { 300 }
    var balancerToleranceUnit by profileCacheStore.string("balancerToleranceUnit") { "ms" }
    var balancerUseFrontProxy by profileCacheStore.boolean("balancerUseFrontProxy")
    var balancerUseLandingProxy by profileCacheStore.boolean("balancerUseLandingProxy")
    var balancerFrontProxy by profileCacheStore.long("balancerFrontProxyLong")
    var balancerLandingProxy by profileCacheStore.long("balancerLandingProxyLong")
    var balancerFrontProxyTmp by profileCacheStore.stringToInt("balancerFrontProxy")
    var balancerLandingProxyTmp by profileCacheStore.stringToInt("balancerLandingProxy")
    var balancerNameExclude by profileCacheStore.string("balancerNameExclude") { "" }
    var balancerNameInclude by profileCacheStore.string("balancerNameInclude") { "" }

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)
    var groupOrder by profileCacheStore.stringToInt(Key.GROUP_ORDER)
    var groupIsSelector by profileCacheStore.boolean(Key.GROUP_IS_SELECTOR)
    var groupIsUrlTest by profileCacheStore.boolean("groupIsUrlTest")
    var groupIsLoadBalance by profileCacheStore.boolean("groupIsLoadBalance")
    private var rawGroupUrlTestUrl by profileCacheStore.string("groupUrlTestUrl") {
        SagerNet.application.getString(R.string.default_connection_test_url)
    }
    var groupUrlTestUrl: String
        get() {
            val url = rawGroupUrlTestUrl.trim()
            if (url.isBlank()) {
                val newUrl = SagerNet.application.getString(R.string.default_connection_test_url)
                rawGroupUrlTestUrl = newUrl
                return newUrl
            }
            return url
        }
        set(value) {
            rawGroupUrlTestUrl = value.trim()
        }
    var groupUrlTestInterval by profileCacheStore.stringToInt("groupUrlTestInterval") { 300 }
    var groupUrlTestTolerance by profileCacheStore.stringToInt("groupUrlTestTolerance") { 50 }
    var groupUrlTestIdleTimeout by profileCacheStore.string("groupUrlTestIdleTimeout") { "30m" }
    var groupUrlTestInterruptExist by profileCacheStore.boolean("groupUrlTestInterruptExist")

    fun isGroupDisabled(groupId: Long): Boolean =
        configurationStore.getBoolean("group_${groupId}_disabled", false)
    fun setGroupDisabled(groupId: Long, disabled: Boolean) {
        configurationStore.putBoolean("group_${groupId}_disabled", disabled)
    }

    fun isGroupUrlTest(groupId: Long): Boolean =
        configurationStore.getBoolean("group_${groupId}_isUrlTest", false)
    fun setGroupUrlTest(groupId: Long, value: Boolean) {
        configurationStore.putBoolean("group_${groupId}_isUrlTest", value)
    }
    fun groupIsUrlTest(groupId: Long): Boolean = isGroupUrlTest(groupId)
    fun setGroupIsUrlTest(groupId: Long, value: Boolean) = setGroupUrlTest(groupId, value)

    fun isGroupLoadBalance(groupId: Long): Boolean =
        configurationStore.getBoolean("group_${groupId}_isLoadBalance", false)
    fun setGroupLoadBalance(groupId: Long, value: Boolean) {
        configurationStore.putBoolean("group_${groupId}_isLoadBalance", value)
    }
    fun groupIsLoadBalance(groupId: Long): Boolean = isGroupLoadBalance(groupId)
    fun setGroupIsLoadBalance(groupId: Long, value: Boolean) = setGroupLoadBalance(groupId, value)

    fun groupUrlTestUrl(groupId: Long): String {
        val stored = configurationStore.getString("group_${groupId}_urlTestUrl", "")?.trim()?.takeIf { it.isNotBlank() }
        return stored ?: connectionTestURL
    }
    fun setGroupUrlTestUrl(groupId: Long, value: String) {
        configurationStore.putString("group_${groupId}_urlTestUrl", value)
    }

    fun groupUrlTestInterval(groupId: Long): Long =
        configurationStore.getString("group_${groupId}_urlTestInterval", "300")?.toLongOrNull() ?: 300L
    fun setGroupUrlTestInterval(groupId: Long, value: Long) {
        configurationStore.putString("group_${groupId}_urlTestInterval", value.toString())
    }

    fun groupUrlTestTolerance(groupId: Long): Int =
        configurationStore.getString("group_${groupId}_urlTestTolerance", "50")?.toIntOrNull() ?: 50
    fun setGroupUrlTestTolerance(groupId: Long, value: Int) {
        configurationStore.putString("group_${groupId}_urlTestTolerance", value.toString())
    }

    fun groupUrlTestIdleTimeout(groupId: Long): String =
        configurationStore.getString("group_${groupId}_urlTestIdleTimeout", "30m") ?: "30m"
    fun setGroupUrlTestIdleTimeout(groupId: Long, value: String) {
        configurationStore.putString("group_${groupId}_urlTestIdleTimeout", value)
    }

    fun groupUrlTestInterrupt(groupId: Long): Boolean =
        configurationStore.getBoolean("group_${groupId}_urlTestInterrupt", false)
    fun setGroupUrlTestInterrupt(groupId: Long, value: Boolean) {
        configurationStore.putBoolean("group_${groupId}_urlTestInterrupt", value)
    }
    fun groupUrlTestInterruptExist(groupId: Long): Boolean = groupUrlTestInterrupt(groupId)
    fun setGroupUrlTestInterruptExist(groupId: Long, value: Boolean) = setGroupUrlTestInterrupt(groupId, value)

    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionForceResolve by profileCacheStore.boolean(Key.SUBSCRIPTION_FORCE_RESOLVE)
    var subscriptionDeduplication by profileCacheStore.boolean(Key.SUBSCRIPTION_DEDUPLICATION)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionLockUserAgent by profileCacheStore.boolean(Key.SUBSCRIPTION_LOCK_USER_AGENT) { false }
    var subscriptionRetainValidNodes by profileCacheStore.boolean(Key.SUBSCRIPTION_RETAIN_VALID_NODES) { false }
    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 360 }
    var subscriptionFilterMode by profileCacheStore.stringToInt(Key.SUBSCRIPTION_FILTER_MODE) { 0 }
    var subscriptionFilterRegex by profileCacheStore.string(Key.SUBSCRIPTION_FILTER_REGEX)
    var subscriptionServerDns by profileCacheStore.string(Key.SUBSCRIPTION_SERVER_DNS)

    var rulesFirstCreate by profileCacheStore.boolean("rulesFirstCreate")

    // var enableTLSFragment by configurationStore.boolean(Key.ENABLE_TLS_FRAGMENT)

    var webdavServer: String?
        get() = configurationStore.getString("webdavServer")
        set(value) = configurationStore.putString("webdavServer", value)

    var webdavUsername: String?
        get() = configurationStore.getString("webdavUsername")
        set(value) = configurationStore.putString("webdavUsername", value)

    var webdavPassword: String?
        get() = configurationStore.getString("webdavPassword")
        set(value) = configurationStore.putString("webdavPassword", value)

    var webdavPath: String?
        get() = configurationStore.getString("webdavPath") ?: "Throne"  // 设置默认值
        set(value) = configurationStore.putString("webdavPath", value)

    var globalMode by configurationStore.boolean(Key.GLOBAL_MODE)
    var smartMode by configurationStore.boolean(Key.SMART_MODE) { false }
    var smartRouteCountry by configurationStore.string(Key.SMART_ROUTE_COUNTRY) { "cn" }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
    }
}
