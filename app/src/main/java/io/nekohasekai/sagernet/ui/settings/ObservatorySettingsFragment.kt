package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class ObservatorySettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_observatory

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        val speedTestMode = findPreference<SimpleMenuPreference>(Key.SPEED_TEST_MODE)
        speedTestMode?.setOnPreferenceChangeListener { _, newValue ->
            SpeedTestSettings.isValidMode(newValue.toString())
        }

        val speedTestTimeout = findPreference<EditTextPreference>(Key.SPEED_TEST_TIMEOUT_MS)
        speedTestTimeout?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        speedTestTimeout?.setOnPreferenceChangeListener { _, newValue ->
            val valid = SpeedTestSettings.isValidTimeout(newValue.toString())
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_timeout_invalid, Toast.LENGTH_SHORT).show()
            }
            valid
        }

        val simpleDownloadURL = findPreference<EditTextPreference>(Key.SIMPLE_DOWNLOAD_URL)
        simpleDownloadURL?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine()
        }
        simpleDownloadURL?.setOnPreferenceChangeListener { preference, newValue ->
            val value = newValue.toString().trim()
            val valid = SpeedTestSettings.isValidHttpUrl(value)
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_url_invalid, Toast.LENGTH_SHORT).show()
            } else if (value != newValue) {
                (preference as EditTextPreference).text = value
                return@setOnPreferenceChangeListener false
            }
            valid
        }
    }
}
