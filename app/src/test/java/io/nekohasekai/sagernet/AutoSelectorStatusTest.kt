package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorBuild
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorMemberStatus
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSelectorStatusTest {

    @Test
    fun parseHandlesIdleAndObjectAndArrayJson() {
        assertEquals(AutoSelectorStatus.IDLE, AutoSelectorStatus.parse(null))
        assertEquals(AutoSelectorStatus.IDLE, AutoSelectorStatus.parse(""))
        assertEquals(AutoSelectorStatus.IDLE, AutoSelectorStatus.parse("[]"))

        val build = AutoSelectorBuild(
            selectorId = 77L,
            selectorName = "(日本🇯🇵) 策略组",
            groupTag = "proxy",
            members = mapOf("proxy-101" to 101L, "proxy-102" to 102L),
            memberIds = listOf(101L, 102L),
            trackedGroups = emptyList(),
            testUrl = "http://cp.cloudflare.com/",
            balance = false,
            poolCap = 1000,
            buildLimit = 100,
            resultValidityMins = 1440,
        )

        val rawArray = """
            [
              {
                "tag": "proxy",
                "phase": "ready",
                "selected": "proxy-101",
                "selected_udp": "proxy-101",
                "pinned": "proxy-102",
                "balance": false,
                "balance_mode": "rotate",
                "suspended": false,
                "members_total": 2,
                "members_probed": 2,
                "members_alive": 1,
                "members_qualified": 1,
                "members_cooldown": 1,
                "probes_in_flight": 0,
                "rounds_completed": 3,
                "last_round_ms": 1700000100000,
                "next_round_ms": 1700000400000,
                "last_switch_ms": 1700000050000,
                "last_switch_reason": "faster",
                "members": [
                  {
                    "tag": "proxy-101",
                    "rank": 1,
                    "state": "ok",
                    "selected": true,
                    "qualified": true,
                    "average_ms": 120,
                    "deviation_ms": 8,
                    "min_ms": 110,
                    "max_ms": 135,
                    "samples": 5,
                    "failures": 0,
                    "probes": 5,
                    "dial_total": 12,
                    "dial_fail": 0,
                    "last_ok_ms": 1700000100000,
                    "last_probe_ms": 1700000100000
                  },
                  {
                    "tag": "proxy-102",
                    "rank": 2,
                    "state": "cooldown",
                    "cooldown_until_ms": 1700000150000,
                    "last_error": "EOF"
                  }
                ]
              }
            ]
        """.trimIndent()

        val parsed = AutoSelectorStatus.parse(rawArray, build)
        assertTrue(parsed.running)
        assertEquals(77L, parsed.selectorId)
        assertEquals("(日本🇯🇵) 策略组", parsed.selectorName)
        assertEquals("proxy", parsed.tag)
        assertEquals(101L, parsed.selectedId)
        assertEquals(102L, parsed.pinnedId)
        assertEquals(2, parsed.members.size)
        assertEquals(101L, parsed.members[0].profileId)
        assertEquals(120, parsed.members[0].averageMs)
        assertEquals("EOF", parsed.members[1].lastError)
    }

    @Test
    fun statusToJsonRoundTripsAllFields() {
        val status = AutoSelectorStatus(
            running = true,
            selectorId = 55L,
            selectorName = "JP Strategy",
            tag = "proxy",
            phase = "ready",
            selectedTag = "proxy-9",
            selectedId = 9L,
            pinnedTag = "proxy-9",
            pinnedId = 9L,
            balance = true,
            balanceMode = "connection",
            membersTotal = 1,
            membersProbed = 1,
            membersAlive = 1,
            membersQualified = 1,
            rankingActive = true,
            rankingDone = 2,
            rankingTotal = 5,
            exhausted = false,
            members = listOf(
                AutoSelectorMemberStatus(
                    tag = "proxy-9",
                    profileId = 9L,
                    rank = 1,
                    state = "ok",
                    selected = true,
                    qualified = true,
                    averageMs = 95,
                    probes = 4,
                    dialTotal = 8,
                )
            ),
        )
        val roundTripped = AutoSelectorStatus.parse(status.toJson(withMembers = true))
        assertEquals(status, roundTripped)

        val noMembers = AutoSelectorStatus.parse(status.toJson(withMembers = false))
        assertFalse(noMembers.members.isNotEmpty())
        assertEquals(55L, noMembers.selectorId)
        assertEquals(9L, noMembers.selectedId)
        assertTrue(noMembers.rankingActive)
    }
}
