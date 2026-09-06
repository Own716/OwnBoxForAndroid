package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.system.OsConstants
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.SystemClock
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.runBlocking
import libcore.BoxPlatformInterface
import libcore.InterfaceUpdateListener
import libcore.Libcore
import libcore.NB4AInterface
import libcore.NetworkInterfaceIterator
import libcore.StringIterator
import java.net.Inet6Address
import java.util.Collections
import java.util.WeakHashMap
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import libcore.NetworkInterface as LibcoreNetworkInterface

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    // 默认接口监视器（sing-box 官方内核强制平台提供）。
    // 复用 DefaultNetworkListener：registerBestMatchingNetworkCallback 避开 VPN 接口，
    // 报告的是物理默认网络（WiFi/蜂窝）。

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        // 必须同步注册：官方内核拨号时 DefaultInterface()==nil 会秒报
        // "no available network interface"（见 libcore/interface_monitor.go 批注）。
        // 原先 runOnDefaultDispatcher 异步注册，测试盒 box.Start() 后立刻拨号，
        // 首拨几乎必然抢在首次回调之前 → 批量测速大面积"超时"。
        // DefaultNetworkListener.start 会等待 actor 处理 Start；缓存命中时还会等待
        // 首次回调及 Go updateDefaultInterface 完成后才返回（Go 线程短暂阻塞，可接受）。
        runBlocking {
            DefaultNetworkListener.start(listener) { network ->
                checkDefaultInterfaceUpdate(listener, network)
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        // 与 start 对称同步化，避免 box.Close 后监听者残留/时序错乱。
        runBlocking {
            DefaultNetworkListener.stop(listener)
        }
    }

    // 每个监听器（box）的上报状态：用于 Info 去重 + 风暴计数诊断。
    // WeakHashMap 键为 gomobile 代理对象，box close 后不泄漏。
    private class IfaceReportState {
        var name: String? = null
        var index: Int = Int.MIN_VALUE
        var network: Network? = null
        var suppressed: Int = 0
    }

    private val ifaceReportStates = Collections.synchronizedMap(
        WeakHashMap<InterfaceUpdateListener, IfaceReportState>()
    )

    private fun reportState(listener: InterfaceUpdateListener): IfaceReportState =
        synchronized(ifaceReportStates) {
            ifaceReportStates.getOrPut(listener) { IfaceReportState() }
        }

    private fun checkDefaultInterfaceUpdate(listener: InterfaceUpdateListener, network: Network?) {
        // 诊断：入口线程 + 全程耗时（验证事件链是否跑在主线程、单次事件成本）
        val start = SystemClock.elapsedRealtime()
        Logs.d("checkDefaultInterfaceUpdate enter network=$network thread=${Thread.currentThread().name}")
        // 同步「网络变化时重置出站」开关到 Go：控制 name/index 变化时是否
        // callback → 官方 ResetNetwork（见 libcore/interface_monitor.go）。
        Libcore.setNetworkChangeResetConnections(DataStore.networkChangeResetConnections)
        val state = reportState(listener)
        if (network == null) {
            Logs.i("checkDefaultInterfaceUpdate network=null -> clear default interface suppressedSinceLast=${state.suppressed} elapsed=${SystemClock.elapsedRealtime() - start}ms")
            state.name = null
            state.index = Int.MIN_VALUE
            state.network = null
            state.suppressed = 0
            listener.updateDefaultInterface("", -1)
            return
        }
        // LinkProperties / NetworkInterface 可能短暂未就绪，参考 husi/SFA 重试
        repeat(10) { attempt ->
            val linkProperties = SagerNet.connectivity.getLinkProperties(network)
            if (linkProperties == null) {
                Logs.d("checkDefaultInterfaceUpdate attempt=${attempt + 1} linkProperties=null network=$network")
                Thread.sleep(100)
                return@repeat
            }
            val interfaceIndex = try {
                NetworkInterface.getByName(linkProperties.interfaceName).index
            } catch (e: Exception) {
                Logs.d("checkDefaultInterfaceUpdate attempt=${attempt + 1} getByName failed name=${linkProperties.interfaceName}: $e")
                Thread.sleep(100)
                return@repeat
            }
            // 结果与上次完全相同：短路，不调 Go。
            // Go 侧 unchanged 路径本就只是 UpdateInterfaces 刷缓存后 skip ResetNetwork，
            // 不上报零语义损失（下次真实变化时全链照跑、缓存照刷）。
            // 批量测速时每个 test box 挂一个监听器，onCapabilitiesChanged 风暴 ×
            // N 个 box × (JNI + Go UpdateInterfaces 全量枚举) 曾把主线程按秒阻塞。
            if (state.name == linkProperties.interfaceName && state.index == interfaceIndex && state.network == network) {
                state.suppressed++
                if (state.suppressed == 1 || state.suppressed % 20 == 0) {
                    Logs.d("checkDefaultInterfaceUpdate duplicate #${state.suppressed} name=${linkProperties.interfaceName} index=$interfaceIndex network=$network elapsed=${SystemClock.elapsedRealtime() - start}ms (unchanged, skip Go)")
                }
                return
            }
            Logs.i("checkDefaultInterfaceUpdate ok name=${linkProperties.interfaceName} index=$interfaceIndex network=$network attempt=${attempt + 1} suppressedSinceLast=${state.suppressed} elapsed=${SystemClock.elapsedRealtime() - start}ms thread=${Thread.currentThread().name}")
            state.name = linkProperties.interfaceName
            state.index = interfaceIndex
            state.network = network
            state.suppressed = 0
            listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex)
            return
        }
        Logs.w("checkDefaultInterfaceUpdate exhausted retries network=$network -> clear default interface suppressedSinceLast=${state.suppressed} elapsed=${SystemClock.elapsedRealtime() - start}ms")
        state.name = null
        state.index = Int.MIN_VALUE
        state.network = null
        state.suppressed = 0
        listener.updateDefaultInterface("", -1)
    }

    // 平台网络接口枚举（sing-box 官方内核拨号路径强制要求，否则报 no available network interface）。
    // 参考 husi AndroidPlatformInterface.getInterfaces。

    override fun getInterfaces(): NetworkInterfaceIterator {
        @Suppress("DEPRECATION") val networks = SagerNet.connectivity.allNetworks
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<LibcoreNetworkInterface>()
        for (network in networks) {
            val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: continue
            val boxInterface = LibcoreNetworkInterface()
            boxInterface.name = linkProperties.interfaceName
            val networkInterface = networkInterfaces.find { it.name == boxInterface.name } ?: continue
            boxInterface.dnsServer = linkProperties.dnsServers.mapNotNull { it.hostAddress }
                .let { it.toStringIterator(it.size) }
            boxInterface.type = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libcore.InterfaceTypeWIFI
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libcore.InterfaceTypeCellular
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libcore.InterfaceTypeEthernet
                else -> Libcore.InterfaceTypeOther
            }
            boxInterface.index = networkInterface.index
            runCatching { boxInterface.mtu = networkInterface.mtu }
                .onFailure { Logs.w("failed to get mtu for interface ${boxInterface.name}: $it") }
            boxInterface.addresses = networkInterface.interfaceAddresses.map { it.toPrefix() }
                .let { it.toStringIterator(it.size) }
            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            if (networkInterface.isPointToPoint) dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            if (networkInterface.supportsMulticast()) dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator(), interfaces.size)
    }

    private class InterfaceArray(
        private val iterator: Iterator<LibcoreNetworkInterface>,
        private val size: Int,
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibcoreNetworkInterface = iterator.next()
        override fun length(): Int = size
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id = data.proxy!!.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}

private fun Iterable<String>.toStringIterator(size: Int): StringIterator {
    return object : StringIterator {
        private val it = iterator()
        override fun hasNext(): Boolean = it.hasNext()
        override fun next(): String = it.next()
        override fun length(): Int = size
    }
}

private fun InterfaceAddress.toPrefix(): String {
    return if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/${networkPrefixLength}"
    } else {
        "${address.hostAddress}/${networkPrefixLength}"
    }
}
