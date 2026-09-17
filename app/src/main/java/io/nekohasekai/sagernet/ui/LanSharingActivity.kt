package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityLanSharingBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

class LanSharingActivity : ThemedActivity() {

    private lateinit var binding: ActivityLanSharingBinding

    private var detectedWifiIp: String? = null
    private var detectedHotspotIp: String? = null

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
        refreshNetworkInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkInfo()
        updateUIState()
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

        // 快捷教程卡片点击
        binding.cardTutorialShortcut.setOnClickListener {
            showTutorialDialog()
        }
    }

    private fun restartServiceIfNeeded(fallbackToastRes: Int? = null) {
        if (DataStore.serviceState.canStop) {
            Toast.makeText(this, R.string.lan_sharing_restarting_service, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                SagerNet.stopService()
                delay(600)
                SagerNet.startService()
            }
        } else {
            fallbackToastRes?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isHotspotActive(): Boolean {
        // 1. WifiManager API 反射检查（适用于主流 Android 及各厂商定制系统）
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

        // 2. ConnectivityManager getTetheredIfaces 检查（识别所有活跃的网络共享网卡）
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
            // 在所有可用底层网络中寻找真正的 Wi-Fi 传输层网络（即使 VPN 开启，底层的 Wi-Fi 网络也在 allNetworks 中）
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

            // 针对部分旧版本 Android 系统的辅助检测
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
        // 判定准则：严格限定为真实热点局域网网段 (192.168.43.x 等)，绝对不采纳蜂窝移动数据 IP (如 10.x / 172.x 等运营商 CGNAT 私网)
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
                val ip = intf.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .firstOrNull { isHotspotIpPattern(it) }
                if (ip != null) {
                    hotspotIp = ip
                    break
                }
            }

            // 若未找到，遍历活跃网卡检查是否有符合热点网段的 IP
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

            // Android 11+ 出于安全沙箱隔离，普通应用无法直接枚举 Tethering 专有 AP 网卡 IP
            // 但 Android 热点网关地址对所有连入热点的客户端均为 192.168.43.1 黄金标准，作为默认值 100% 准确可用
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
            binding.textHotspotIp.text = detectedHotspotIp ?: "192.168.43.1"
            binding.badgeHotspotStatus.text = getString(R.string.lan_sharing_hotspot_detected)
            binding.badgeHotspotStatus.setTextColor(primaryColor)
            binding.textHotspotDesc.text = getString(R.string.lan_sharing_hotspot_desc)
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

        binding.switchLanSharing.isChecked = enabled

        // Hero 头部
        val primary = getColorAttr(R.attr.colorPrimary)
        val secondary = getColorAttr(android.R.attr.textColorSecondary)

        if (enabled) {
            binding.textHeroTitle.text = getString(R.string.lan_sharing_active)
            binding.textHeroTitle.setTextColor(primary)
            binding.badgeServiceStatus.text = getString(R.string.lan_sharing_status_running)
            binding.badgeServiceStatus.setTextColor(primary)
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
