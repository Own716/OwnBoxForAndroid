package io.nekohasekai.sagernet.bg.autoselector

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parsed runtime snapshot of an active (or recently stopped) AutoSelector group.
 * Combines Go core status fields (`AutoSelectorStatus` / `AutoSelectorMemberStatus`)
 * with Android runtime ranking / exhaustion metadata using Gson so JVM tests work without mocks.
 */
data class AutoSelectorMemberStatus(
    val tag: String,
    val profileId: Long = 0L,
    val rank: Int = 0,
    val state: String = "untested",
    val selected: Boolean = false,
    val selectedUdp: Boolean = false,
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
    val lastOkMs: Long = 0L,
    val lastProbeMs: Long = 0L,
    val cooldownUntilMs: Long = 0L,
    val lastError: String = "",
)

data class AutoSelectorStatus(
    val running: Boolean = false,
    val selectorId: Long = 0L,
    val selectorName: String = "",
    val tag: String = "",
    val phase: String = "stopped",
    val selectedTag: String = "",
    val selectedId: Long = 0L,
    val selectedUdpTag: String = "",
    val selectedUdpId: Long = 0L,
    val pinnedTag: String = "",
    val pinnedId: Long = 0L,
    val balance: Boolean = false,
    val balanceMode: String = "",
    val suspended: Boolean = false,
    val suspendedSinceMs: Long = 0L,
    val membersTotal: Int = 0,
    val membersProbed: Int = 0,
    val membersAlive: Int = 0,
    val membersQualified: Int = 0,
    val membersCooldown: Int = 0,
    val probesInFlight: Int = 0,
    val roundsCompleted: Int = 0,
    val lastRoundMs: Long = 0L,
    val nextRoundMs: Long = 0L,
    val lastSwitchMs: Long = 0L,
    val lastSwitchReason: String = "",
    val rankingTotal: Int = 0,
    val rankingDone: Int = 0,
    val rankingActive: Boolean = false,
    val exhausted: Boolean = false,
    val members: List<AutoSelectorMemberStatus> = emptyList(),
) {

    fun toJson(withMembers: Boolean = true): String {
        val obj = JsonObject()
        obj.addProperty("running", running)
        obj.addProperty("selector_id", selectorId)
        obj.addProperty("selector_name", selectorName)
        obj.addProperty("tag", tag)
        obj.addProperty("phase", phase)
        obj.addProperty("selected", selectedTag)
        obj.addProperty("selected_id", selectedId)
        obj.addProperty("selected_udp", selectedUdpTag)
        obj.addProperty("selected_udp_id", selectedUdpId)
        obj.addProperty("pinned", pinnedTag)
        obj.addProperty("pinned_id", pinnedId)
        obj.addProperty("balance", balance)
        obj.addProperty("balance_mode", balanceMode)
        obj.addProperty("suspended", suspended)
        obj.addProperty("suspended_since_ms", suspendedSinceMs)
        obj.addProperty("members_total", membersTotal)
        obj.addProperty("members_probed", membersProbed)
        obj.addProperty("members_alive", membersAlive)
        obj.addProperty("members_qualified", membersQualified)
        obj.addProperty("members_cooldown", membersCooldown)
        obj.addProperty("probes_in_flight", probesInFlight)
        obj.addProperty("rounds_completed", roundsCompleted)
        obj.addProperty("last_round_ms", lastRoundMs)
        obj.addProperty("next_round_ms", nextRoundMs)
        obj.addProperty("last_switch_ms", lastSwitchMs)
        obj.addProperty("last_switch_reason", lastSwitchReason)
        obj.addProperty("ranking_total", rankingTotal)
        obj.addProperty("ranking_done", rankingDone)
        obj.addProperty("ranking_active", rankingActive)
        obj.addProperty("exhausted", exhausted)
        if (withMembers) {
            val arr = JsonArray()
            for (m in members) {
                val mObj = JsonObject()
                mObj.addProperty("tag", m.tag)
                mObj.addProperty("profile_id", m.profileId)
                mObj.addProperty("rank", m.rank)
                mObj.addProperty("state", m.state)
                mObj.addProperty("selected", m.selected)
                mObj.addProperty("selected_udp", m.selectedUdp)
                mObj.addProperty("qualified", m.qualified)
                mObj.addProperty("active", m.active)
                mObj.addProperty("average_ms", m.averageMs)
                mObj.addProperty("deviation_ms", m.deviationMs)
                mObj.addProperty("min_ms", m.minMs)
                mObj.addProperty("max_ms", m.maxMs)
                mObj.addProperty("samples", m.samples)
                mObj.addProperty("failures", m.failures)
                mObj.addProperty("probes", m.probes)
                mObj.addProperty("dial_total", m.dialTotal)
                mObj.addProperty("dial_fail", m.dialFail)
                mObj.addProperty("last_ok_ms", m.lastOkMs)
                mObj.addProperty("last_probe_ms", m.lastProbeMs)
                mObj.addProperty("cooldown_until_ms", m.cooldownUntilMs)
                mObj.addProperty("last_error", m.lastError)
                arr.add(mObj)
            }
            obj.add("members", arr)
        }
        return obj.toString()
    }

    companion object {
        val IDLE = AutoSelectorStatus()

        fun parse(rawJson: String?, build: AutoSelectorBuild? = null): AutoSelectorStatus {
            val trimmed = rawJson?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed == "[]") return IDLE
            return runCatching {
                val elem = JsonParser.parseString(trimmed)
                val obj = when {
                    elem.isJsonObject -> elem.asJsonObject
                    elem.isJsonArray -> {
                        val arr = elem.asJsonArray
                        if (arr.size() == 0) return IDLE
                        val desiredTag = build?.groupTag
                        val matched = if (!desiredTag.isNullOrBlank()) {
                            arr.firstOrNull { it.isJsonObject && it.asJsonObject.str("tag") == desiredTag }
                        } else null
                        (matched ?: arr[0]).takeIf { it.isJsonObject }?.asJsonObject ?: return IDLE
                    }
                    else -> return IDLE
                }
                parseObject(obj, build)
            }.getOrDefault(IDLE)
        }

        private fun parseObject(obj: JsonObject, build: AutoSelectorBuild?): AutoSelectorStatus {
            val tag = obj.str("tag", build?.groupTag.orEmpty())
            val phase = obj.str("phase", "stopped")
            val running = obj.bool("running", phase != "stopped" && tag.isNotEmpty())
            val selectedTag = obj.str("selected")
            val selectedUdpTag = obj.str("selected_udp")
            val pinnedTag = obj.str("pinned")
            val tagMap = build?.members.orEmpty()

            val membersArr = obj.get("members")?.takeIf { it.isJsonArray }?.asJsonArray
            val members = ArrayList<AutoSelectorMemberStatus>(membersArr?.size() ?: 0)
            if (membersArr != null) {
                for (el in membersArr) {
                    if (!el.isJsonObject) continue
                    val m = el.asJsonObject
                    val mTag = m.str("tag")
                    val mId = m.long("profile_id", tagMap[mTag] ?: 0L)
                    members.add(
                        AutoSelectorMemberStatus(
                            tag = mTag,
                            profileId = mId,
                            rank = m.int("rank"),
                            state = m.str("state", "untested"),
                            selected = m.bool("selected"),
                            selectedUdp = m.bool("selected_udp"),
                            qualified = m.bool("qualified"),
                            active = m.bool("active"),
                            averageMs = m.int("average_ms"),
                            deviationMs = m.int("deviation_ms"),
                            minMs = m.int("min_ms"),
                            maxMs = m.int("max_ms"),
                            samples = m.int("samples"),
                            failures = m.int("failures"),
                            probes = m.int("probes"),
                            dialTotal = m.int("dial_total"),
                            dialFail = m.int("dial_fail"),
                            lastOkMs = m.long("last_ok_ms"),
                            lastProbeMs = m.long("last_probe_ms"),
                            cooldownUntilMs = m.long("cooldown_until_ms"),
                            lastError = m.str("last_error"),
                        )
                    )
                }
            }

            return AutoSelectorStatus(
                running = running,
                selectorId = obj.long("selector_id", build?.selectorId ?: 0L),
                selectorName = obj.str("selector_name", build?.selectorName.orEmpty()),
                tag = tag,
                phase = phase,
                selectedTag = selectedTag,
                selectedId = obj.long("selected_id", tagMap[selectedTag] ?: 0L),
                selectedUdpTag = selectedUdpTag,
                selectedUdpId = obj.long("selected_udp_id", tagMap[selectedUdpTag] ?: 0L),
                pinnedTag = pinnedTag,
                pinnedId = obj.long("pinned_id", tagMap[pinnedTag] ?: 0L),
                balance = obj.bool("balance", build?.balance ?: false),
                balanceMode = obj.str("balance_mode"),
                suspended = obj.bool("suspended"),
                suspendedSinceMs = obj.long("suspended_since_ms"),
                membersTotal = obj.int("members_total"),
                membersProbed = obj.int("members_probed"),
                membersAlive = obj.int("members_alive"),
                membersQualified = obj.int("members_qualified"),
                membersCooldown = obj.int("members_cooldown"),
                probesInFlight = obj.int("probes_in_flight"),
                roundsCompleted = obj.int("rounds_completed"),
                lastRoundMs = obj.long("last_round_ms"),
                nextRoundMs = obj.long("next_round_ms"),
                lastSwitchMs = obj.long("last_switch_ms"),
                lastSwitchReason = obj.str("last_switch_reason"),
                rankingTotal = obj.int("ranking_total"),
                rankingDone = obj.int("ranking_done"),
                rankingActive = obj.bool("ranking_active"),
                exhausted = obj.bool("exhausted"),
                members = members,
            )
        }

        private fun JsonObject.elem(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }
        private fun JsonObject.str(key: String, default: String = ""): String =
            runCatching { elem(key)?.asString }.getOrNull() ?: default
        private fun JsonObject.int(key: String, default: Int = 0): Int =
            runCatching { elem(key)?.asInt }.getOrNull() ?: default
        private fun JsonObject.long(key: String, default: Long = 0L): Long =
            runCatching { elem(key)?.asLong }.getOrNull() ?: default
        private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
            runCatching { elem(key)?.asBoolean }.getOrNull() ?: default
    }
}
