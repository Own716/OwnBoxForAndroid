package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment.ListSummaryProvider

class DnsSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_dns

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<Preference>(Key.REMOTE_DNS)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.DIRECT_DNS)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.ENABLE_DNS_ROUTING)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.ENABLE_FAKEDNS)?.onPreferenceChangeListener = reloadListener

        val dnsHosts = findPreference<EditTextPreference>(Key.DNS_HOSTS)
        dnsHosts?.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        dnsHosts?.summaryProvider = ListSummaryProvider(maxLines = 1)
        dnsHosts?.onPreferenceChangeListener = reloadListener
    }
}
