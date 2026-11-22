package io.nekohasekai.sagernet.ui.settings

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ui.LanSharingActivity
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment.ListSummaryProvider
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class ModeSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_mode

    private lateinit var mixedPort: EditTextPreference
    private lateinit var disableMixedInbound: SwitchPreferenceCompat
    private lateinit var mixedAuthConfig: Preference
    private lateinit var httpProxyBypass: EditTextPreference
    private var lanSharingPref: Preference? = null
    @Volatile
    private var cachedRootAvailable: Boolean = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        val serviceMode = findPreference<SimpleMenuPreference>(Key.SERVICE_MODE)
        serviceMode?.setOnPreferenceChangeListener { _, newValue ->
            val oldMode = DataStore.serviceMode
            val wasStarted = DataStore.serviceState.started
            if (wasStarted) SagerNet.stopService()
            val nextMode = newValue as? String ?: oldMode
            updateRootTunPrefsVisibility(nextMode)
            if (Key.isRootMode(oldMode) && !Key.isRootMode(nextMode)) {
                val appCtx = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    if (wasStarted) kotlinx.coroutines.delay(350)
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.nekohasekai.sagernet.bg.root.RootTunManager.stop(appCtx)
                    }
                    if (isAdded) {
                        updateRootTunPrefsVisibility(nextMode)
                        if (result.isSuccess) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                R.string.root_tun_switching_cleanup_done,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            } else if (Key.isRootMode(nextMode)) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val hasRoot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.nekohasekai.sagernet.bg.root.RootShell.isRootAvailable()
                    }
                    cachedRootAvailable = hasRoot
                    if (isAdded) {
                        updateRootTunPrefsVisibility(nextMode)
                        if (!hasRoot) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                R.string.root_tun_no_root_hint,
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            true
        }

        mixedPort = findPreference(Key.MIXED_PORT)!!
        disableMixedInbound = findPreference(Key.DISABLE_MIXED_INBOUND)!!
        mixedAuthConfig = findPreference(Key.MIXED_AUTH_CONFIG)!!
        httpProxyBypass = findPreference(Key.HTTP_PROXY_BYPASS)!!
        lanSharingPref = findPreference("lanSharing")

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        httpProxyBypass.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        httpProxyBypass.summaryProvider = ListSummaryProvider(maxLines = 1)

        mixedPort.onPreferenceChangeListener = reloadListener
        httpProxyBypass.onPreferenceChangeListener = reloadListener

        fun updateMixedPortState(disabled: Boolean = DataStore.disableMixedInbound) {
            mixedPort.isEnabled = !disabled
            mixedAuthConfig.isEnabled = !disabled
            httpProxyBypass.isEnabled = !disabled
            if (disabled) {
                mixedPort.summaryProvider = null
                mixedPort.summary = getString(R.string.mixed_inbound_disabled)
            } else {
                mixedPort.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
        }
        updateMixedPortState()

        disableMixedInbound.setOnPreferenceChangeListener { _, newValue ->
            val disabled = newValue as Boolean
            if (disabled && DataStore.serviceMode == Key.MODE_PROXY) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.disable_mixed_inbound_proxy_toast, DataStore.mixedPort),
                    Toast.LENGTH_LONG
                ).show()
            }
            updateMixedPortState(disabled)
            reloadListener.onPreferenceChange(disableMixedInbound, newValue)
        }

        fun updateMixedAuthSummary() {
            mixedAuthConfig.summary = DataStore.mixedUsername.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.mixed_auth_enabled_sum, it) }
                ?: getString(R.string.mixed_auth_no_auth)
        }
        updateMixedAuthSummary()

        mixedAuthConfig.setOnPreferenceClickListener {
            val view = layoutInflater.inflate(R.layout.layout_mixed_auth_dialog, null)
            val usernameEdit = view.findViewById<EditText>(R.id.mixed_username_edit)
            val passwordEdit = view.findViewById<EditText>(R.id.mixed_password_edit)
            usernameEdit.setText(DataStore.mixedUsername)
            passwordEdit.setText(DataStore.mixedPassword)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mixed_auth_config)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.mixedUsername = usernameEdit.text.toString().trim()
                    DataStore.mixedPassword = passwordEdit.text.toString()
                    updateMixedAuthSummary()
                    reloadListener.onPreferenceChange(mixedAuthConfig, "")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        lanSharingPref?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LanSharingActivity::class.java))
            true
        }

        findPreference<androidx.preference.SwitchPreference>(Key.ROOT_LAN_SHARING)
            ?.setOnPreferenceChangeListener(reloadListener)

        findPreference<Preference>(Key.ROOT_TUN_CLEANUP)?.setOnPreferenceClickListener { pref ->
            pref.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.nekohasekai.sagernet.bg.root.RootTunManager.stop(requireContext())
                }
                if (isAdded) {
                    pref.isEnabled = true
                    android.widget.Toast.makeText(
                        requireContext(),
                        if (result.isSuccess) R.string.root_tun_cleanup_done else R.string.root_tun_cleanup_failed,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            true
        }
        updateRootTunPrefsVisibility()
        viewLifecycleOwner.lifecycleScope.launch {
            val hasRoot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.nekohasekai.sagernet.bg.root.RootShell.isRootAvailable()
            }
            cachedRootAvailable = hasRoot
            if (isAdded) {
                updateRootTunPrefsVisibility()
            }
        }
    }

    private fun updateRootTunPrefsVisibility(mode: String = DataStore.serviceMode) {
        val showLanSwitch = mode == Key.MODE_ROOT_TUN ||
            mode == Key.MODE_ROOT_REDIRECT ||
            mode == Key.MODE_ROOT_MIXED
        val showCleanup = Key.isRootMode(mode) ||
            cachedRootAvailable ||
            context?.let { io.nekohasekai.sagernet.bg.root.RootTunManager.hasActiveState(it) } == true
        findPreference<androidx.preference.SwitchPreference>(Key.ROOT_LAN_SHARING)?.isVisible = showLanSwitch
        findPreference<Preference>(Key.ROOT_TUN_CLEANUP)?.isVisible = showCleanup
    }

    private fun getLocalIps(): String {
        val ips = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { !it.isLoopback && it.isUp }
                ?.flatMap { it.inetAddresses.toList() }
                ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.mapNotNull { it.hostAddress }
                ?.distinct()
        }.getOrNull() ?: emptyList()
        return when {
            ips.isEmpty() -> "192.168.43.1"
            ips.any { it.startsWith("192.168.43.") } -> ips.first { it.startsWith("192.168.43.") }
            else -> ips.joinToString(" / ")
        }
    }

    private fun updateLanSharingSummary() {
        if (lanSharingPref == null) return
        val enabled = DataStore.allowAccess
        if (enabled) {
            val localIp = getLocalIps()
            val port = DataStore.mixedPort
            lanSharingPref?.summary = getString(R.string.lan_sharing_enabled_sum, localIp, port)
        } else {
            lanSharingPref?.summary = getString(R.string.lan_sharing_disabled_sum)
        }
    }

    override fun onResume() {
        super.onResume()
        updateLanSharingSummary()
        updateRootTunPrefsVisibility()
    }
}
