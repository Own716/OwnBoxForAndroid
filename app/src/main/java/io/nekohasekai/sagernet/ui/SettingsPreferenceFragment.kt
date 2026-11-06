package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.TelegramGroupedPreferenceDecoration

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
        setDivider(null)
        setDividerHeight(0)
        listView.clipToPadding = false
        listView.setPadding(0, dp2px(8), 0, dp2px(120))
        TelegramGroupedPreferenceDecoration.applyTo(listView)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        setPreferencesFromResource(R.xml.global_preferences, rootKey)
        tintPreferenceIcons(preferenceScreen, Theme.getPrimaryColor(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        tintPreferenceIcons(preferenceScreen, Theme.getPrimaryColor(requireContext()))
    }

    private fun tintPreferenceIcons(group: PreferenceGroup, color: Int) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) {
                tintPreferenceIcons(pref, color)
            } else {
                pref.icon?.let { icon ->
                    val tinted = icon.mutate()
                    DrawableCompat.setTint(tinted, color)
                    pref.icon = tinted
                }
            }
        }
    }

    class ListSummaryProvider(
        private val maxLines: Int,
    ) : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val lines = preference.text.orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
            if (lines.isEmpty()) {
                return preference.context.getString(androidx.preference.R.string.not_set)
            }
            return if (lines.size > maxLines) {
                lines.take(maxLines).joinToString("\n", postfix = "\n...")
            } else {
                lines.joinToString("\n")
            }
        }
    }
}
