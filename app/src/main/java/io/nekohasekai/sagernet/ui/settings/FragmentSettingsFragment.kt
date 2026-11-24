package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R

class FragmentSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_fragment

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<Preference>(Key.ENABLE_TLS_FRAGMENT)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("fragmentLength")?.onPreferenceChangeListener = reloadListener
        findPreference<EditTextPreference>("fragmentInterval")?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { pref, newValue ->
                val str = newValue as? String ?: return@OnPreferenceChangeListener true
                val num = str.split("-").firstOrNull()?.trim()?.toLongOrNull()
                if (num == null || num < 1 || num > 1000) {
                    Toast.makeText(
                        requireContext(),
                        R.string.fragment_interval_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                } else {
                    reloadListener.onPreferenceChange(pref, newValue)
                }
            }
    }
}
