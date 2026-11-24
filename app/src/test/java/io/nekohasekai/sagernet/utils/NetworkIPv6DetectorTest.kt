package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkIPv6DetectorTest {

    @Test
    fun publicIPv6ClassificationFiltersLinkLocalUlaLoopbackAndMulticast() {
        assertTrue(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("240e:940:602:daa:0:ff:b065:c07a")))
        assertTrue(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("2409:8a00:1234::1")))
        assertTrue(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("2001:4860:4860::8888")))

        assertFalse(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("fe80::1")))
        assertFalse(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("fc00::1")))
        assertFalse(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("fd12:3456:789a::1")))
        assertFalse(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("::1")))
        assertFalse(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("ff02::1")))
        assertFalse(NetworkIPv6Detector.isPublicIPv6Address(InetAddress.getByName("192.168.1.10")))
    }

    @Test
    fun usableIPv4ClassificationFiltersLoopbackAndLinkLocal() {
        assertTrue(NetworkIPv6Detector.isUsableIPv4Address(InetAddress.getByName("192.168.1.10")))
        assertTrue(NetworkIPv6Detector.isUsableIPv4Address(InetAddress.getByName("10.0.0.2")))

        assertFalse(NetworkIPv6Detector.isUsableIPv4Address(InetAddress.getByName("127.0.0.1")))
        assertFalse(NetworkIPv6Detector.isUsableIPv4Address(InetAddress.getByName("169.254.1.1")))
        assertFalse(NetworkIPv6Detector.isUsableIPv4Address(InetAddress.getByName("224.0.0.1")))
        assertFalse(NetworkIPv6Detector.isUsableIPv4Address(InetAddress.getByName("240e::1")))
    }

    @Test
    fun evaluateCapabilitiesDistinguishesDualStackIpv4OnlyAndIpv6Only() {
        // 1. Dual-stack broadband with default IPv6 route
        val dualStack = NetworkIPv6Detector.evaluateCapabilities(
            addresses = listOf(
                InetAddress.getByName("192.168.1.10"),
                InetAddress.getByName("fe80::1"),
                InetAddress.getByName("240e:940:602::100"),
            ),
            hasRoutesInfo = true,
            hasDefaultIPv6Route = true,
        )
        assertEquals(NetworkIPv6Detector.NetworkIpCapabilities(hasIPv4 = true, hasPublicIPv6 = true), dualStack)

        // 2. IPv4-only broadband (only link-local/ULA IPv6, no global IPv6 or no ::/0 route)
        val ipv4Only = NetworkIPv6Detector.evaluateCapabilities(
            addresses = listOf(
                InetAddress.getByName("192.168.1.10"),
                InetAddress.getByName("fe80::1"),
                InetAddress.getByName("fd00::1234"),
            ),
            hasRoutesInfo = true,
            hasDefaultIPv6Route = false,
        )
        assertEquals(NetworkIPv6Detector.NetworkIpCapabilities(hasIPv4 = true, hasPublicIPv6 = false), ipv4Only)

        // 3. Global IPv6 address present on interface, but router advertises no ::/0 default IPv6 route
        val globalWithoutDefaultRoute = NetworkIPv6Detector.evaluateCapabilities(
            addresses = listOf(
                InetAddress.getByName("192.168.1.10"),
                InetAddress.getByName("240e:940:602::100"),
            ),
            hasRoutesInfo = true,
            hasDefaultIPv6Route = false,
        )
        assertEquals(NetworkIPv6Detector.NetworkIpCapabilities(hasIPv4 = true, hasPublicIPv6 = false), globalWithoutDefaultRoute)

        // 4. Pure IPv6-only physical network (no IPv4)
        val pureIpv6 = NetworkIPv6Detector.evaluateCapabilities(
            addresses = listOf(
                InetAddress.getByName("2409:8a00:1234::1"),
            ),
            hasRoutesInfo = true,
            hasDefaultIPv6Route = true,
        )
        assertEquals(NetworkIPv6Detector.NetworkIpCapabilities(hasIPv4 = false, hasPublicIPv6 = true), pureIpv6)
    }
}
