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

    @Test
    fun parseExtractsAutoSelectMetadataRolesAndStats() {
        val json = """
            {
              "active": true,
              "strategyKey": "balancer_9",
              "strategyKind": "balancer",
              "strategyId": 9,
              "editProfileId": 9,
              "editGroupId": 0,
              "groupName": "SG Auto",
              "groupTag": "balancer-9",
              "strategyType": "autoselect",
              "selectionMode": "latency",
              "balanceEnabled": true,
              "availableCount": 3,
              "totalCount": 5,
              "lastSwitchMs": 1700000100000,
              "switchReason": "best_available",
              "nextCheckSec": 205,
              "learnedSiteCount": 18,
              "learnEnabled": true,
              "localOffline": false,
              "activeTag": "out-1",
              "activeProfileId": 101,
              "activeName": "SG 01",
              "activeDelay": 320,
              "pinnedTag": "",
              "pinnedProfileId": 0,
              "pinnedName": "",
              "checking": false,
              "lastCheckMs": 1700000200000,
              "members": [
                {
                  "tag": "out-1",
                  "profileId": 101,
                  "name": "SG 01",
                  "delay": 320,
                  "error": "",
                  "lastCheckMs": 1700000200000,
                  "active": true,
                  "pinned": false,
                  "role": "active",
                  "checkCount": 8,
                  "failCount": 0
                },
                {
                  "tag": "out-2",
                  "profileId": 102,
                  "name": "SG 02",
                  "delay": 506,
                  "error": "",
                  "lastCheckMs": 1700000190000,
                  "active": false,
                  "pinned": false,
                  "role": "standby",
                  "checkCount": 8,
                  "failCount": 0
                },
                {
                  "tag": "out-3",
                  "profileId": 103,
                  "name": "SG 03",
                  "delay": 523,
                  "error": "",
                  "lastCheckMs": 1700000180000,
                  "active": false,
                  "pinned": false,
                  "role": "reserve",
                  "checkCount": 8,
                  "failCount": 1
                },
                {
                  "tag": "out-4",
                  "profileId": 104,
                  "name": "SG 04",
                  "delay": 490,
                  "error": "",
                  "lastCheckMs": 1700000170000,
                  "active": false,
                  "pinned": false,
                  "role": "reserve",
                  "checkCount": 8,
                  "failCount": 0
                },
                {
                  "tag": "out-5",
                  "profileId": 105,
                  "name": "SG 05",
                  "delay": 0,
                  "error": "passive suspect cooldown",
                  "lastCheckMs": 1700000160000,
                  "active": false,
                  "pinned": false,
                  "role": "suspect",
                  "checkCount": 4,
                  "failCount": 3,
                  "suspectUntilMs": 1700000260000,
                  "suspectRemainingSec": 45
                }
              ]
            }
        """.trimIndent()

        val snap = StrategySnapshot.parse(json)!!
        assertEquals("autoselect", snap.strategyType)
        assertEquals("latency", snap.selectionMode)
        assertTrue(snap.balanceEnabled)
        assertEquals(3, snap.availableCount)
        assertEquals(5, snap.totalCount)
        assertEquals(1700000100000L, snap.lastSwitchMs)
        assertEquals("best_available", snap.switchReason)
        assertEquals(205, snap.nextCheckSec)
        assertEquals(18, snap.learnedSiteCount)
        assertTrue(snap.learnEnabled)
        assertFalse(snap.localOffline)

        assertEquals(StrategySnapshot.MemberSubtitleKind.CARRYING_TRAFFIC, StrategySnapshot.resolveMemberSubtitleKind(snap.members[0]))
        assertEquals(StrategySnapshot.MemberSubtitleKind.STANDBY, StrategySnapshot.resolveMemberSubtitleKind(snap.members[1]))
        assertEquals(StrategySnapshot.MemberSubtitleKind.NONE, StrategySnapshot.resolveMemberSubtitleKind(snap.members[2]))
        assertFalse(StrategySnapshot.shouldShowPartialFailureWarning(snap.members[2]))
        assertEquals(StrategySnapshot.MemberSubtitleKind.NONE, StrategySnapshot.resolveMemberSubtitleKind(snap.members[3]))
        assertEquals(StrategySnapshot.MemberSubtitleKind.SUSPECT, StrategySnapshot.resolveMemberSubtitleKind(snap.members[4]))
        assertEquals(45, snap.members[4].suspectRemainingSec)
        assertEquals(-2L, StrategySnapshotHelper.ACTION_CLEAR_LEARNED)
        assertEquals(":clear_learned", StrategySnapshot.CLEAR_LEARNED_TAG)
    }

    @Test
    fun partialFailureWarningRequiresRepeatedFailures() {
        val singleBlip = StrategyMemberSnapshot(
            tag = "out-1", profileId = 1, name = "N1", delay = 100, error = "", lastCheckMs = 0L,
            active = false, pinned = false, role = "reserve", checkCount = 8, failCount = 1,
        )
        assertFalse(StrategySnapshot.shouldShowPartialFailureWarning(singleBlip))
        assertEquals(StrategySnapshot.MemberSubtitleKind.NONE, StrategySnapshot.resolveMemberSubtitleKind(singleBlip))

        val lowRatioTwoFails = singleBlip.copy(checkCount = 10, failCount = 2)
        assertFalse(StrategySnapshot.shouldShowPartialFailureWarning(lowRatioTwoFails))

        val unstable = singleBlip.copy(checkCount = 8, failCount = 3)
        assertTrue(StrategySnapshot.shouldShowPartialFailureWarning(unstable))
        assertEquals(StrategySnapshot.MemberSubtitleKind.PARTIAL_FAILURES, StrategySnapshot.resolveMemberSubtitleKind(unstable))
    }

    @Test
    fun sortMembersForDisplaySinksUnavailableNodesPreservingRelativeOrder() {
        val failedA = StrategyMemberSnapshot(
            tag = "out-a", profileId = 1, name = "Failed A", delay = 0, error = "i/o timeout", lastCheckMs = 0L,
            active = false, pinned = false, role = "failed",
        )
        val standby1 = StrategyMemberSnapshot(
            tag = "out-b", profileId = 2, name = "Standby 1", delay = 180, error = "", lastCheckMs = 0L,
            active = false, pinned = false, role = "standby",
        )
        val pinnedActive = StrategyMemberSnapshot(
            tag = "out-c", profileId = 3, name = "Pinned Active", delay = 220, error = "", lastCheckMs = 0L,
            active = true, pinned = true, role = "active",
        )
        val failedB = StrategyMemberSnapshot(
            tag = "out-d", profileId = 4, name = "Failed B", delay = 0, error = "connection refused", lastCheckMs = 0L,
            active = false, pinned = false, role = "failed",
        )
        val standby2 = StrategyMemberSnapshot(
            tag = "out-e", profileId = 5, name = "Standby 2", delay = 150, error = "", lastCheckMs = 0L,
            active = false, pinned = false, role = "standby",
        )

        val sorted = StrategySnapshot.sortMembersForDisplay(listOf(failedA, standby1, pinnedActive, failedB, standby2))
        assertEquals(
            listOf("out-c", "out-b", "out-e", "out-a", "out-d"),
            sorted.map { it.tag },
        )
    }

    @Test
    fun classifyErrorMapsWrappedGoErrorsBeforeGenericProbeWrapper() {
        assertEquals(StrategySnapshot.ErrorKind.NONE, StrategySnapshot.classifyError("   "))
        assertEquals(
            StrategySnapshot.ErrorKind.TIMEOUT,
            StrategySnapshot.classifyError("Get \"https://www.gstatic.com/generate_204\": dial tcp 1.2.3.4:443: i/o timeout"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.TIMEOUT,
            StrategySnapshot.classifyError("context deadline exceeded"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.REFUSED,
            StrategySnapshot.classifyError("dial tcp 127.0.0.1:443: connect: connection refused"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.RESET,
            StrategySnapshot.classifyError("read tcp 10.0.0.2:12345->1.2.3.4:443: read: connection reset by peer"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.TLS,
            StrategySnapshot.classifyError("Get \"https://example.com\": tls: failed to verify certificate: x509: certificate signed by unknown authority"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.DNS,
            StrategySnapshot.classifyError("Get \"https://example.com\": dial tcp: lookup example.com: no such host"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.UNREACHABLE,
            StrategySnapshot.classifyError("connect: network is unreachable"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.EOF,
            StrategySnapshot.classifyError("Get \"https://example.com\": unexpected EOF"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.PROBE_FAILED,
            StrategySnapshot.classifyError("probe failed: unexpected status 502"),
        )
        assertEquals(
            StrategySnapshot.ErrorKind.UNKNOWN,
            StrategySnapshot.classifyError("custom backend error"),
        )
    }
}
