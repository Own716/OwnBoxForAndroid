package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.needRestart
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.TelegramGroupedPreferenceDecoration

abstract class BaseSettingsPreferenceFragment : PreferenceFragmentCompat() {

    abstract val preferenceResId: Int

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
        setPreferencesFromResource(preferenceResId, rootKey)
        tintPreferenceIcons(preferenceScreen, Theme.getPrimaryColor(requireContext()))
    }

    protected fun tintPreferenceIcons(group: PreferenceGroup, color: Int) {
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

    protected val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    protected val restartListener = Preference.OnPreferenceChangeListener { _, _ ->
        needRestart()
        true
    }
}
