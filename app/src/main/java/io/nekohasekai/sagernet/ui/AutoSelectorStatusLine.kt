package io.nekohasekai.sagernet.ui

import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The profiles screen's status line: what the running proxy / auto-selector
 * does. A tap opens [AutoSelectorStatusActivity].
 */
object AutoSelectorStatusLine {

    private const val TICK_MS = 15_000L

    suspend fun follow(fragment: ConfigurationFragment) {
        if (fragment.select) return
        val selection = MutableStateFlow(DataStore.selectedProxy)
        val listener = object : OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
                if (key == Key.PROFILE_ID || key == Key.PROFILE_CURRENT) {
                    selection.value = DataStore.selectedProxy
                }
            }
        }
        DataStore.configurationStore.registerChangeListener(listener)
        try {
            while (true) {
                val context = fragment.context
                val profileId = selection.value.takeIf { it > 0 } ?: DataStore.currentProfile
                if (context != null && profileId > 0) {
                    val profile = withContext(Dispatchers.IO) { ProfileManager.getProfile(profileId) }
                    if (profile != null) {
                        val latency = profile.status
                        val latencyStr = if (latency > 0) "${latency} ms" else if (latency < 0) "不可用" else "未测试"
                        val text = "当前配置：${profile.displayName()} · $latencyStr"
                        fragment.setRuntimeStatus(text) {
                            fragment.context?.let { ctx ->
                                fragment.startActivity(AutoSelectorStatusActivity.intent(ctx, profileId))
                            }
                        }
                    } else {
                        fragment.setRuntimeStatus(null)
                    }
                } else {
                    fragment.setRuntimeStatus(null)
                }
                delay(TICK_MS)
            }
        } finally {
            DataStore.configurationStore.unregisterChangeListener(listener)
        }
    }
}
