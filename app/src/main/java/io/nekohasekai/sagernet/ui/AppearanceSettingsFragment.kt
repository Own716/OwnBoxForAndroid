package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.LiveUpdateFormatter
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.ColorPickerPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class AppearanceSettingsFragment : PreferenceFragmentCompat() {

    private lateinit var notificationPermission: Preference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
        setDivider(null)
        setDividerHeight(0)
        listView.clipToPadding = false
        listView.setPadding(0, dp2px(8), 0, dp2px(88))
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        setPreferencesFromResource(R.xml.settings_appearance, rootKey)

        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)
        appTheme?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.appTheme = (newValue as Number).toInt()
            Theme.applyNightTheme()
            activity?.recreate()
            true
        }

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)
        nightTheme?.setOnPreferenceChangeListener { _, newTheme ->
            val mode = (newTheme as String).toInt()
            DataStore.nightTheme = mode
            Theme.currentNightMode = mode
            Theme.applyNightTheme()
            activity?.recreate()
            true
        }

        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)
        appLanguage?.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }

        val liveUpdateEnabled = findPreference<SwitchPreference>(Key.LIVE_UPDATE_ENABLED)
        val liveUpdateShowCapsule = findPreference<SwitchPreference>(Key.LIVE_UPDATE_SHOW_CAPSULE)
        val liveUpdatePreset = findPreference<SimpleMenuPreference>(Key.LIVE_UPDATE_PRESET)
        val liveUpdateCollapsed = findPreference<EditTextPreference>(Key.LIVE_UPDATE_COLLAPSED_FORMAT)
        val liveUpdateTitle = findPreference<EditTextPreference>(Key.LIVE_UPDATE_TITLE_FORMAT)
        val liveUpdateContent = findPreference<EditTextPreference>(Key.LIVE_UPDATE_CONTENT_FORMAT)
        val liveUpdateSubtext = findPreference<EditTextPreference>(Key.LIVE_UPDATE_SUBTEXT_FORMAT)
        val liveUpdatePreview = findPreference<Preference>("liveUpdatePreview")

        fun notifyNotificationUpdate() {
            if (DataStore.serviceState.started) {
                val intent = Intent(Action.REFRESH_NOTIFICATION)
                intent.setPackage(requireContext().packageName)
                requireContext().sendBroadcast(intent)
            }
        }

        fun updateLiveUpdatePreview() {
            if (liveUpdatePreview == null) return
            val mockInfo = LiveUpdateFormatter.getMockInfo(requireContext())
            val tpls = LiveUpdateFormatter.getTemplates()
            val renderedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, mockInfo)
            val renderedTitle = LiveUpdateFormatter.format(tpls.title, mockInfo)
            val renderedContent = LiveUpdateFormatter.format(tpls.content, mockInfo)
            val renderedSub = LiveUpdateFormatter.format(tpls.subText, mockInfo)
            liveUpdatePreview.summary = if (renderedSub.isNotEmpty()) {
                getString(
                    R.string.live_update_preview_summary_format,
                    renderedCollapsed,
                    renderedTitle,
                    renderedContent,
                    renderedSub
                )
            } else {
                getString(
                    R.string.live_update_preview_summary_format_no_sub,
                    renderedCollapsed,
                    renderedTitle,
                    renderedContent
                )
            }
        }

        liveUpdateEnabled?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.liveUpdateEnabled = newValue as Boolean
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            needReload()
            true
        }

        liveUpdateShowCapsule?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.liveUpdateShowCapsule = newValue as Boolean
            view?.post {
                notifyNotificationUpdate()
            }
            true
        }

        updateLiveUpdatePreview()

        liveUpdatePreset?.setOnPreferenceChangeListener { _, newValue ->
            val newPreset = newValue as String
            if (newPreset != LiveUpdateFormatter.PRESET_CUSTOM) {
                val tpls = LiveUpdateFormatter.getPresetTemplates(newPreset)
                DataStore.liveUpdateCollapsedFormat = tpls.collapsed
                DataStore.liveUpdateTitleFormat = tpls.title
                DataStore.liveUpdateContentFormat = tpls.content
                DataStore.liveUpdateSubtextFormat = tpls.subText

                liveUpdateCollapsed?.text = tpls.collapsed
                liveUpdateTitle?.text = tpls.title
                liveUpdateContent?.text = tpls.content
                liveUpdateSubtext?.text = tpls.subText
            }
            DataStore.liveUpdatePreset = newPreset
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            needReload()
            true
        }

        fun bindFormatField(pref: EditTextPreference?, onSave: (String) -> Unit) {
            pref?.setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as? String).orEmpty().trim().replace('｛', '{').replace('｝', '}')
                onSave(str)
                pref.text = str
                DataStore.liveUpdatePreset = LiveUpdateFormatter.PRESET_CUSTOM
                liveUpdatePreset?.value = LiveUpdateFormatter.PRESET_CUSTOM
                view?.post {
                    updateLiveUpdatePreview()
                    notifyNotificationUpdate()
                }
                needReload()
                true
            }
        }

        bindFormatField(liveUpdateCollapsed) { DataStore.liveUpdateCollapsedFormat = it }
        bindFormatField(liveUpdateTitle) { DataStore.liveUpdateTitleFormat = it }
        bindFormatField(liveUpdateContent) { DataStore.liveUpdateContentFormat = it }
        bindFormatField(liveUpdateSubtext) { DataStore.liveUpdateSubtextFormat = it }

        liveUpdatePreview?.setOnPreferenceClickListener {
            val mockInfo = LiveUpdateFormatter.getMockInfo(requireContext())
            val tpls = LiveUpdateFormatter.getTemplates()
            val renderedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, mockInfo)
            val renderedTitle = LiveUpdateFormatter.format(tpls.title, mockInfo)
            val renderedContent = LiveUpdateFormatter.format(tpls.content, mockInfo)
            val renderedSub = LiveUpdateFormatter.format(tpls.subText, mockInfo).ifEmpty {
                getString(androidx.preference.R.string.not_set)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.live_update_preview_dialog_title)
                .setMessage(
                    getString(
                        R.string.live_update_preview_dialog_message,
                        renderedCollapsed,
                        renderedTitle,
                        renderedContent,
                        renderedSub
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<SwitchPreference>("showDirectSpeed")?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.showDirectSpeed = newValue as Boolean
            needReload()
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            true
        }

        findPreference<SwitchPreference>("showGroupInNotification")?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.showGroupInNotification = newValue as Boolean
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            true
        }

        findPreference<MultiSelectListPreference>(Key.NOTIFICATION_ACTIONS)?.apply {
            summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { p ->
                val chosen = p.values
                p.entryValues.indices.filter { p.entryValues[it].toString() in chosen }
                    .joinToString(", ") { p.entries[it] }
                    .ifEmpty { getString(androidx.preference.R.string.not_set) }
            }
            setOnPreferenceChangeListener { _, newValue ->
                val set = (newValue as Set<*>).map { it.toString() }.toMutableSet()
                if (set.size > 3) {
                    Toast.makeText(requireContext(), R.string.notification_actions_too_many, Toast.LENGTH_SHORT).show()
                    false
                } else {
                    DataStore.configurationStore.putStringSet(Key.NOTIFICATION_ACTIONS, set)
                    view?.post { notifyNotificationUpdate() }
                    true
                }
            }
        }

        notificationPermission = findPreference("notificationPermission")!!
        notificationPermission.setOnPreferenceClickListener {
            openNotificationSettings()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (::notificationPermission.isInitialized) {
            notificationPermission.setSummary(
                if (NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
                    R.string.notification_permission_allowed
                } else {
                    R.string.notification_permission_blocked
                }
            )
        }
    }

    private fun openNotificationSettings() {
        val context = requireContext()
        val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            app
        }
        for (candidate in listOf(intent, app)) {
            try {
                startActivity(candidate)
                return
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val ARG_TITLE = "title"
    }
}
