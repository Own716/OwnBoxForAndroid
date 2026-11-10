package io.nekohasekai.sagernet.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.AppManagerActivity
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class VpnSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_vpn

    private var proxyApps: SwitchPreferenceCompat? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        proxyApps = findPreference(Key.PROXY_APPS)
        proxyApps?.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }

        if (Build.VERSION.SDK_INT < 28) {
            findPreference<Preference>(Key.METERED_NETWORK)?.isVisible = false
        }

        findPreference<Preference>(Key.STRICT_ROUTE)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.BYPASS_LAN)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.BYPASS_LAN_IN_CORE)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("block_quic")?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.MTU)?.onPreferenceChangeListener = restartListener

        findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)?.apply {
            summaryProvider = null
            value = io.nekohasekai.sagernet.TunImplementation.SING_TUN.toString()
            isEnabled = false
            summary = "Sing-Tun (官方最新原生协议栈)"
        }
    }

    override fun onResume() {
        super.onResume()
        proxyApps?.isChecked = DataStore.proxyApps
    }
}
