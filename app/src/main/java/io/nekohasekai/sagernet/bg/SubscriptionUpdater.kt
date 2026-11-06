package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import com.google.common.util.concurrent.ListenableFuture
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
        suspendCancellableCoroutine { cont ->
            addListener({
                try {
                    cont.resume(get())
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            }, { it.run() })
        }

    suspend fun reconfigureUpdater() {
        val workManager = RemoteWorkManager.getInstance(app)
        try {
            workManager.cancelUniqueWork(WORK_NAME).awaitResult()
        } catch (e: Throwable) {
            Logs.w("SubscriptionUpdater: cancel work failed", e)
        }

        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
        if (subscriptions.isEmpty()) {
            Logs.d("SubscriptionUpdater: no auto-update subscriptions, work cancelled")
            return
        }

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        var minDelay =
            subscriptions.minByOrNull { it.subscription!!.autoUpdateDelay }!!.subscription!!.autoUpdateDelay.toLong()
        val now = System.currentTimeMillis() / 1000L
        var minInitDelay =
            subscriptions.minOf { it.subscription!!.lastUpdated + minDelay * 60 - now }
        if (minDelay < 15) minDelay = 15
        if (minInitDelay > 60) minInitDelay = 60

        Logs.d("SubscriptionUpdater: scheduling ${subscriptions.size} subscription(s), period=${minDelay}min, initDelay=${minInitDelay}s")

        // main process
        try {
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                UPDATE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                    .setInputData(
                        Data.Builder()
                            // Run the worker in the :bg process (RemoteWorkerService),
                            // where DataStore.serviceState is maintained by BaseService.
                            .putString(
                                RemoteListenableWorker.ARGUMENT_PACKAGE_NAME,
                                app.packageName
                            )
                            .putString(
                                RemoteListenableWorker.ARGUMENT_CLASS_NAME,
                                RemoteWorkerService::class.java.name
                            )
                            .build()
                    )
                    .apply {
                        if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                    }
                    .build()
            ).awaitResult()
            Logs.d("SubscriptionUpdater: work enqueued")
        } catch (e: Throwable) {
            Logs.w("SubscriptionUpdater: enqueue work failed", e)
        }
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : RemoteCoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_throne_tile)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doRemoteWork(): Result {
            Logs.d("SubscriptionUpdater: work started, serviceState=${DataStore.serviceState}")
            var subscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            if (subscriptions.isNotEmpty()) for (profile in subscriptions) {
                val subscription = profile.subscription!!

                if (((System.currentTimeMillis() / 1000).toInt() - subscription.lastUpdated) < subscription.autoUpdateDelay * 60) {
                    Logs.d("work: not updating " + profile.displayName())
                    continue
                }
                Logs.d("work: updating " + profile.displayName())

                notification.setContentText(
                    applicationContext.getString(
                        R.string.subscription_update_message, profile.displayName()
                    )
                )
                nm.notify(2, notification.build())

                GroupUpdater.executeUpdate(profile, false)
            }

            nm.cancel(2)

            return Result.success()
        }
    }

}
