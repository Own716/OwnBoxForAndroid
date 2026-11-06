package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import androidx.preference.Preference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R

class FragmentSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_fragment

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<Preference>(Key.ENABLE_TLS_FRAGMENT)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("fragmentLength")?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("fragmentInterval")?.onPreferenceChangeListener = reloadListener
    }
}
