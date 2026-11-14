package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {
        val workManager = try {
            WorkManager.getInstance(app)
        } catch (e: Throwable) {
            Logs.w("SubscriptionUpdater: failed to get WorkManager instance", e)
            return
        }

        val subscriptions = try {
            SagerDatabase.groupDao.subscriptions()
                .filter { it.subscription?.autoUpdate == true && !it.subscription?.link.isNullOrBlank() }
        } catch (e: Throwable) {
            Logs.w("SubscriptionUpdater: failed to query subscriptions", e)
            return
        }

        if (subscriptions.isEmpty()) {
            try {
                workManager.cancelUniqueWork(WORK_NAME)
                Logs.d("SubscriptionUpdater: no auto-update subscriptions, work cancelled")
            } catch (e: Throwable) {
                Logs.w("SubscriptionUpdater: cancel work failed", e)
            }
            return
        }

        // PeriodicWorkRequest minimum interval is 15 minutes
        val minDelay = subscriptions.minOf { (it.subscription?.autoUpdateDelay ?: 1440).coerceAtLeast(15) }.toLong()
        val now = System.currentTimeMillis() / 1000L

        // Time until each subscription is due: (lastUpdated + autoUpdateDelay * 60) - now
        val minDueSeconds = subscriptions.minOf { group ->
            val sub = group.subscription!!
            val last = if (sub.lastUpdated > 0) sub.lastUpdated.toLong() else 0L
            val delaySec = (sub.autoUpdateDelay ?: 1440).coerceAtLeast(15).toLong() * 60L
            (last + delaySec) - now
        }

        val initialDelaySec = if (minDueSeconds <= 0L) {
            0L
        } else {
            minDueSeconds.coerceAtMost(minDelay * 60L)
        }

        Logs.d("SubscriptionUpdater: scheduling ${subscriptions.size} subscription(s), period=${minDelay}min, initialDelay=${initialDelaySec}s")

        try {
            val request = PeriodicWorkRequest.Builder(
                UpdateTask::class.java,
                minDelay,
                TimeUnit.MINUTES
            ).apply {
                if (initialDelaySec > 0) {
                    setInitialDelay(initialDelaySec, TimeUnit.SECONDS)
                }
            }.build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Logs.d("SubscriptionUpdater: work enqueued successfully")
        } catch (e: Throwable) {
            Logs.w("SubscriptionUpdater: enqueue work failed", e)
        }
    }

    fun checkPendingUpdates(onlyIfConnected: Boolean = false) {
        runOnDefaultDispatcher {
            runCatching {
                val now = System.currentTimeMillis() / 1000L
                var subscriptions = SagerDatabase.groupDao.subscriptions()
                    .filter { it.subscription?.autoUpdate == true && !it.subscription?.link.isNullOrBlank() }

                if (onlyIfConnected) {
                    subscriptions = subscriptions.filter { it.subscription?.updateWhenConnectedOnly == true }
                } else if (!DataStore.serviceState.connected) {
                    subscriptions = subscriptions.filter { it.subscription?.updateWhenConnectedOnly != true }
                }

                for (profile in subscriptions) {
                    val subscription = profile.subscription ?: continue
                    val delaySec = (subscription.autoUpdateDelay ?: 1440).coerceAtLeast(15).toLong() * 60L
                    val elapsedSec = now - subscription.lastUpdated.toLong()

                    if (subscription.lastUpdated <= 0 || elapsedSec >= delaySec) {
                        Logs.d("SubscriptionUpdater: executing pending update for ${profile.displayName()}")
                        GroupUpdater.executeUpdate(profile, false)
                    }
                }
            }.onFailure { e ->
                Logs.w("SubscriptionUpdater: checkPendingUpdates failed", e)
            }
        }
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            Logs.d("SubscriptionUpdater: UpdateTask started, serviceState=${DataStore.serviceState}")
            val now = System.currentTimeMillis() / 1000L
            var subscriptions = try {
                SagerDatabase.groupDao.subscriptions()
                    .filter { it.subscription?.autoUpdate == true && !it.subscription?.link.isNullOrBlank() }
            } catch (e: Throwable) {
                Logs.w("SubscriptionUpdater: failed to query subscriptions in worker", e)
                return Result.failure()
            }

            if (!DataStore.serviceState.connected) {
                Logs.d("SubscriptionUpdater: proxy not connected, filtering updateWhenConnectedOnly")
                subscriptions = subscriptions.filter { it.subscription?.updateWhenConnectedOnly != true }
            }

            if (subscriptions.isEmpty()) {
                Logs.d("SubscriptionUpdater: no subscriptions eligible for update right now")
                return Result.success()
            }

            val nm = NotificationManagerCompat.from(applicationContext)
            val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
                .setWhen(0)
                .setTicker(applicationContext.getString(R.string.forward_success))
                .setContentTitle(applicationContext.getString(R.string.subscription_update))
                .setSmallIcon(R.drawable.ic_throne_tile)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)

            for (profile in subscriptions) {
                val subscription = profile.subscription ?: continue
                val delaySec = (subscription.autoUpdateDelay ?: 1440).coerceAtLeast(15).toLong() * 60L
                val elapsedSec = now - subscription.lastUpdated.toLong()

                // Allow 30 seconds jitter margin
                if (subscription.lastUpdated > 0 && elapsedSec < (delaySec - 30L)) {
                    Logs.d("SubscriptionUpdater: skipping ${profile.displayName()}, elapsed=${elapsedSec}s < delay=${delaySec}s")
                    continue
                }

                Logs.d("SubscriptionUpdater: updating ${profile.displayName()}")
                runCatching {
                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message, profile.displayName()
                        )
                    )
                    nm.notify(2, notification.build())
                }

                runCatching {
                    GroupUpdater.executeUpdate(profile, false)
                }.onFailure { e ->
                    Logs.w("SubscriptionUpdater: update failed for ${profile.displayName()}", e)
                }
            }

            runCatching { nm.cancel(2) }
            return Result.success()
        }
    }

}
