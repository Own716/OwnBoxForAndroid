package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.ui.route.RuleSetAutoUpdateDialog
import io.nekohasekai.sagernet.utils.NetworkIPv6Detector
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class CoreSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_core

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<Preference>(Key.SMART_ROUTE_COUNTRY)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.TRAFFIC_SNIFFING)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.BLOCK_QUIC)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.RESOLVE_DESTINATION)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.IPV6_MODE)?.setOnPreferenceChangeListener { pref, newValue ->
            if ((newValue as? String)?.toIntOrNull() == IPv6Mode.ONLY &&
                !NetworkIPv6Detector.inspectDefaultPhysicalNetwork().hasPublicIPv6
            ) {
                Toast.makeText(
                    requireContext(),
                    R.string.ipv6_only_no_public_ipv6_warning,
                    Toast.LENGTH_LONG
                ).show()
            }
            reloadListener.onPreferenceChange(pref, newValue)
        }
        findPreference<Preference>(Key.DUAL_NETWORK_ACCELERATION)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.CONCURRENT_DIAL)?.onPreferenceChangeListener = reloadListener

        val rulesProvider = findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)
        val rulesGeositeUrl = findPreference<EditTextPreference>(Key.RULES_GEOSITE_URL)
        val rulesGeoipUrl = findPreference<EditTextPreference>(Key.RULES_GEOIP_URL)

        fun updateCustomRulesVisibility(provider: Int) {
            rulesGeositeUrl?.isVisible = provider == 4
            rulesGeoipUrl?.isVisible = provider == 4
        }
        updateCustomRulesVisibility(DataStore.rulesProvider)

        rulesProvider?.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            updateCustomRulesVisibility(provider)
            true
        }

        findPreference<Preference>(Key.RULES_UPDATE_INTERVAL)?.apply {
            summary = RuleSets.formatUpdateInterval(requireContext(), DataStore.rulesUpdateInterval)
            setOnPreferenceClickListener {
                RuleSetAutoUpdateDialog.show(requireContext()) { newInterval ->
                    summary = RuleSets.formatUpdateInterval(requireContext(), newInterval)
                }
                true
            }
        }
    }
}
