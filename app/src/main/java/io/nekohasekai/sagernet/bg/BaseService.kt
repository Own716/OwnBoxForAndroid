package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.app.ActivityManager
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.io.File
import java.net.UnknownHostException

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var cacheRecoveryAttempts = 0

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                Action.RESTART -> {
                    Logs.i("BaseService: received Action.RESTART, forcing full stopRunner(restart = true)")
                    service.stopRunner(restart = true)
                }
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (SagerNet.power.isDeviceIdleMode) {
                            // Doze mode: do NOT call proxy?.box?.sleep() / pauseManager.DevicePause().
                            // Pausing TUN inbound during Doze blocks background push notifications & sync,
                            // causing disconnects and socket timeouts when phone is idle for hours.
                            // Instead, only run a memory trim while keeping TUN/network completely alive.
                            Libcore.forceGc()
                        } else {
                            proxy?.box?.wake()
                            if (DataStore.wakeResetConnections) {
                                Libcore.resetAllConnections(true)
                            }
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    Libcore.resetAllConnections(true)
                    LandingIpManager.clearCache()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.reset_connections_done),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                Action.SWITCH_PERFORMANCE_MODE -> {
                    val enabled = DataStore.performancePriorityMode
                    Logs.i("BaseService: SWITCH_PERFORMANCE_MODE received, enabled=$enabled")
                    if (!enabled) {
                        Libcore.forceGc()
                        System.gc()
                    }
                }

                Intent.ACTION_SCREEN_OFF -> {
                    proxy?.box?.sleep()
                    // Screen turned off: in low power / standard mode, reclaim OS memory to keep background RSS minimal
                    if (!DataStore.performancePriorityMode) {
                        Libcore.forceGc()
                        System.gc()
                    }
                }

                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    proxy?.box?.wake()
                    runOnDefaultDispatcher {
                        proxy?.looper?.postLastSnapshotSpeed()
                    }
                    if (DataStore.wakeResetConnections) {
                        Libcore.resetAllConnections(true)
                    }
                }

                Action.CLOSE -> service.stopRunner()

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
            runCatching { io.nekohasekai.sagernet.widget.OwnBoxWidgetProvider.updateWidgets(SagerNet.application) }
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box, DataStore.connectionTestURL, DataStore.connectionTestTimeout
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        override fun urlTestCustomUrl(url: String, timeoutMs: Int): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                // urlTestFull 直接经 default outbound 拨号，完全绕过路由规则，杜绝 geosite:cn 等分流劫持
                return Libcore.urlTestFull(
                    data!!.proxy!!.box, url, timeoutMs
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        override fun postNotificationSpeed(speed: io.nekohasekai.sagernet.aidl.SpeedDisplayData) {
            launch {
                data?.notification?.postNotificationSpeedUpdate(speed)
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            if (canReloadSelector()) {
                val proxy = data.proxy
                val box = runCatching { proxy?.box }.getOrNull()
                val tag = proxy?.config?.profileTagMap?.get(DataStore.selectedProxy) ?: ""
                if (box != null && tag.isNotBlank()) {
                    try {
                        box.selectOutbound(tag)
                        return
                    } catch (e: Exception) {
                        Logs.w("Failed to selectOutbound($tag): ${e.message}, restarting service")
                    }
                }
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> {
                    Logs.w("State $s encountered during reload, restarting runner")
                    stopRunner(true)
                }
            }
        }

        fun canReloadSelector(): Boolean {
            val proxy = data.proxy ?: return false
            runCatching { proxy.box }.getOrNull() ?: return false
            if (data.state != State.Connected) return false
            if (proxy.config.selectorGroupId < 0L) return false
            val profileId = DataStore.selectedProxy
            if (profileId <= 0L) return false
            val tag = proxy.config.profileTagMap[profileId]
            return !tag.isNullOrBlank()
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        fun deleteCorruptedCacheDb() {
            val context = this as Context
            val candidates = mutableListOf<File>()

            runCatching {
                candidates.add(File(context.cacheDir, "cache.db"))
                context.cacheDir.listFiles { f -> f.name.startsWith("cache.db") }?.let { candidates.addAll(it) }
            }
            runCatching {
                candidates.add(File(context.filesDir, "cache.db"))
                context.filesDir.listFiles { f -> f.name.startsWith("cache.db") }?.let { candidates.addAll(it) }
            }
            runCatching {
                candidates.add(File(context.noBackupFilesDir, "cache.db"))
                context.noBackupFilesDir.listFiles { f -> f.name.startsWith("cache.db") }?.let { candidates.addAll(it) }
            }
            runCatching {
                context.filesDir.parentFile?.let { parent ->
                    val parentCache = File(parent, "cache")
                    if (parentCache.exists()) {
                        candidates.add(File(parentCache, "cache.db"))
                        parentCache.listFiles { f -> f.name.startsWith("cache.db") }?.let { candidates.addAll(it) }
                    }
                }
            }

            candidates.distinctBy { it.absolutePath }.forEach { file ->
                runCatching {
                    if (file.exists()) {
                        val deleted = file.delete()
                        Logs.i("Auto-recovery: delete cache file ${file.absolutePath}, success=$deleted")
                    }
                }.onFailure {
                    Logs.w("Auto-recovery: failed to delete ${file.absolutePath}", it)
                }
            }
        }

        suspend fun killProcesses(): Throwable? {
            val proxy = data.proxy
            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            var cleanupError: Throwable? = null
            fun recordCleanupFailure(stage: String, error: Throwable) {
                if (cleanupError == null) {
                    cleanupError = error
                } else if (cleanupError !== error) {
                    cleanupError?.addSuppressed(error)
                }
                Logs.w(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=$stage failed " +
                        "type=${error.javaClass.name} message=${error.message}"
                )
            }
            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill begin"
            )
            try {
                withContext(Dispatchers.IO) {
                    proxy?.close()
                }
                Logs.i(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=proxy-close success"
                )
            } catch (error: Throwable) {
                recordCleanupFailure("proxy-close", error)
            }

            try {
                wakeLock?.release()
            } catch (error: Throwable) {
                recordCleanupFailure("wake-lock-release", error)
            } finally {
                wakeLock = null
            }

            try {
                DefaultNetworkListener.stop(this)
            } catch (error: Throwable) {
                recordCleanupFailure("network-listener-stop", error)
            }

            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill done " +
                    "hasCleanupError=${cleanupError != null}"
            )
            return cleanupError
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            DataStore.baseService = null
            DataStore.vpnService = null
            DataStore.mixedInboundAuthed = false
            if (!restart) {
                data.cacheRecoveryAttempts = 0
            }

            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxy = data.proxy
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            val caller = Thread.currentThread().stackTrace.firstOrNull { frame ->
                frame.className != Thread::class.java.name && frame.methodName != "stopRunner"
            }?.let { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
                ?: "unknown"
            Logs.i(
                "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId restart=$restart " +
                    "state=${data.state} profileId=${proxy?.profile?.id ?: -1L} " +
                    "hasMessage=${msg != null} caller=$caller"
            )
            if (data.state == State.Stopping) {
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=ignored-already-stopping"
                )
                return
            }
            this as Service

            data.changeState(State.Stopping)
            val originalMessage = msg

            runOnMainDispatcher {
                var cleanupError: Throwable? = null
                fun recordCleanupFailure(stage: String, error: Throwable) {
                    if (cleanupError == null) {
                        cleanupError = error
                    } else if (cleanupError !== error) {
                        cleanupError?.addSuppressed(error)
                    }
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=$stage failed type=${error.javaClass.name} " +
                            "message=${error.message}"
                    )
                }

                try {
                    data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                } catch (error: Throwable) {
                    recordCleanupFailure("connecting-job-cancel", error)
                } finally {
                    data.connectingJob = null
                }

                try {
                    data.notification?.destroy()
                } catch (error: Throwable) {
                    recordCleanupFailure("notification-destroy", error)
                } finally {
                    data.notification = null
                }

                try {
                    killProcesses()?.let { recordCleanupFailure("process-cleanup", it) }
                } catch (error: Throwable) {
                    recordCleanupFailure("process-cleanup-boundary", error)
                }

                try {
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                    }
                } catch (error: Throwable) {
                    recordCleanupFailure("receiver-unregister", error)
                } finally {
                    data.closeReceiverRegistered = false
                    data.proxy = null
                }

                cleanupError?.let { error ->
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=cleanup failed type=${error.javaClass.name} " +
                            "message=${error.message} suppressed=${error.suppressed.size} " +
                            "originalMessagePreserved=${originalMessage != null}"
                    )
                }

                try {
                    data.changeState(State.Stopped, originalMessage)
                } catch (error: Throwable) {
                    recordCleanupFailure("state-stopped", error)
                }
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=stopped restart=$restart hasCleanupError=${cleanupError != null}"
                )

                try {
                    // stop the service if nothing has bound to it
                    if (restart) {
                        delay(100)
                        startRunner()
                    } else {
                        stopSelf()
                    }
                } catch (error: Throwable) {
                    recordCleanupFailure("service-finish", error)
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            // 只负责 underlyingNetwork / 网卡名跟踪，供 VpnService.setUnderlyingNetworks。
            // 「网络变化时重置出站」由 DataStore.networkChangeResetConnections 控制，
            // 经 NativeInterface → Libcore.setNetworkChangeResetConnections →
            // interfaceMonitor 是否 callback → 官方 ResetNetwork 生效；
            // 此处不再叠调 resetAllConnections（避免与内核双路径各拆一次）。
            // 「唤醒时重置」见 receiver 内 DataStore.wakeResetConnections。
            DefaultNetworkListener.start(this) { network ->
                if (network == null) return@start
                SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                    SagerNet.underlyingNetwork = network
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        Logs.d("Network changed: $oldName -> ${link.interfaceName}")
                        upstreamInterfaceName = link.interfaceName
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_STICKY
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Action.RESTART)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                    addAction(Action.SWITCH_PERFORMANCE_MODE)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            runOnMainDispatcher {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    Executable.killAll()    // clean up old processes
                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }

                    startProcesses()
                    data.changeState(State.Connected)
                    data.cacheRecoveryAttempts = 0

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    val msg = exc.readableMessage
                    val isCacheCorrupt = msg.contains("invalid freelist page", ignoreCase = true) ||
                            msg.contains("initialize cache-file: timeout", ignoreCase = true) ||
                            msg.contains("initialize cache-file", ignoreCase = true) ||
                            msg.contains("freelist", ignoreCase = true) ||
                            (msg.contains("cache.db", ignoreCase = true) && msg.contains("panic", ignoreCase = true))

                    if (isCacheCorrupt && data.cacheRecoveryAttempts < 1) {
                        data.cacheRecoveryAttempts++
                        Logs.w("Auto-recovery: detected corrupted cache database ($msg). Purging cache.db and retrying once...")
                        deleteCorruptedCacheDb()
                        runCatching {
                            withContext(Dispatchers.IO) {
                                proxy.close()
                            }
                        }
                        data.proxy = null
                        delay(200)
                        startRunner()
                        return@runOnMainDispatcher
                    }
                    data.cacheRecoveryAttempts = 0

                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_STICKY
        }
    }

}
