package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
                Toast.makeText(this, R.string.lan_sharing_not_detected, Toast.LENGTH_SHORT).show()
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

    private fun refreshNetworkInfo() {
        var wifiIp: String? = null
        var hotspotIp: String? = null

        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()

                // 彻底排除蜂窝移动数据（rmnet/ccmni/pdp/wwan等）与虚拟网卡，杜绝运营商内网 IP 混淆
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                    name.startsWith("wwan") || name.startsWith("dummy") || name.startsWith("tun") ||
                    name.startsWith("sit") || name.startsWith("ip6") || name.startsWith("clat")
                ) {
                    continue
                }

                val addrs = intf.inetAddresses.toList().filter { !it.isLoopbackAddress && it is Inet4Address }
                for (addr in addrs) {
                    val host = addr.hostAddress ?: continue
                    when {
                        // 热点或虚拟 AP 专用网卡及热点常用私有网段 (192.168.43.x / 44.x / 49.x / 50.x)
                        name.contains("ap") || name.contains("softap") || name.contains("swlan") ||
                            name.contains("rndis") || name.contains("wigig") || name.contains("tether") ||
                            host.startsWith("192.168.43.") || host.startsWith("192.168.44.") ||
                            host.startsWith("192.168.49.") || host.startsWith("192.168.50.") -> {
                            if (hotspotIp == null) hotspotIp = host
                        }
                        // 正常 Wi-Fi 无线网卡 (连接家庭/公司路由器的网卡)
                        name.contains("wlan") -> {
                            if (wifiIp == null) wifiIp = host
                        }
                        else -> {
                            if (wifiIp == null && !host.startsWith("127.")) {
                                wifiIp = host
                            }
                        }
                    }
                }
            }
        }

        detectedWifiIp = wifiIp
        detectedHotspotIp = hotspotIp
        val port = DataStore.mixedPort

        // 更新热点 IP 展示 (若未开启热点，显示标准建议 IP 192.168.43.1 并注明状态)
        if (!detectedHotspotIp.isNullOrBlank()) {
            binding.textHotspotIp.text = detectedHotspotIp
            binding.textHotspotPort.text = port.toString()
            binding.textHotspotStatusDesc.text = getString(R.string.lan_sharing_hotspot_detected)
        } else {
            binding.textHotspotIp.text = "192.168.43.1"
            binding.textHotspotPort.text = port.toString()
            binding.textHotspotStatusDesc.text = getString(R.string.lan_sharing_hotspot_not_active)
        }

        // 更新 Wi-Fi IP 展示
        if (!detectedWifiIp.isNullOrBlank()) {
            binding.textWifiIp.text = detectedWifiIp
            binding.textWifiPort.text = port.toString()
            binding.textWifiStatusDesc.text = getString(R.string.lan_sharing_wifi_detected)
        } else {
            binding.textWifiIp.text = getString(R.string.lan_sharing_not_detected)
            binding.textWifiPort.text = port.toString()
            binding.textWifiStatusDesc.text = getString(R.string.lan_sharing_wifi_not_connected)
        }

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
