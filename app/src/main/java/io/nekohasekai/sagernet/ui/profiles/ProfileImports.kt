package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.ScannerActivity
import io.nekohasekai.sagernet.ui.profile.BalancerSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.JuicitySettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SnellSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity

class ProfileImports(private val host: ConfigurationFragment) {

    private val importFile = host.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFile(uri)
    }

    fun prepare(menu: Menu) {
        menu.findItem(R.id.action_scan_qr_code)?.isVisible = hasCamera(host.requireContext())
    }

    private fun hasCamera(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> host.startActivity(Intent(host.requireContext(), ScannerActivity::class.java))
            R.id.action_import_clipboard -> importClipboard()
            R.id.action_import_file -> host.startFilesForResult(importFile, "*/*")
            else -> {
                val intent = editorIntent(item.itemId) ?: return false
                host.startActivity(intent)
            }
        }
        return true
    }

    private fun importClipboard() {
        val text = SagerNet.getClipboardText().trim()
        if (text.isBlank()) {
            host.snackbar(R.string.clipboard_empty).show()
            return
        }
        val groupId = host.currentGroupId
        host.launchIo {
            try {
                val rawProxies = RawUpdater.parseRaw(text).orEmpty()
                if (rawProxies.isNotEmpty()) {
                    for (bean in rawProxies) {
                        ProfileManager.createProfile(groupId, bean)
                    }
                    host.onUi {
                        snackbar(resources.getQuantityString(R.plurals.added, rawProxies.size, rawProxies.size)).show()
                    }
                    GroupRepo.postReload(groupId)
                } else {
                    host.onUi { snackbar(R.string.no_proxies_found_in_clipboard).show() }
                }
            } catch (e: Exception) {
                host.onUi { snackbar(e.readableMessage).show() }
            }
        }
    }

    private fun importFile(uri: Uri) {
        val groupId = host.currentGroupId
        val activity = host.requireActivity()
        host.launchIo {
            try {
                val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val rawProxies = RawUpdater.parseRaw(text).orEmpty()
                if (rawProxies.isNotEmpty()) {
                    for (bean in rawProxies) {
                        ProfileManager.createProfile(groupId, bean)
                    }
                    host.onUi {
                        snackbar(resources.getQuantityString(R.plurals.added, rawProxies.size, rawProxies.size)).show()
                    }
                    GroupRepo.postReload(groupId)
                } else {
                    host.onUi { snackbar(R.string.no_proxies_found_in_file).show() }
                }
            } catch (e: Exception) {
                host.onUi { snackbar(e.readableMessage).show() }
            }
        }
    }

    private fun editorIntent(itemId: Int): Intent? {
        val ctx = host.requireContext()
        return when (itemId) {
            R.id.action_new_socks -> Intent(ctx, SocksSettingsActivity::class.java)
            R.id.action_new_http -> Intent(ctx, HttpSettingsActivity::class.java)
            R.id.action_new_ss -> Intent(ctx, ShadowsocksSettingsActivity::class.java)
            R.id.action_new_vmess -> Intent(ctx, VMessSettingsActivity::class.java)
            R.id.action_new_vless -> Intent(ctx, VMessSettingsActivity::class.java).apply { putExtra("vless", true) }
            R.id.action_new_trojan -> Intent(ctx, TrojanSettingsActivity::class.java)
            R.id.action_new_mieru -> Intent(ctx, MieruSettingsActivity::class.java)
            R.id.action_new_naive -> Intent(ctx, NaiveSettingsActivity::class.java)
            R.id.action_new_hysteria -> Intent(ctx, HysteriaSettingsActivity::class.java)
            R.id.action_new_tuic -> Intent(ctx, TuicSettingsActivity::class.java)
            R.id.action_new_juicity -> Intent(ctx, JuicitySettingsActivity::class.java)
            R.id.action_new_ssh -> Intent(ctx, SSHSettingsActivity::class.java)
            R.id.action_new_snell -> Intent(ctx, SnellSettingsActivity::class.java)
            R.id.action_new_wg -> Intent(ctx, WireGuardSettingsActivity::class.java)
            R.id.action_new_chain -> Intent(ctx, ChainSettingsActivity::class.java)
            R.id.action_new_auto_selector -> Intent(ctx, BalancerSettingsActivity::class.java)
            else -> null
        }
    }
}
