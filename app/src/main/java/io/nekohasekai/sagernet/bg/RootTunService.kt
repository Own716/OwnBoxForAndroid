package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.root.RootShell
import io.nekohasekai.sagernet.bg.root.RootTunManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Foreground service running root-privileged `hev-socks5-tunnel` + iptables policy routing
 * backed by the app's in-process `sing-box` loopback SOCKS5 inbound.
 */
class RootTunService : Service(), BaseService.Interface {

    override val data = BaseService.Data(this)
    override val tag get() = "SagerNetRootTunService"
    override var upstreamInterfaceName: String? = null
    override var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun createNotification(profileName: String) = ServiceNotification(
        this,
        profileName,
        if (DataStore.liveUpdateEnabled) "service-live-update" else "service-vpn-v2",
        true,
    )

    override fun onBind(intent: Intent) = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
    }

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:root_tun")
                .apply { acquire() }
        }
        if (wifiLock == null) {
            try {
                val wifiManager =
                    applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager?.createWifiLock(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    },
                    "sagernet:root_tun_wifi",
                )?.apply { acquire() }
            } catch (t: Throwable) {
                Logs.w("RootTunService: failed to acquire wifiLock", t)
            }
        }
    }

    override suspend fun startProcesses() {
        val hasRoot = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
        if (!hasRoot) {
            throw IllegalStateException(getString(R.string.root_tun_no_root))
        }
        val hevtunBin = RootTunManager.findHevtunBinary(this)
            ?: throw IllegalStateException(getString(R.string.root_tun_missing_binary))

        // Start sing-box first so the loopback root-tun-in SOCKS5 port is listening before hevsockstun starts.
        super.startProcesses()

        val config = data.proxy?.config
        if (config == null || config.rootSocksPort <= 0) {
            throw IllegalStateException(getString(R.string.root_tun_start_failed))
        }

        val ownUid = Process.myUid()
        val perAppEnabled = DataStore.proxyApps
        val perAppUids = if (perAppEnabled) {
            withContext(Dispatchers.IO) {
                RootTunManager.resolvePerAppUids(
                    context = this@RootTunService,
                    individualRaw = DataStore.individual,
                    ownUid = ownUid,
                )
            }
        } else {
            emptySet()
        }

        val params = RootTunManager.StartParams(
            ownUid = ownUid,
            socksPort = config.rootSocksPort,
            socksUsername = config.rootSocksUsername,
            socksPassword = config.rootSocksPassword,
            mtu = DataStore.mtu,
            enableIpv6 = DataStore.ipv6Mode != IPv6Mode.DISABLE,
            bypassLan = DataStore.bypassLan,
            rootLanSharing = DataStore.rootLanSharing,
            perAppEnabled = perAppEnabled,
            perAppBypass = DataStore.bypass,
            perAppUids = perAppUids,
            hevtunBinPath = hevtunBin,
            yamlPath = RootTunManager.defaultYamlFile(this).absolutePath,
            pidPath = RootTunManager.defaultPidFile(this).absolutePath,
        )

        withContext(Dispatchers.IO) {
            RootTunManager.start(this@RootTunService, params)
        }
    }

    override suspend fun killProcesses(): Throwable? {
        try {
            withContext(Dispatchers.IO) {
                val res = RootTunManager.stop(this@RootTunService)
                if (!res.isSuccess && res.output.isNotBlank()) {
                    Logs.w("RootTunService teardown returned ${res.exitCode}: ${res.output}")
                }
            }
        } catch (t: Throwable) {
            Logs.w("RootTunService teardown error", t)
        }
        try {
            wifiLock?.release()
        } catch (t: Throwable) {
            Logs.w(t)
        } finally {
            wifiLock = null
        }
        return super.killProcesses()
    }

    override fun onDestroy() {
        runCatching { wifiLock?.release() }
        wifiLock = null
        if (RootTunManager.defaultPidFile(this).exists()) {
            val appCtx = applicationContext
            thread(isDaemon = true, name = "ownbox-root-cleanup") {
                runCatching { RootTunManager.stop(appCtx) }
            }
        }
        super.onDestroy()
    }
}
