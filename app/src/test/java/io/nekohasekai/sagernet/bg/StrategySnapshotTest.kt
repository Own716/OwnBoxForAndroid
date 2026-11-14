package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.StrategySnapshot.TimeBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategySnapshotTest {

    @Test
    fun parseReturnsNullOnEmptyMalformedOrInactiveJson() {
        assertNull(StrategySnapshot.parse(null))
        assertNull(StrategySnapshot.parse(""))
        assertNull(StrategySnapshot.parse("{not-json"))
        assertNull(StrategySnapshot.parse("""{"active":false,"groupTag":"proxy"}"""))
    }

    @Test
    fun parseExtractsActivePinnedAndUnavailableMembers() {
        val json = """
            {
              "active": true,
              "strategyKey": "balancer_42",
              "strategyKind": "balancer",
              "strategyId": 42,
              "editProfileId": 42,
              "editGroupId": 0,
              "groupName": "日本策略组",
              "groupTag": "balancer-42",
              "strategyType": "leastPing",
              "activeTag": "out-1",
              "activeProfileId": 101,
              "activeName": "Japan 01",
              "activeDelay": 120,
              "pinnedTag": "out-1",
              "pinnedProfileId": 101,
              "pinnedName": "Japan 01",
              "checking": false,
              "lastCheckMs": 1700000000000,
              "members": [
                {
                  "tag": "out-1",
                  "profileId": 101,
                  "name": "Japan 01",
                  "delay": 120,
                  "error": "",
                  "lastCheckMs": 1700000000000,
                  "active": true,
                  "pinned": true
                },
                {
                  "tag": "out-2",
                  "profileId": 102,
                  "name": "Japan 02",
                  "delay": 0,
                  "error": "context deadline exceeded",
                  "lastCheckMs": 1699999995000,
                  "active": false,
                  "pinned": false
                }
              ]
            }
        """.trimIndent()

        val snap = StrategySnapshot.parse(json)
        assertNotNull(snap)
        snap!!
        assertTrue(snap.active)
        assertTrue(snap.isPinned)
        assertTrue(snap.canEdit)
        assertEquals("balancer_42", snap.strategyKey)
        assertEquals("日本策略组", snap.groupName)
        assertEquals("leastPing", snap.strategyType)
        assertEquals(101L, snap.pinnedProfileId)
        assertEquals(120, snap.activeDelay)
        assertEquals(2, snap.members.size)

        val first = snap.members[0]
        assertTrue(first.active)
        assertTrue(first.pinned)
        assertFalse(first.isUnavailable)
        assertEquals(120, first.delay)

        val second = snap.members[1]
        assertFalse(second.active)
        assertFalse(second.pinned)
        assertTrue(second.isUnavailable)
        assertEquals("context deadline exceeded", second.error)
    }

    @Test
    fun computeRelativeTimeHandlesBoundaryBuckets() {
        val now = 1_700_000_000_000L

        assertEquals(TimeBucket.NEVER, StrategySnapshot.computeRelativeTime(0L, now).bucket)
        assertEquals(TimeBucket.NEVER, StrategySnapshot.computeRelativeTime(-1L, now).bucket)

        // Future timestamp clamps to 0s -> JUST_NOW
        assertEquals(TimeBucket.JUST_NOW, StrategySnapshot.computeRelativeTime(now + 5_000L, now).bucket)
        assertEquals(TimeBucket.JUST_NOW, StrategySnapshot.computeRelativeTime(now, now).bucket)
        assertEquals(TimeBucket.JUST_NOW, StrategySnapshot.computeRelativeTime(now - 9_999L, now).bucket)

        val sec10 = StrategySnapshot.computeRelativeTime(now - 10_000L, now)
        assertEquals(TimeBucket.SECONDS, sec10.bucket)
        assertEquals(10, sec10.amount)

        val sec59 = StrategySnapshot.computeRelativeTime(now - 59_999L, now)
        assertEquals(TimeBucket.SECONDS, sec59.bucket)
        assertEquals(59, sec59.amount)

        val min1 = StrategySnapshot.computeRelativeTime(now - 60_000L, now)
        assertEquals(TimeBucket.MINUTES, min1.bucket)
        assertEquals(1, min1.amount)

        val min59 = StrategySnapshot.computeRelativeTime(now - 3_599_000L, now)
        assertEquals(TimeBucket.MINUTES, min59.bucket)
        assertEquals(59, min59.amount)

        val hr1 = StrategySnapshot.computeRelativeTime(now - 3_600_000L, now)
        assertEquals(TimeBucket.HOURS, hr1.bucket)
        assertEquals(1, hr1.amount)

        val hr23 = StrategySnapshot.computeRelativeTime(now - 86_399_000L, now)
        assertEquals(TimeBucket.HOURS, hr23.bucket)
        assertEquals(23, hr23.amount)

        val day1 = StrategySnapshot.computeRelativeTime(now - 86_400_000L, now)
        assertEquals(TimeBucket.DAYS, day1.bucket)
        assertEquals(1, day1.amount)

        val day7 = StrategySnapshot.computeRelativeTime(now - 7 * 86_400_000L, now)
        assertEquals(TimeBucket.DAYS, day7.bucket)
        assertEquals(7, day7.amount)
    }
}
