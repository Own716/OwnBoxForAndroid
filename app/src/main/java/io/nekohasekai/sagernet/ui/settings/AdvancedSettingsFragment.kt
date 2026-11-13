package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.SmartAutoStopMatcher
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.triggerFullRestart
import io.nekohasekai.sagernet.ui.MainActivity
import moe.matsuri.nb4a.ui.EditConfigPreference
import moe.matsuri.nb4a.ui.LongClickListPreference
import java.io.File

class AdvancedSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_advanced

    private var globalCustomConfig: EditConfigPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<SwitchPreferenceCompat>(Key.ENABLE_CLASH_API)?.setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.refreshNavMenu(newValue as Boolean)
            reloadListener.onPreferenceChange(findPreference(Key.ENABLE_CLASH_API)!!, newValue)
        }

        findPreference<Preference>(Key.NETWORK_CHANGE_RESET_CONNECTIONS)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.WAKE_RESET_CONNECTIONS)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.GLOBAL_ALLOW_INSECURE)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.ALLOW_INSECURE_ON_REQUEST)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.APP_TLS_VERSION)?.onPreferenceChangeListener = reloadListener

        findPreference<SwitchPreferenceCompat>(Key.PERFORMANCE_PRIORITY_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            SagerNet.updatePerformancePriorityMode(enabled)
            true
        }

        findPreference<Preference>(Key.ACQUIRE_WAKE_LOCK)?.onPreferenceChangeListener = reloadListener

        val smartAutoStopPref = findPreference<SwitchPreferenceCompat>(Key.SMART_AUTO_STOP)
        val smartAutoStopNetworksPref = findPreference<Preference>(Key.SMART_AUTO_STOP_NETWORKS)
        fun updateSmartAutoStopSummary() {
            val rules = DataStore.smartAutoStopNetworks.trim()
            smartAutoStopNetworksPref?.summary = if (rules.isEmpty()) {
                getString(R.string.smart_auto_stop_networks_summary_empty)
            } else {
                rules
            }
        }
        updateSmartAutoStopSummary()

        smartAutoStopPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            DataStore.smartAutoStop = enabled
            if (DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Stopped &&
                DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Idle
            ) {
                SagerNet.refreshSmartAutoStop()
            }
            true
        }

        smartAutoStopNetworksPref?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            val input = EditText(ctx).apply {
                setText(DataStore.smartAutoStopNetworks)
                hint = "192.168.1.0/24, gateway:192.168.10.1"
                setSingleLine(false)
                maxLines = 4
            }
            val container = android.widget.FrameLayout(ctx).apply {
                val padH = (24 * resources.displayMetrics.density).toInt()
                val padV = (8 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, 0)
                addView(input)
            }
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.smart_auto_stop_networks)
                .setMessage(R.string.smart_auto_stop_networks_hint)
                .setView(container)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val raw = input.text?.toString().orEmpty()
                    when (val result = SmartAutoStopMatcher.validateRules(raw)) {
                        is SmartAutoStopMatcher.ValidationResult.Valid -> {
                            DataStore.smartAutoStopNetworks = result.rules.joinToString(",")
                            updateSmartAutoStopSummary()
                            if (DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Stopped &&
                                DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Idle
                            ) {
                                SagerNet.refreshSmartAutoStop()
                            }
                            dialog.dismiss()
                        }
                        is SmartAutoStopMatcher.ValidationResult.TooManyRules -> {
                            Toast.makeText(
                                ctx,
                                getString(R.string.smart_auto_stop_too_many, result.max),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is SmartAutoStopMatcher.ValidationResult.InvalidRule -> {
                            Toast.makeText(
                                ctx,
                                getString(R.string.smart_auto_stop_invalid_rule, result.rule),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            dialog.show()
            true
        }

        findPreference<SwitchPreferenceCompat>(Key.HIDE_FROM_RECENT_APPS)?.setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            true
        }

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)
        logLevel?.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel?.onPreferenceChangeListener = restartListener
        logLevel?.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true

            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = 50
                setText(size.toString())
            }

            MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toInt()
                    if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                    restartListener.onPreferenceChange(logLevel, DataStore.logLevel)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)
        globalCustomConfig?.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        findPreference<Preference>(Key.CLEAR_CACHE)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.clear_cache)
                setMessage(R.string.clear_cache_confirm)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    clearAppCache()
                }
                setNegativeButton(android.R.string.cancel, null)
            }.show()
            true
        }

        findPreference<Preference>("resetSettings")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.confirm)
                setMessage(R.string.reset_settings_message)
                setNegativeButton(R.string.no, null)
                setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
            }.show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        globalCustomConfig?.notifyChanged()
    }

    private fun clearAppCache() {
        try {
            val cacheDir = SagerNet.application.cacheDir
            clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))

            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }

            Toast.makeText(requireContext(), R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.clear_cache_failed, e.message), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true

            for (child in children) {
                val childFile = File(dir, child)

                if (child == "neko.log") {
                    try {
                        childFile.writeText("")
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (child in skipFiles) {
                    continue
                }

                if (childFile.isDirectory) {
                    clearDirFiles(childFile, skipFiles)
                } else {
                    childFile.delete()
                }
            }

            return true
        }
        return false
    }
}
