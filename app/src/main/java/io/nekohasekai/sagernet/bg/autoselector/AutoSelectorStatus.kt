package io.nekohasekai.sagernet.bg.autoselector

import org.json.JSONArray
import org.json.JSONObject

data class AutoSelectorStatus(
    val phase: String = PHASE_IDLE,
    val selectorId: Long = -1,
    val selectorName: String = "",
    val measuring: Int = 0,
    val corePhase: String = "",
    val selectedId: Long = -1,
    val selectedName: String = "",
    val pinnedId: Long = -1,
    val lastSwitchMs: Long = 0,
    val lastSwitchReason: String = "",
    val membersTotal: Int = 0,
    val suspended: Boolean = false,
    val membersProbed: Int = 0,
    val membersAlive: Int = 0,
    val membersQualified: Int = 0,
    val membersCooldown: Int = 0,
    val probesInFlight: Int = 0,
    val roundsCompleted: Int = 0,
    val lastRoundMs: Long = 0,
    val nextRoundMs: Long = 0,
    val balance: Boolean = false,
    val exhaustedSinceMs: Long = 0,
    val members: List<Member> = emptyList(),
    val notice: String = "",
    val noticeAtMs: Long = 0,
) {

    data class Member(
        val id: Long = -1,
        val tag: String = "",
        val name: String = "",
        val selected: Boolean = false,
        val selectedUdp: Boolean = false,
        val state: String = "",
        val rank: Int = 0,
        val qualified: Boolean = false,
        val active: Boolean = false,
        val averageMs: Int = 0,
        val deviationMs: Int = 0,
        val minMs: Int = 0,
        val maxMs: Int = 0,
        val samples: Int = 0,
        val failures: Int = 0,
        val probes: Int = 0,
        val dialTotal: Int = 0,
        val dialFail: Int = 0,
        val lastOkMs: Long = 0,
        val lastProbeMs: Long = 0,
        val cooldownUntilMs: Long = 0,
        val lastError: String = "",
    ) {
        val isDead: Boolean get() = state == STATE_DEAD
        val isUsable: Boolean get() = state == STATE_OK || state == STATE_DEGRADED
        val hasProblem: Boolean get() = state == STATE_DEAD || state == STATE_COOLDOWN || state == STATE_DEGRADED

        fun toJson(): JSONObject {
            val json = JSONObject()
            json.put("id", id)
            json.put("tag", tag)
            json.put("name", name)
            json.put("selected", selected)
            json.put("selected_udp", selectedUdp)
            json.put("state", state)
            json.put("rank", rank)
            json.put("qualified", qualified)
            json.put("active", active)
            json.put("average_ms", averageMs)
            json.put("deviation_ms", deviationMs)
            json.put("min_ms", minMs)
            json.put("max_ms", maxMs)
            json.put("samples", samples)
            json.put("failures", failures)
            json.put("probes", probes)
            json.put("dial_total", dialTotal)
            json.put("dial_fail", dialFail)
            json.put("last_ok_ms", lastOkMs)
            json.put("last_probe_ms", lastProbeMs)
            json.put("cooldown_until_ms", cooldownUntilMs)
            json.put("last_error", lastError)
            return json
        }

        companion object {
            fun fromJson(obj: JSONObject): Member = Member(
                id = obj.optLong("id", -1L),
                tag = obj.optString("tag", ""),
                name = obj.optString("name", ""),
                selected = obj.optBoolean("selected", false),
                selectedUdp = obj.optBoolean("selected_udp", false),
                state = obj.optString("state", ""),
                rank = obj.optInt("rank", 0),
                qualified = obj.optBoolean("qualified", false),
                active = obj.optBoolean("active", false),
                averageMs = obj.optInt("average_ms", 0),
                deviationMs = obj.optInt("deviation_ms", 0),
                minMs = obj.optInt("min_ms", 0),
                maxMs = obj.optInt("max_ms", 0),
                samples = obj.optInt("samples", 0),
                failures = obj.optInt("failures", 0),
                probes = obj.optInt("probes", 0),
                dialTotal = obj.optInt("dial_total", 0),
                dialFail = obj.optInt("dial_fail", 0),
                lastOkMs = obj.optLong("last_ok_ms", 0L),
                lastProbeMs = obj.optLong("last_probe_ms", 0L),
                cooldownUntilMs = obj.optLong("cooldown_until_ms", 0L),
                lastError = obj.optString("last_error", ""),
            )
        }
    }

    val active: Boolean get() = phase != PHASE_IDLE

    fun member(id: Long): Member? = members.firstOrNull { it.id == id }

    fun toJson(): String {
        val json = JSONObject()
        json.put("phase", phase)
        json.put("selector_id", selectorId)
        json.put("selector_name", selectorName)
        json.put("measuring", measuring)
        json.put("core_phase", corePhase)
        json.put("selected_id", selectedId)
        json.put("selected_name", selectedName)
        json.put("pinned_id", pinnedId)
        json.put("last_switch_ms", lastSwitchMs)
        json.put("last_switch_reason", lastSwitchReason)
        json.put("members_total", membersTotal)
        json.put("suspended", suspended)
        json.put("members_probed", membersProbed)
        json.put("members_alive", membersAlive)
        json.put("members_qualified", membersQualified)
        json.put("members_cooldown", membersCooldown)
        json.put("probes_in_flight", probesInFlight)
        json.put("rounds_completed", roundsCompleted)
        json.put("last_round_ms", lastRoundMs)
        json.put("next_round_ms", nextRoundMs)
        json.put("balance", balance)
        json.put("exhausted_since_ms", exhaustedSinceMs)
        json.put("notice", notice)
        json.put("notice_at_ms", noticeAtMs)

        val array = JSONArray()
        members.forEach { array.put(it.toJson()) }
        json.put("members", array)
        return json.toString()
    }

    companion object {
        const val PHASE_IDLE = "idle"
        const val PHASE_MEASURING = "measuring"
        const val PHASE_RUNNING = "running"

        const val STATE_OK = "ok"
        const val STATE_DEGRADED = "degraded"
        const val STATE_UNTESTED = "untested"
        const val STATE_DEAD = "dead"
        const val STATE_COOLDOWN = "cooldown"

        @JvmField
        val IDLE = AutoSelectorStatus()

        @JvmStatic
        fun parse(json: String?): AutoSelectorStatus {
            if (json.isNullOrBlank()) return IDLE
            return try {
                val obj = JSONObject(json)
                val mArray = obj.optJSONArray("members")
                val mList = ArrayList<Member>()
                if (mArray != null) {
                    for (i in 0 until mArray.length()) {
                        val mObj = mArray.optJSONObject(i)
                        if (mObj != null) mList.add(Member.fromJson(mObj))
                    }
                }
                AutoSelectorStatus(
                    phase = obj.optString("phase", PHASE_IDLE),
                    selectorId = obj.optLong("selector_id", -1L),
                    selectorName = obj.optString("selector_name", ""),
                    measuring = obj.optInt("measuring", 0),
                    corePhase = obj.optString("core_phase", ""),
                    selectedId = obj.optLong("selected_id", -1L),
                    selectedName = obj.optString("selected_name", ""),
                    pinnedId = obj.optLong("pinned_id", -1L),
                    lastSwitchMs = obj.optLong("last_switch_ms", 0L),
                    lastSwitchReason = obj.optString("last_switch_reason", ""),
                    membersTotal = obj.optInt("members_total", 0),
                    suspended = obj.optBoolean("suspended", false),
                    membersProbed = obj.optInt("members_probed", 0),
                    membersAlive = obj.optInt("members_alive", 0),
                    membersQualified = obj.optInt("members_qualified", 0),
                    membersCooldown = obj.optInt("members_cooldown", 0),
                    probesInFlight = obj.optInt("probes_in_flight", 0),
                    roundsCompleted = obj.optInt("rounds_completed", 0),
                    lastRoundMs = obj.optLong("last_round_ms", 0L),
                    nextRoundMs = obj.optLong("next_round_ms", 0L),
                    balance = obj.optBoolean("balance", false),
                    exhaustedSinceMs = obj.optLong("exhausted_since_ms", 0L),
                    members = mList,
                    notice = obj.optString("notice", ""),
                    noticeAtMs = obj.optLong("notice_at_ms", 0L),
                )
            } catch (e: Exception) {
                IDLE
            }
        }
    }
}
