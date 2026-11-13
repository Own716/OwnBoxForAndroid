package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.SmartAutoStopMatcher.NetworkSnapshot
import io.nekohasekai.sagernet.bg.SmartAutoStopMatcher.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutoStopMatcherTest {

    @Test
    fun matchesExactIpv4AndIpv6() {
        val ipv4Snapshot = NetworkSnapshot(
            ipAddresses = listOf("192.168.10.25", "fe80::1"),
            gatewayAddresses = listOf("192.168.10.1"),
            hasPhysicalNetwork = true,
        )
        val v4Hit = SmartAutoStopMatcher.evaluate(listOf("192.168.10.25"), ipv4Snapshot)
        assertTrue(v4Hit.isMatched)
        assertEquals("192.168.10.25", v4Hit.matchedRule)
        assertEquals("192.168.10.25", v4Hit.matchedAddress)

        val ipv6Snapshot = NetworkSnapshot(
            ipAddresses = listOf("fd00:1234::5"),
            gatewayAddresses = listOf("fe80::1"),
            hasPhysicalNetwork = true,
        )
        val v6Hit = SmartAutoStopMatcher.evaluate(listOf("fd00:1234::5"), ipv6Snapshot)
        assertTrue(v6Hit.isMatched)
        assertEquals("fd00:1234::5", v6Hit.matchedRule)
    }

    @Test
    fun matchesCidrAndGatewayPrefixCaseInsensitive() {
        val snapshot = NetworkSnapshot(
            ipAddresses = listOf("10.24.33.89"),
            gatewayAddresses = listOf("10.24.33.1", "fe80::a"),
            hasPhysicalNetwork = true,
        )

        val cidrHit = SmartAutoStopMatcher.evaluate(listOf("10.24.0.0/16"), snapshot)
        assertTrue(cidrHit.isMatched)
        assertEquals("10.24.0.0/16", cidrHit.matchedRule)
        assertEquals("10.24.33.89", cidrHit.matchedAddress)

        // Gateway prefix must match gatewayAddresses, not local IP when local IP differs
        val gwHit = SmartAutoStopMatcher.evaluate(listOf("GATEWAY:10.24.33.0/24"), snapshot)
        assertTrue(gwHit.isMatched)
        assertEquals("GATEWAY:10.24.33.0/24", gwHit.matchedRule)
        assertEquals("10.24.33.1", gwHit.matchedAddress)

        val gwMissLocalIp = SmartAutoStopMatcher.evaluate(listOf("gateway:10.24.33.89"), snapshot)
        assertFalse(gwMissLocalIp.isMatched)
    }

    @Test
    fun noPhysicalNetworkNeverMatches() {
        val snapshot = NetworkSnapshot(
            ipAddresses = listOf("192.168.1.50"),
            gatewayAddresses = listOf("192.168.1.1"),
            hasPhysicalNetwork = false,
        )
        val result = SmartAutoStopMatcher.evaluate(listOf("192.168.1.0/24", "gateway:192.168.1.1"), snapshot)
        assertFalse(result.isMatched)
    }

    @Test
    fun validatesRuleFormatAndMaxRuleLimit() {
        val valid = SmartAutoStopMatcher.validateRules(
            "192.168.1.0/24， 10.0.0.5 ; gateway:192.168.1.1\nfd00::/64, 172.16.0.0/12"
        )
        assertTrue(valid is ValidationResult.Valid)
        assertEquals(5, (valid as ValidationResult.Valid).rules.size)

        val tooMany = SmartAutoStopMatcher.validateRules(
            "10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5,10.0.0.6"
        )
        assertTrue(tooMany is ValidationResult.TooManyRules)
        assertEquals(6, (tooMany as ValidationResult.TooManyRules).count)

        val invalid = SmartAutoStopMatcher.validateRules("192.168.1.0/33")
        assertTrue(invalid is ValidationResult.InvalidRule)
        assertEquals("192.168.1.0/33", (invalid as ValidationResult.InvalidRule).rule)

        val invalidHostname = SmartAutoStopMatcher.validateRules("office-wifi.ssid")
        assertTrue(invalidHostname is ValidationResult.InvalidRule)
    }

    @Test
    fun networkKeyIsOrderIndependentAndDeterministic() {
        val a = NetworkSnapshot(
            ipAddresses = listOf("192.168.1.10", "fd00::1", " 192.168.1.10 "),
            gatewayAddresses = emptyList(),
            hasPhysicalNetwork = true,
        )
        val b = NetworkSnapshot(
            ipAddresses = listOf("fd00::1", "192.168.1.10"),
            gatewayAddresses = listOf("192.168.1.1"),
            hasPhysicalNetwork = true,
        )
        assertEquals(a.networkKey, b.networkKey)
        assertEquals("192.168.1.10,fd00::1", a.networkKey)
    }
}
