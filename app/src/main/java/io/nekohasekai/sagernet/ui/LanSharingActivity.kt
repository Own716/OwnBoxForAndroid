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
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityLanSharingBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
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
                SagerNet.reloadService()
                Toast.makeText(
                    this,
                    if (isChecked) R.string.lan_sharing_turn_on else R.string.lan_sharing_turn_off,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.rowSwitchLanSharing.setOnClickListener {
            binding.switchLanSharing.toggle()
        }

        // 复制 Wi-Fi IP (提供纯 IP 复制，方便在目标设备上直接填入“主机名”，不用删端口)
        val copyWifiAction = {
            val ip = detectedWifiIp
            if (!ip.isNullOrBlank()) {
                copyToClipboard(ip, getString(R.string.lan_sharing_copied_ip, ip))
            } else {
                Toast.makeText(this, R.string.lan_sharing_not_detected, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCopyWifiIp.setOnClickListener { copyWifiAction() }
        binding.rowWifiIp.setOnClickListener { copyWifiAction() }

        // 复制热点 IP
        val copyHotspotAction = {
            val ip = detectedHotspotIp ?: "192.168.43.1"
            copyToClipboard(ip, getString(R.string.lan_sharing_copied_ip, ip))
        }
        binding.btnCopyHotspotIp.setOnClickListener { copyHotspotAction() }
        binding.rowHotspotIp.setOnClickListener { copyHotspotAction() }

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

    private fun refreshNetworkInfo() {
        var wifiIp: String? = null
        var hotspotIp: String? = null

        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses.toList().filter { !it.isLoopbackAddress && it is Inet4Address }
                for (addr in addrs) {
                    val host = addr.hostAddress ?: continue
                    when {
                        // 热点或虚拟 AP 常见网卡名字与 IP 前缀
                        name.contains("ap") || name.contains("swlan") || name.contains("rndis") ||
                                host.startsWith("192.168.43.") || host.startsWith("192.168.44.") ||
                                host.startsWith("192.168.49.") || host.startsWith("10.101.") -> {
                            if (hotspotIp == null) hotspotIp = host
                        }
                        // 常见 Wi-Fi 网卡
                        name.contains("wlan") -> {
                            if (wifiIp == null) wifiIp = host
                        }
                        else -> {
                            if (wifiIp == null) wifiIp = host
                        }
                    }
                }
            }
        }

        detectedWifiIp = wifiIp
        detectedHotspotIp = hotspotIp

        val port = DataStore.mixedPort

        // 更新 Wi-Fi IP 展示
        if (!detectedWifiIp.isNullOrBlank()) {
            binding.textWifiIp.text = "${detectedWifiIp}:$port"
        } else {
            binding.textWifiIp.text = getString(R.string.lan_sharing_not_detected)
        }

        // 更新热点 IP 展示 (若未开启热点，提示默认建议 IP)
        if (!detectedHotspotIp.isNullOrBlank()) {
            binding.textHotspotIp.text = "${detectedHotspotIp}:$port"
        } else {
            binding.textHotspotIp.text = "192.168.43.1:$port (${getString(R.string.lan_sharing_not_detected)})"
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
                SagerNet.reloadService()
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                DataStore.mixedUsername = ""
                DataStore.mixedPassword = ""
                updateUIState()
                SagerNet.reloadService()
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
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
                    SagerNet.reloadService()
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
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
