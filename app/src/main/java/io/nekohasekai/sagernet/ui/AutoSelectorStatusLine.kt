package io.nekohasekai.sagernet.ui

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.database.DataStore

/**
 * Controls the slim runtime status strip under the group tabs in [ConfigurationFragment]
 * (matching Screenshot 2: "自动选择已停止" when stopped, or live selected node/health when running).
 */
object AutoSelectorStatusLine {

    fun render(
        view: TextView?,
        status: AutoSelectorStatus,
        hasAutoSelectorInUi: Boolean,
        resolveMemberName: (Long, String) -> String = { id, tag -> tag.ifBlank { if (id > 0L) "#$id" else "" } },
    ) {
        if (view == null) return
        val shouldShow = status.running || hasAutoSelectorInUi
        view.isVisible = shouldShow
        if (!shouldShow) return

        val text = if (status.running) {
            AutoSelectorTexts.statusHeadline(view.context, status, System.currentTimeMillis(), resolveMemberName)
        } else {
            view.context.getString(R.string.autosel_status_stopped)
        }
        if (view.text?.toString() != text) {
            view.text = text
        }
        view.visibility = View.VISIBLE
    }

    fun pickFallbackSelectorId(
        status: AutoSelectorStatus,
        visibleAutoSelectorId: Long = 0L,
        firstKnownAutoSelectorId: Long = 0L,
    ): Long {
        if (status.selectorId > 0L) return status.selectorId
        if (visibleAutoSelectorId > 0L) return visibleAutoSelectorId
        if (DataStore.selectedProxy > 0L && firstKnownAutoSelectorId == DataStore.selectedProxy) {
            return DataStore.selectedProxy
        }
        return firstKnownAutoSelectorId
    }
}
