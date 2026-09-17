package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityLanSharingBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LanSharingActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: ActivityLanSharingBinding

    private var detectedWifiIp: String? = null
    private var detectedHotspotIp: String? = null
    private var isRestarting = false

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    data class ClientDevice(
        val ip: String,
        var activeConnections: Int,
        var uploadBytes: Long,
        var downloadBytes: Long,
        var lastHost: String,
        var lastSeenTime: Long,
        var isHotspot: Boolean
    )

    private val clientHistoryMap = ConcurrentHashMap<String, ClientDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanSharingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        setupViews()
        connection.connect(this, this)
        refreshNetworkInfo()

        // 启动后台轮询监听正在使用的代理设备 (Clash API & 本机网络连接)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    pollClientDevices()
                    delay(2000)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.disconnect(this)
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkInfo()
        updateUIState()
        pollClientDevices()
    }

    // --- SagerConnection.Callback ---
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        if (state == BaseService.State.Connected || state == BaseService.State.Stopped) {
            isRestarting = false
        }
        updateUIState()
        pollClientDevices()
    }

    override fun onServiceConnected(service: ISagerNetService) {
        updateUIState()
        pollClientDevices()
    }

    private fun setupViews() {
        // 主开关监听
        binding.switchLanSharing.isChecked = DataStore.allowAccess
        binding.switchLanSharing.setOnCheckedChangeListener { _, isChecked ->
            if (DataStore.allowAccess != isChecked) {
                DataStore.allowAccess = isChecked
                updateUIState()
                restartServiceIfNeeded(
                    if (isChecked) R.string.lan_sharing_turn_on else R.string.lan_sharing_turn_off
                )
            }
        }

        binding.rowSwitchLanSharing.setOnClickListener {
            binding.switchLanSharing.toggle()
        }

        // 热点主机名 (IP) 复制
        val copyHotspotHostAction = {
            val ip = detectedHotspotIp ?: "192.168.43.1"
            copyToClipboard(ip, getString(R.string.lan_sharing_copied_ip, ip))
        }
        binding.btnCopyHotspotIp.setOnClickListener { copyHotspotHostAction() }

        // 热点端口复制
        val copyHotspotPortAction = {
            val port = DataStore.mixedPort
            copyToClipboard(port.toString(), getString(R.string.lan_sharing_copied_port, port))
        }
        binding.btnCopyHotspotPort.setOnClickListener { copyHotspotPortAction() }

        // 同 Wi-Fi 主机名 (IP) 复制
        val copyWifiHostAction = {
            val ip = detectedWifiIp
            if (!ip.isNullOrBlank()) {
                copyToClipboard(ip, getString(R.string.lan_sharing_copied_ip, ip))
            } else {
                Toast.makeText(this, R.string.lan_sharing_wifi_not_connected_tip, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCopyWifiIp.setOnClickListener { copyWifiHostAction() }

        // 同 Wi-Fi 端口复制
        val copyWifiPortAction = {
            val port = DataStore.mixedPort
            copyToClipboard(port.toString(), getString(R.string.lan_sharing_copied_port, port))
        }
        binding.btnCopyWifiPort.setOnClickListener { copyWifiPortAction() }

        // 访问认证配置
        val authAction = {
            showAuthDialog()
        }
        binding.rowAuth.setOnClickListener { authAction() }
        binding.btnAuthAction.setOnClickListener { authAction() }

        // 刷新活跃设备按钮
        binding.btnRefreshDevices.setOnClickListener {
            pollClientDevices()
            Toast.makeText(this, R.string.action_refresh, Toast.LENGTH_SHORT).show()
        }

        // 快捷教程卡片点击
        binding.cardTutorialShortcut.setOnClickListener {
            showTutorialDialog()
        }
    }

    private fun restartServiceIfNeeded(fallbackToastRes: Int? = null) {
        if (DataStore.serviceState.canStop) {
            Toast.makeText(this, R.string.lan_sharing_restarting_service, Toast.LENGTH_SHORT).show()
            isRestarting = true
            updateUIState()
            // 发送 Action.RESTART 广播，BaseService 执行 stopRunner(restart = true)
            // 完整释放旧核心后再启动新核心，确保 100% 重新生成并绑定 0.0.0.0 端口
            SagerNet.restartService()
        } else {
            fallbackToastRes?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isHotspotActive(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            val isEnabled = runCatching {
                val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                method.invoke(wifiManager) as? Boolean == true
            }.getOrNull()
            if (isEnabled == true) return true

            val apState = runCatching {
                val method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
                method.isAccessible = true
                method.invoke(wifiManager) as? Int ?: 0
            }.getOrNull()
            // 12 = WIFI_AP_STATE_ENABLING, 13 = WIFI_AP_STATE_ENABLED
            if (apState == 13 || apState == 12) return true
        }

        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val tetheredIfaces = runCatching {
                val method = cm.javaClass.getDeclaredMethod("getTetheredIfaces")
                method.isAccessible = true
                (method.invoke(cm) as? Array<*>)?.filterIsInstance<String>().orEmpty()
            }.getOrDefault(emptyList())
            if (tetheredIfaces.isNotEmpty()) return true
        }

        return false
    }

    private fun isHotspotIpPattern(ip: String): Boolean {
        return ip.startsWith("192.168.43.") ||
                ip.startsWith("192.168.44.") ||
                ip.startsWith("192.168.49.") ||
                ip.startsWith("192.168.50.") ||
                ip.startsWith("172.20.10.")
    }

    private fun isCellularOrVirtualInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("rmnet") || n.startsWith("v4-") || n.startsWith("r_") ||
                n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("wwan") ||
                n.startsWith("dummy") || n.startsWith("tun") || n.startsWith("sit") ||
                n.startsWith("ip6") || n.startsWith("clat") || n.startsWith("seth") ||
                n.startsWith("ipa") || n.startsWith("epdg") || n.startsWith("bond")
    }

    private fun isValidHotspotIp(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "0.0.0.0" || ip.startsWith("169.254.") || ip.startsWith("172.19.")) {
            return false
        }
        return true
    }

    private fun isHotspotInterface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("ap") || n.startsWith("wlan1") || n.startsWith("swlan") ||
                n.startsWith("softap") || n.startsWith("wigig") || n.contains("hotspot")
    }

    private fun refreshNetworkInfo() {
        var wifiIp: String? = null
        var isWifiConnected = false
        val hotspotActive = isHotspotActive()
        var hotspotIp: String? = null

        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val isWifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

        // 1. Wi-Fi 状态与 IP 检测（仅当系统 Wi-Fi 功能已打开时才检测，彻底杜绝 Wi-Fi 关闭时误显）
        if (isWifiEnabled && cm != null) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val ip = linkProps.linkAddresses
                        .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                        .firstOrNull { !it.startsWith("127.") && !it.startsWith("169.254.") }
                    if (!ip.isNullOrBlank()) {
                        wifiIp = ip
                        isWifiConnected = true
                        break
                    }
                }
            }

            if (!isWifiConnected && wifiManager != null) {
                val info = runCatching { wifiManager.connectionInfo }.getOrNull()
                val ipInt = info?.ipAddress ?: 0
                if (ipInt != 0 && info?.networkId != -1) {
                    val ip = String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                    if (ip != "0.0.0.0") {
                        wifiIp = ip
                        isWifiConnected = true
                    }
                }
            }
        }

        // 2. 手机热点 IP 检测（方案 A）
        if (hotspotActive) {
            val tetheredIfaces = runCatching {
                val method = cm?.javaClass?.getDeclaredMethod("getTetheredIfaces")
                method?.isAccessible = true
                (method?.invoke(cm) as? Array<*>)?.filterIsInstance<String>().orEmpty()
            }.getOrDefault(emptyList())

            val interfaces = runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            }.getOrDefault(emptyList())

            // 优先检查 tethered 明确指定的网卡
            for (tetherName in tetheredIfaces) {
                val intf = interfaces.firstOrNull { it.name.equals(tetherName, ignoreCase = true) } ?: continue
                val ipList = intf.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .filter { isValidHotspotIp(it) }

                val matchedIp = ipList.firstOrNull { isHotspotIpPattern(it) } ?: ipList.firstOrNull()
                if (matchedIp != null) {
                    hotspotIp = matchedIp
                    break
                }
            }

            // 若未找到，遍历活跃网卡匹配热点专用接口名称 (如 ap0, swlan0 等)
            if (hotspotIp == null) {
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    val name = intf.name.lowercase()
                    if (isCellularOrVirtualInterface(name)) continue

                    if (isHotspotInterface(name)) {
                        val ipList = intf.inetAddresses.toList()
                            .filterIsInstance<Inet4Address>()
                            .mapNotNull { it.hostAddress }
                            .filter { isValidHotspotIp(it) }
                        val matchedIp = ipList.firstOrNull { isHotspotIpPattern(it) } ?: ipList.firstOrNull()
                        if (matchedIp != null) {
                            hotspotIp = matchedIp
                            break
                        }
                    }
                }
            }

            // 再次回退检查标准 192.168.43.x 网段
            if (hotspotIp == null) {
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    val name = intf.name.lowercase()
                    if (isCellularOrVirtualInterface(name)) continue

                    val ip = intf.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                        .firstOrNull { isHotspotIpPattern(it) }
                    if (ip != null) {
                        hotspotIp = ip
                        break
                    }
                }
            }

            // 若系统沙箱隔离无权限读取，回退为黄金标准 192.168.43.1
            if (hotspotIp.isNullOrBlank()) {
                hotspotIp = "192.168.43.1"
            }
        } else {
            hotspotIp = "192.168.43.1"
        }

        detectedWifiIp = if (isWifiConnected) wifiIp else null
        detectedHotspotIp = hotspotIp
        val port = DataStore.mixedPort

        val primaryColor = getColorAttr(R.attr.colorPrimary)
        val secondaryColor = getColorAttr(android.R.attr.textColorSecondary)

        // 更新方案 A：手机热点展示
        if (hotspotActive) {
            val ip = detectedHotspotIp ?: "192.168.43.1"
            binding.textHotspotIp.text = ip
            binding.badgeHotspotStatus.text = getString(R.string.lan_sharing_hotspot_detected)
            binding.badgeHotspotStatus.setTextColor(primaryColor)
            if (ip != "192.168.43.1") {
                binding.textHotspotDesc.text = "检测到本机热点分配网关为 $ip，其他设备连接热点后请在代理主机填入此 IP"
            } else {
                binding.textHotspotDesc.text = getString(R.string.lan_sharing_hotspot_desc)
            }
        } else {
            binding.textHotspotIp.text = "192.168.43.1"
            binding.badgeHotspotStatus.text = getString(R.string.lan_sharing_hotspot_not_active)
            binding.badgeHotspotStatus.setTextColor(secondaryColor)
            binding.textHotspotDesc.text = getString(R.string.lan_sharing_hotspot_hint_off)
        }
        binding.textHotspotPort.text = port.toString()

        // 更新方案 B：同 Wi-Fi 局域网展示
        if (isWifiConnected && !detectedWifiIp.isNullOrBlank()) {
            binding.textWifiIp.text = detectedWifiIp
            binding.badgeWifiStatus.text = getString(R.string.lan_sharing_wifi_detected)
            binding.badgeWifiStatus.setTextColor(primaryColor)
            binding.textWifiDesc.text = getString(R.string.lan_sharing_wifi_desc)
        } else {
            binding.textWifiIp.text = getString(R.string.lan_sharing_not_connected)
            binding.badgeWifiStatus.text = getString(R.string.lan_sharing_wifi_not_connected)
            binding.badgeWifiStatus.setTextColor(secondaryColor)
            binding.textWifiDesc.text = getString(R.string.lan_sharing_wifi_hint_off)
        }
        binding.textWifiPort.text = port.toString()

        updateUIState()
    }

    private fun updateUIState() {
        val enabled = DataStore.allowAccess
        val port = DataStore.mixedPort
        val hasAuth = DataStore.mixedUsername.isNotBlank()
        val isStarted = DataStore.serviceState.started
        val isConnecting = DataStore.serviceState == BaseService.State.Connecting || isRestarting

        binding.switchLanSharing.isChecked = enabled

        val primary = getColorAttr(R.attr.colorPrimary)
        val secondary = getColorAttr(android.R.attr.textColorSecondary)

        if (isConnecting) {
            binding.textHeroTitle.text = getString(R.string.lan_sharing_service_restarting)
            binding.textHeroTitle.setTextColor(primary)
            binding.badgeServiceStatus.text = getString(R.string.connecting)
            binding.badgeServiceStatus.setTextColor(primary)
            binding.imgHeroStatus.setColorFilter(primary)
        } else if (enabled) {
            binding.textHeroTitle.text = getString(R.string.lan_sharing_active)
            binding.textHeroTitle.setTextColor(primary)
            binding.badgeServiceStatus.text = if (isStarted) getString(R.string.lan_sharing_status_running) else getString(R.string.lan_sharing_status_stopped)
            binding.badgeServiceStatus.setTextColor(if (isStarted) primary else secondary)
            binding.imgHeroStatus.setColorFilter(primary)
        } else {
            binding.textHeroTitle.text = getString(R.string.lan_sharing_inactive)
            binding.textHeroTitle.setTextColor(secondary)
            binding.badgeServiceStatus.text = getString(R.string.lan_sharing_status_stopped)
            binding.badgeServiceStatus.setTextColor(secondary)
            binding.imgHeroStatus.setColorFilter(secondary)
        }

        val authSummary = if (hasAuth) getString(R.string.lan_sharing_auth_enabled) else getString(R.string.lan_sharing_auth_none)
        binding.textHeroSubtitle.text = getString(R.string.lan_sharing_status_sub, port, authSummary)

        // 认证行
        binding.textAuthStatus.text = if (hasAuth) {
            "${DataStore.mixedUsername} : ••••••"
        } else {
            getString(R.string.lan_sharing_auth_none)
        }
        binding.btnAuthAction.text = if (hasAuth) getString(R.string.action_edit) else getString(R.string.settings)
    }

    // --- 活跃代理设备监控核心逻辑 ---
    private fun isLocalAddress(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "::1" || ip.equals("localhost", ignoreCase = true)) return true
        if (ip.startsWith("172.19.0.")) return true // VpnService 私有隧道地址
        if (!detectedWifiIp.isNullOrBlank() && ip == detectedWifiIp) return true
        if (!detectedHotspotIp.isNullOrBlank() && ip == detectedHotspotIp) return true
        return false
    }

    private fun isHotspotClientIp(ip: String): Boolean {
        val hotspot = detectedHotspotIp
        if (!hotspot.isNullOrBlank()) {
            val prefix = hotspot.substringBeforeLast('.') + "."
            if (ip.startsWith(prefix)) return true
        }
        return isHotspotIpPattern(ip)
    }

    private fun fetchClientsFromArp(): Set<String> {
        val ips = mutableSetOf<String>()
        runCatching {
            val file = File("/proc/net/arp")
            if (file.exists() && file.canRead()) {
                file.forEachLine { line ->
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.size >= 4 && tokens[0] != "IP" && !tokens[0].startsWith("127.")) {
                        val ip = tokens[0]
                        val mac = tokens[3]
                        if (mac != "00:00:00:00:00:00" && !isLocalAddress(ip)) {
                            ips.add(ip)
                        }
                    }
                }
            }
        }
        return ips
    }

    private fun fetchActiveClientsFromClash(): Map<String, ClientDevice> {
        val result = mutableMapOf<String, ClientDevice>()
        try {
            val req = Request.Builder().url("http://127.0.0.1:9090/connections").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                val bodyStr = resp.body?.string() ?: return emptyMap()
                val root = JSONObject(bodyStr)
                val connArray = root.optJSONArray("connections") ?: return emptyMap()
                val now = System.currentTimeMillis()

                for (i in 0 until connArray.length()) {
                    val c = connArray.getJSONObject(i)
                    val meta = c.optJSONObject("metadata") ?: continue
                    val sourceIP = meta.optString("sourceIP")
                    if (sourceIP.isBlank() || isLocalAddress(sourceIP)) continue

                    val host = meta.optString("destinationHost").takeIf { it.isNotBlank() }
                        ?: meta.optString("destinationIP")
                    val port = meta.optString("destinationPort")
                    val fullHost = if (port.isNotBlank()) "$host:$port" else host
                    val up = c.optLong("upload", 0L)
                    val down = c.optLong("download", 0L)

                    val device = result.getOrPut(sourceIP) {
                        ClientDevice(
                            ip = sourceIP,
                            activeConnections = 0,
                            uploadBytes = 0L,
                            downloadBytes = 0L,
                            lastHost = fullHost,
                            lastSeenTime = now,
                            isHotspot = isHotspotClientIp(sourceIP)
                        )
                    }
                    device.activeConnections += 1
                    device.uploadBytes += up
                    device.downloadBytes += down
                    if (fullHost.isNotBlank()) {
                        device.lastHost = fullHost
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun pollClientDevices() {
        if (!DataStore.serviceState.connected) {
            binding.badgeDevicesCount.text = getString(R.string.lan_sharing_no_devices)
            binding.layoutNoDevices.visibility = View.VISIBLE
            binding.layoutDevicesList.visibility = View.GONE
            binding.textNoDevicesHint.text = getString(R.string.lan_sharing_service_not_connected)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val freshClients = fetchActiveClientsFromClash()
            val arpClients = fetchClientsFromArp()

            withContext(Dispatchers.Main) {
                renderClientDevices(freshClients, arpClients)
            }
        }
    }

    private fun renderClientDevices(freshClients: Map<String, ClientDevice>, arpClients: Set<String>) {
        val now = System.currentTimeMillis()

        // 1. 将活跃连接并入历史缓存
        for ((ip, client) in freshClients) {
            val exist = clientHistoryMap[ip]
            if (exist != null) {
                exist.activeConnections = client.activeConnections
                exist.uploadBytes = maxOf(exist.uploadBytes, client.uploadBytes)
                exist.downloadBytes = maxOf(exist.downloadBytes, client.downloadBytes)
                if (client.lastHost.isNotBlank()) exist.lastHost = client.lastHost
                exist.lastSeenTime = now
            } else {
                clientHistoryMap[ip] = client
            }
        }

        // 2. 将在 ARP 表中发现的热点局域网客户端并入
        for (ip in arpClients) {
            if (!clientHistoryMap.containsKey(ip)) {
                clientHistoryMap[ip] = ClientDevice(
                    ip = ip,
                    activeConnections = 0,
                    uploadBytes = 0L,
                    downloadBytes = 0L,
                    lastHost = "",
                    lastSeenTime = now,
                    isHotspot = isHotspotClientIp(ip)
                )
            }
        }

        // 3. 对之前活跃但当前 0 连接的设备，更新活跃状态
        for ((ip, client) in clientHistoryMap) {
            if (!freshClients.containsKey(ip)) {
                client.activeConnections = 0
            }
        }

        // 4. 清理超过 10 分钟未活跃的旧客户端
        clientHistoryMap.values.removeIf { it.activeConnections == 0 && (now - it.lastSeenTime > 10 * 60 * 1000) }

        val displayList = clientHistoryMap.values.sortedWith(
            compareByDescending<ClientDevice> { it.activeConnections > 0 }
                .thenByDescending { it.lastSeenTime }
        )

        if (displayList.isEmpty()) {
            binding.badgeDevicesCount.text = getString(R.string.lan_sharing_no_devices)
            binding.layoutNoDevices.visibility = View.VISIBLE
            binding.layoutDevicesList.visibility = View.GONE
            binding.textNoDevicesHint.text = getString(R.string.lan_sharing_no_devices_hint)
        } else {
            binding.badgeDevicesCount.text = getString(R.string.lan_sharing_devices_count, displayList.size)
            binding.layoutNoDevices.visibility = View.GONE
            binding.layoutDevicesList.visibility = View.VISIBLE
            binding.layoutDevicesList.removeAllViews()

            for (dev in displayList) {
                val itemView = layoutInflater.inflate(R.layout.item_lan_client_device, binding.layoutDevicesList, false)
                val textIp = itemView.findViewById<TextView>(R.id.text_device_ip)
                val badgeType = itemView.findViewById<TextView>(R.id.badge_device_type)
                val textDetail = itemView.findViewById<TextView>(R.id.text_device_status_detail)
                val textTraffic = itemView.findViewById<TextView>(R.id.text_device_traffic)
                val dotStatus = itemView.findViewById<View>(R.id.dot_device_status)

                textIp.text = dev.ip
                badgeType.text = if (dev.isHotspot) getString(R.string.lan_sharing_device_hotspot) else getString(R.string.lan_sharing_device_wifi)

                if (dev.activeConnections > 0) {
                    dotStatus.setBackgroundResource(R.drawable.bg_status_dot_green)
                    val detail = if (dev.lastHost.isNotBlank()) {
                        getString(R.string.lan_sharing_device_active, dev.activeConnections) + " · " + getString(R.string.lan_sharing_device_recent, dev.lastHost)
                    } else {
                        getString(R.string.lan_sharing_device_active, dev.activeConnections)
                    }
                    textDetail.text = detail
                } else {
                    dotStatus.setBackgroundResource(R.drawable.bg_status_dot_gray)
                    val detail = if (dev.lastHost.isNotBlank()) {
                        getString(R.string.lan_sharing_device_idle) + " · " + getString(R.string.lan_sharing_device_recent, dev.lastHost)
                    } else {
                        getString(R.string.lan_sharing_device_idle)
                    }
                    textDetail.text = detail
                }

                val upStr = Formatter.formatFileSize(this, dev.uploadBytes)
                val downStr = Formatter.formatFileSize(this, dev.downloadBytes)
                textTraffic.text = "▲ $upStr\n▼ $downStr"

                binding.layoutDevicesList.addView(itemView)
            }
        }
    }

    private fun copyToClipboard(text: String, toastMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("IP", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
    }

    private fun showAuthDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }

        val userEdit = EditText(this).apply {
            hint = getString(R.string.username)
            setText(DataStore.mixedUsername)
        }
        val passEdit = EditText(this).apply {
            hint = getString(R.string.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(DataStore.mixedPassword)
        }

        container.addView(userEdit)
        container.addView(passEdit)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lan_sharing_auth_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val user = userEdit.text.toString().trim()
                val pass = passEdit.text.toString().trim()
                DataStore.mixedUsername = user
                DataStore.mixedPassword = pass
                updateUIState()
                restartServiceIfNeeded(R.string.saved)
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                DataStore.mixedUsername = ""
                DataStore.mixedPassword = ""
                updateUIState()
                restartServiceIfNeeded(R.string.saved)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPortDialog() {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(DataStore.mixedPort.toString())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
            addView(editText)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lan_sharing_menu_port)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = editText.text.toString().toIntOrNull()
                if (port != null && port in 1024..65535) {
                    DataStore.mixedPort = port
                    refreshNetworkInfo()
                    restartServiceIfNeeded(R.string.saved)
                } else {
                    Toast.makeText(this, R.string.lan_sharing_invalid_port, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTutorialDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lan_sharing_tutorial_title)
            .setMessage(R.string.lan_sharing_tutorial_content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.lan_sharing_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                refreshNetworkInfo()
                pollClientDevices()
                Toast.makeText(this, R.string.action_refresh, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_tutorial -> {
                showTutorialDialog()
                true
            }
            R.id.action_port -> {
                showPortDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
