package io.nekohasekai.sagernet.utils

import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import java.net.Inet4Address

object TrustedNetworkManager {

    private var isPausedByTrusted = false

    fun checkAndApply(network: Network?) {
        if (!DataStore.trustedNetworkSmartEnabled) {
            if (isPausedByTrusted) {
                resumeProxy()
            }
            return
        }

        if (network == null) {
            if (isPausedByTrusted) resumeProxy()
            return
        }

        val cm = SagerNet.connectivity
        val caps = cm.getNetworkCapabilities(network) ?: return
        val linkProps = cm.getLinkProperties(network)

        val isWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (!isWifiOrEthernet) {
            // 切入移动蜂窝网络 -> 恢复代理
            if (isPausedByTrusted) resumeProxy()
            return
        }

        var isTrusted = false

        // 1. 匹配受信任 Wi-Fi SSID
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wm = SagerNet.application.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val currentSsid = wm?.connectionInfo?.ssid?.trim('\"')
            if (!currentSsid.isNullOrBlank() && currentSsid != "<unknown ssid>") {
                if (DataStore.trustedWifiSsids.contains(currentSsid)) {
                    Logs.i("TrustedNetworkManager: matched trusted Wi-Fi SSID: $currentSsid")
                    isTrusted = true
                }
            }
        }

        // 2. 匹配受信任内网网关或私网 IP
        if (!isTrusted && linkProps != null) {
            val trustedIps = DataStore.trustedLanIps
            if (trustedIps.isNotEmpty()) {
                val gateways = linkProps.routes.mapNotNull { it.gateway?.hostAddress }
                if (gateways.any { trustedIps.contains(it) }) {
                    Logs.i("TrustedNetworkManager: matched trusted Gateway IP")
                    isTrusted = true
                }
                val localIps = linkProps.linkAddresses.mapNotNull {
                    if (it.address is Inet4Address) it.address.hostAddress else null
                }
                if (localIps.any { trustedIps.contains(it) }) {
                    Logs.i("TrustedNetworkManager: matched trusted LAN IP")
                    isTrusted = true
                }
            }
        }

        if (isTrusted) {
            pauseProxy()
        } else if (isPausedByTrusted) {
            resumeProxy()
        }
    }

    private fun pauseProxy() {
        if (isPausedByTrusted) return
        isPausedByTrusted = true
        Logs.i("TrustedNetworkManager: entering trusted network, pausing proxy service")
        runOnDefaultDispatcher {
            SagerNet.stopService()
        }
    }

    private fun resumeProxy() {
        if (!isPausedByTrusted) return
        isPausedByTrusted = false
        Logs.i("TrustedNetworkManager: leaving trusted network, resuming proxy service")
        runOnDefaultDispatcher {
            SagerNet.startService()
        }
    }
}
