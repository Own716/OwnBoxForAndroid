package io.nekohasekai.sagernet.ui.profiles

import android.os.SystemClock
import androidx.room.InvalidationTracker
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Writes of the `:bg` process (subscription refreshes, test results, imports) reach this process only through
 * Room's multi-instance invalidation: [onProfiles] / [onGroups] run on the main thread after the `profiles` /
 * `groups` tables changed, at most once per [THROTTLE_MS] while changes keep coming. [onProfiles] returns when the
 * reload it started finished, and a reload that took longer than [THROTTLE_MS] is followed by a pause as long.
 */
internal class ProfilesDbWatcher(
    private val onProfiles: suspend () -> Unit,
    private val onGroups: () -> Unit,
) {

    companion object {
        private const val THROTTLE_MS = 400L
    }

    private val profiles = Channel<Unit>(Channel.CONFLATED)
    private val groups = Channel<Unit>(Channel.CONFLATED)

    private val observer = object : InvalidationTracker.Observer(arrayOf(ProxyEntity.TABLE, ProxyGroup.TABLE)) {
        override fun onInvalidated(tables: Set<String>) {
            if (tables.any { it.equals(ProxyEntity.TABLE, true) }) profiles.trySend(Unit)
            if (tables.any { it.equals(ProxyGroup.TABLE, true) }) groups.trySend(Unit)
        }
    }

    /**
     * Watches until [scope] is cancelled, once per instance; [catchUp] reports both tables once, for changes made while
     * not watching.
     */
    fun start(scope: CoroutineScope, catchUp: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val tracker = SagerDatabase.instance.invalidationTracker
            try {
                tracker.addObserver(observer)
            } catch (e: Exception) {
                Logs.w(e)
                return@launch
            }
            if (catchUp) {
                profiles.trySend(Unit)
                groups.trySend(Unit)
            }
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { tracker.removeObserver(observer) }
            }
        }
        scope.launch(Dispatchers.Main) {
            for (signal in profiles) {
                val started = SystemClock.uptimeMillis()
                onProfiles()
                delay(max(THROTTLE_MS, SystemClock.uptimeMillis() - started))
            }
        }
        scope.launch(Dispatchers.Main) {
            for (signal in groups) {
                onGroups()
                delay(THROTTLE_MS)
            }
        }
    }
}
