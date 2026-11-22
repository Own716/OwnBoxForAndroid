package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.root.RootTunManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag: String get() = "SagerNetProxyService"

    override suspend fun startProcesses() {
        if (RootTunManager.hasActiveState(this)) {
            Logs.i("ProxyService: clearing residual Root mode rules before starting Proxy mode")
            withContext(Dispatchers.IO) {
                runCatching { RootTunManager.stop(this@ProxyService) }
                    .onFailure { Logs.w("ProxyService: residual Root cleanup failed", it) }
            }
        }
        super.startProcesses()
    }
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(
            this, profileName,
            if (DataStore.liveUpdateEnabled) "service-live-update" else "service-proxy",
            true
        )

    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:proxy")
            .apply { acquire() }
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)

    override fun onDestroy() {
        runCatching { data.smartAutoStopController?.stop() }
        data.smartAutoStopController = null
        super.onDestroy()
        data.binder.close()
    }
}
