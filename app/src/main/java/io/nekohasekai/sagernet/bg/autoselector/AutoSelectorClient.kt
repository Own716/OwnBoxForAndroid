package io.nekohasekai.sagernet.bg.autoselector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The UI's copy of the service's auto-selector status without its member table, fed by every
 * [io.nekohasekai.sagernet.bg.SagerConnection].
 */
object AutoSelectorClient {

    private val mutableStatus = MutableStateFlow(AutoSelectorStatus.IDLE)

    val status: StateFlow<AutoSelectorStatus> = mutableStatus

    fun update(json: String?) {
        mutableStatus.value = AutoSelectorStatus.parse(json)
    }

    /** The service process went away. */
    fun reset() {
        mutableStatus.value = AutoSelectorStatus.IDLE
    }
}
