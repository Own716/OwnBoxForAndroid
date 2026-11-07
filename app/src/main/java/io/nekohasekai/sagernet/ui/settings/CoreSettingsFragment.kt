package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.ui.route.RuleSetAutoUpdateDialog
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class CoreSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_core

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<Preference>(Key.SMART_ROUTE_COUNTRY)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.TRAFFIC_SNIFFING)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.RESOLVE_DESTINATION)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.IPV6_MODE)?.onPreferenceChangeListener = reloadListener
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
