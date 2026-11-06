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
