package io.nekohasekai.sagernet.bg.autoselector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-process observable mirror of the running AutoSelector state delivered via AIDL
 * (`cbAutoSelectorUpdate` and `getAutoSelectorStatus`).
 */
object AutoSelectorClient {

    private val _status = MutableStateFlow(AutoSelectorStatus.IDLE)
    val status: StateFlow<AutoSelectorStatus> = _status.asStateFlow()

    fun onStatusJson(json: String?) {
        _status.value = AutoSelectorStatus.parse(json)
    }

    fun reset() {
        _status.value = AutoSelectorStatus.IDLE
    }
}
