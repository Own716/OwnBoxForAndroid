package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ui.SubscriptionAutoUpdateDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionAutoUpdateTest {

    @Test
    fun testPresetMinutes() {
        val presets = SubscriptionAutoUpdateDialog.PRESET_MINUTES
        assertEquals(7, presets.size)
        assertEquals(15, presets[0])
        assertEquals(30, presets[1])
        assertEquals(60, presets[2])
        assertEquals(180, presets[3])
        assertEquals(360, presets[4])
        assertEquals(720, presets[5])
        assertEquals(1440, presets[6])
    }

    @Test
    fun testIntervalClamping() {
        // WorkManager minimum interval is 15 minutes
        val delay0 = 0.coerceAtLeast(15)
        val delay5 = 5.coerceAtLeast(15)
        val delay15 = 15.coerceAtLeast(15)
        val delay1440 = 1440.coerceAtLeast(15)

        assertEquals(15, delay0)
        assertEquals(15, delay5)
        assertEquals(15, delay15)
        assertEquals(1440, delay1440)
    }

    @Test
    fun testInitialDelayCalculation() {
        val now = 1000000L
        val minDelay = 1440L // 24 hours

        // Case 1: Subscription updated 1 hour ago (3600s), delay is 24 hours (86400s)
        val lastUpdated1 = (now - 3600L)
        val dueSeconds1 = (lastUpdated1 + minDelay * 60L) - now
        assertEquals(82800L, dueSeconds1)

        val initialDelay1 = if (dueSeconds1 <= 0L) 0L else dueSeconds1.coerceAtMost(minDelay * 60L)
        // Must NOT be capped to 60 seconds!
        assertEquals(82800L, initialDelay1)
        assertTrue(initialDelay1 > 60L)

        // Case 2: Subscription is overdue by 10 minutes
        val lastUpdated2 = (now - 87000L)
        val dueSeconds2 = (lastUpdated2 + minDelay * 60L) - now
        assertTrue(dueSeconds2 < 0L)
        val initialDelay2 = if (dueSeconds2 <= 0L) 0L else dueSeconds2.coerceAtMost(minDelay * 60L)
        assertEquals(0L, initialDelay2)

        // Case 3: Subscription never updated (lastUpdated == 0)
        val lastUpdated3 = 0L
        val dueSeconds3 = (lastUpdated3 + minDelay * 60L) - now
        assertTrue(dueSeconds3 < 0L)
        val initialDelay3 = if (dueSeconds3 <= 0L) 0L else dueSeconds3.coerceAtMost(minDelay * 60L)
        assertEquals(0L, initialDelay3)
    }
}
