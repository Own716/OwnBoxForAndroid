package io.nekohasekai.sagernet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestDisplayModeContractTest {

    data class DisplayDecision(
        val showSpeed: Boolean,
        val showPing: Boolean,
    )

    /**
     * Models the decision logic implemented in ConfigurationFragment.bindTestResult
     */
    private fun evaluateDisplay(
        isCompact: Boolean,
        hasSpeedResult: Boolean,
        hasPingResult: Boolean,
        speedTs: Long,
        pingTs: Long,
    ): DisplayDecision {
        if (isCompact) {
            // In compact mode: exactly one result is displayed based on which test ran last
            val showSpeed = hasSpeedResult && (speedTs >= pingTs || !hasPingResult)
            val showPing = !showSpeed && hasPingResult
            return DisplayDecision(showSpeed = showSpeed, showPing = showPing)
        } else {
            // In detailed / non-compact mode: all available test results are displayed simultaneously
            return DisplayDecision(showSpeed = hasSpeedResult, showPing = hasPingResult)
        }
    }

    @Test
    fun testCompactModeInitialEmpty() {
        // 1. Initial state: neither tested
        val decision = evaluateDisplay(
            isCompact = true,
            hasSpeedResult = false,
            hasPingResult = false,
            speedTs = 0L,
            pingTs = 0L,
        )
        assertFalse(decision.showSpeed)
        assertFalse(decision.showPing)
    }

    @Test
    fun testCompactModePingThenSpeedThenPingReplacement() {
        // Step 1: User runs URL/TCP test first
        val step1 = evaluateDisplay(
            isCompact = true,
            hasSpeedResult = false,
            hasPingResult = true,
            speedTs = 0L,
            pingTs = 1000L,
        )
        assertFalse(step1.showSpeed)
        assertTrue(step1.showPing)

        // Step 2: User runs speed test next -> speed replaces ping
        val step2 = evaluateDisplay(
            isCompact = true,
            hasSpeedResult = true,
            hasPingResult = true,
            speedTs = 2000L, // Speed tested at t=2000 > ping at t=1000
            pingTs = 1000L,
        )
        assertTrue(step2.showSpeed)
        assertFalse(step2.showPing)

        // Step 3: User runs URL/TCP test again -> ping replaces speed
        val step3 = evaluateDisplay(
            isCompact = true,
            hasSpeedResult = true,
            hasPingResult = true,
            speedTs = 2000L,
            pingTs = 3000L, // Ping tested at t=3000 > speed at t=2000
        )
        assertFalse(step3.showSpeed)
        assertTrue(step3.showPing)
    }

    @Test
    fun testDetailedModeSimultaneousDisplay() {
        // In detailed mode: both results display together
        val stepBoth = evaluateDisplay(
            isCompact = false,
            hasSpeedResult = true,
            hasPingResult = true,
            speedTs = 2000L,
            pingTs = 1000L,
        )
        assertTrue(stepBoth.showSpeed)
        assertTrue(stepBoth.showPing)

        // Only ping tested
        val stepPingOnly = evaluateDisplay(
            isCompact = false,
            hasSpeedResult = false,
            hasPingResult = true,
            speedTs = 0L,
            pingTs = 1000L,
        )
        assertFalse(stepPingOnly.showSpeed)
        assertTrue(stepPingOnly.showPing)

        // Only speed tested
        val stepSpeedOnly = evaluateDisplay(
            isCompact = false,
            hasSpeedResult = true,
            hasPingResult = false,
            speedTs = 2000L,
            pingTs = 0L,
        )
        assertTrue(stepSpeedOnly.showSpeed)
        assertFalse(stepSpeedOnly.showPing)
    }
}
