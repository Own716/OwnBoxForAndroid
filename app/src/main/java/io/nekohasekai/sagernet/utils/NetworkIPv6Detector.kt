package io.nekohasekai.sagernet.utils

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import libcore.Libcore
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Inspects the default physical underlying network's [LinkProperties] to determine whether
 * usable IPv4 and routable global-unicast IPv6 (2000::/3) exist, and synchronizes the live
 * IPv6 state to Go (`libcore`).
 */
object NetworkIPv6Detector {

    data class NetworkIpCapabilities(
        val hasIPv4: Boolean,
        val hasPublicIPv6: Boolean,
    )

    internal fun isPublicIPv6Address(addr: InetAddress): Boolean {
        if (addr !is Inet6Address) return false
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress) {
            return false
        }
        val bytes = addr.address
        if (bytes.size != 16) return false
        val firstByte = bytes[0].toInt() and 0xFF
        // Exclude Unique Local Addresses (fc00::/7)
        if ((firstByte and 0xFE) == 0xFC) return false
        // Require global unicast prefix (2000::/3)
        return (firstByte and 0xE0) == 0x20
    }

    internal fun isUsableIPv4Address(addr: InetAddress): Boolean {
        return addr is Inet4Address &&
            !addr.isLoopbackAddress &&
            !addr.isLinkLocalAddress &&
            !addr.isMulticastAddress &&
            !addr.isAnyLocalAddress
    }

    internal fun evaluateCapabilities(
        addresses: Collection<InetAddress>,
        hasRoutesInfo: Boolean,
        hasDefaultIPv6Route: Boolean,
    ): NetworkIpCapabilities {
        val hasIPv4 = addresses.any { isUsableIPv4Address(it) }
        val hasGlobalV6Addr = addresses.any { isPublicIPv6Address(it) }
        val hasPublicIPv6 = hasGlobalV6Addr && (!hasRoutesInfo || hasDefaultIPv6Route)
        return NetworkIpCapabilities(hasIPv4 = hasIPv4, hasPublicIPv6 = hasPublicIPv6)
    }

    fun inspectLinkProperties(lp: LinkProperties?): NetworkIpCapabilities {
        if (lp == null) {
            return NetworkIpCapabilities(hasIPv4 = true, hasPublicIPv6 = false)
        }
        val addresses = lp.linkAddresses.mapNotNull { it.address }
        val routes = lp.routes
        val hasRoutesInfo = routes.isNotEmpty()
        val hasDefaultIPv6Route = routes.any { route ->
            route.isDefaultRoute && route.destination?.address is Inet6Address
        }
        return evaluateCapabilities(
            addresses = addresses,
            hasRoutesInfo = hasRoutesInfo,
            hasDefaultIPv6Route = hasDefaultIPv6Route,
        )
    }

    fun inspectDefaultPhysicalNetwork(): NetworkIpCapabilities {
        val cm = SagerNet.connectivity
        val underlying = SagerNet.underlyingNetwork
        val targetNetwork = if (underlying != null) {
            underlying
        } else {
            val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.activeNetwork else null
            val isVpn = active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            if (isVpn) null else active
        } ?: return NetworkIpCapabilities(hasIPv4 = true, hasPublicIPv6 = false)

        val lp = runCatching { cm.getLinkProperties(targetNetwork) }.getOrNull()
        return inspectLinkProperties(lp)
    }

    fun syncToCore(lp: LinkProperties? = null): NetworkIpCapabilities {
        val caps = if (lp != null) inspectLinkProperties(lp) else inspectDefaultPhysicalNetwork()
        runCatching { Libcore.setDirectNetworkIPv6Available(caps.hasPublicIPv6) }
        return caps
    }
}
