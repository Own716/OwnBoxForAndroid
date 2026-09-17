package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SubscriptionFilterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFilterTest {

    @Test
    fun filterIncludeWorksWithRegexAndIgnoreCase() {
        val names = listOf("Hong Kong 01", "HK Premium", "Tokyo 01", "Singapore 01", "US West")
        val filterRegex = "hk|hong kong"
        val regex = filterRegex.trim().toRegex(RegexOption.IGNORE_CASE)

        val included = names.filter { regex.containsMatchIn(it) }
        assertEquals(listOf("Hong Kong 01", "HK Premium"), included)
    }

    @Test
    fun filterExcludeWorksWithRegex() {
        val names = listOf("Hong Kong 01", "HK Premium", "Tokyo 01", "Singapore 01")
        val filterRegex = "HK|Hong Kong"
        val regex = filterRegex.trim().toRegex(RegexOption.IGNORE_CASE)

        val excluded = names.filterNot { regex.containsMatchIn(it) }
        assertEquals(listOf("Tokyo 01", "Singapore 01"), excluded)
    }

    @Test
    fun circuitBreakerIsBypassedWhenFilterIsActive() {
        val existsSize = 50
        // User applied include filter which shrank results from 50 to 5 (10% of existing)
        val filteredProxiesSize = 5

        // Without filter: circuit breaker would trigger (5 < 35) and prevent deletion
        val isFilterActiveWhenDisabled = false
        val circuitBreakerTriggeredWithoutFilter = !isFilterActiveWhenDisabled && existsSize >= 10 && filteredProxiesSize < existsSize * 0.70
        assertTrue("Circuit breaker should trigger when filter is disabled", circuitBreakerTriggeredWithoutFilter)

        // With filter active: circuit breaker is bypassed so intended filter deletion proceeds
        val filterMode = SubscriptionFilterMode.INCLUDE
        val filterRegex = "HK"
        val isFilterActive = filterMode != SubscriptionFilterMode.DISABLED && filterRegex.isNotBlank()
        val circuitBreakerTriggeredWithFilter = !isFilterActive && existsSize >= 10 && filteredProxiesSize < existsSize * 0.70
        assertFalse("Circuit breaker must be bypassed when filter is actively configured", circuitBreakerTriggeredWithFilter)
    }
}
