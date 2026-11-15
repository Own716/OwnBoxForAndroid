package io.nekohasekai.sagernet.bg

import android.content.Context
import android.graphics.Color
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColour
import org.json.JSONObject

data class StrategyMemberSnapshot(
    val tag: String,
    val profileId: Long,
    val name: String,
    val delay: Int,
    val error: String,
    val lastCheckMs: Long,
    val active: Boolean,
    val pinned: Boolean,
    val role: String = "",
    val checkCount: Int = 0,
    val failCount: Int = 0,
    val suspectUntilMs: Long = 0L,
    val suspectRemainingSec: Int = 0,
) {
    val isUnavailable: Boolean
        get() = error.isNotBlank() || role == "failed" || role == "suspect"
}

data class StrategyGroupSnapshot(
    val active: Boolean,
    val strategyKey: String,
    val strategyKind: String,
    val strategyId: Long,
    val editProfileId: Long,
    val editGroupId: Long,
    val groupName: String,
    val groupTag: String,
    val strategyType: String,
    val activeTag: String,
    val activeProfileId: Long,
    val activeName: String,
    val activeDelay: Int,
    val pinnedTag: String,
    val pinnedProfileId: Long,
    val pinnedName: String,
    val checking: Boolean,
    val lastCheckMs: Long,
    val members: List<StrategyMemberSnapshot>,
    val selectionMode: String = "",
    val balanceEnabled: Boolean = false,
    val availableCount: Int = members.count { !it.isUnavailable && it.delay > 0 },
    val totalCount: Int = members.size,
    val lastSwitchMs: Long = 0L,
    val switchReason: String = "",
    val nextCheckSec: Int = 0,
    val learnedSiteCount: Int = 0,
    val learnEnabled: Boolean = false,
    val localOffline: Boolean = false,
) {
    val isPinned: Boolean get() = pinnedProfileId > 0L || pinnedTag.isNotBlank()
    val canEdit: Boolean get() = editProfileId > 0L || editGroupId > 0L
}

object StrategySnapshot {

    const val CLEAR_LEARNED_TAG = ":clear_learned"

    enum class TimeBucket { NEVER, JUST_NOW, SECONDS, MINUTES, HOURS, DAYS }
    data class RelativeTime(val bucket: TimeBucket, val amount: Int = 0)

    enum class MemberSubtitleKind {
        CARRYING_TRAFFIC,
        STANDBY,
        SUSPECT,
        FAILED,
        PARTIAL_FAILURES,
        NONE,
    }

    fun parse(json: String?): StrategyGroupSnapshot? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.optBoolean("active", false)) return null

        val membersArr = root.optJSONArray("members")
        val members = ArrayList<StrategyMemberSnapshot>(membersArr?.length() ?: 0)
        if (membersArr != null) {
            for (i in 0 until membersArr.length()) {
                val item = membersArr.optJSONObject(i) ?: continue
                val role = item.optString("role", "")
                val activeFlag = item.optBoolean("active", false) || role == "active"
                members.add(
                    StrategyMemberSnapshot(
                        tag = item.optString("tag"),
                        profileId = item.optLong("profileId", 0L),
                        name = item.optString("name"),
                        delay = item.optInt("delay", 0),
                        error = item.optString("error", ""),
                        lastCheckMs = item.optLong("lastCheckMs", 0L),
                        active = activeFlag,
                        pinned = item.optBoolean("pinned", false),
                        role = role,
                        checkCount = item.optInt("checkCount", 0),
                        failCount = item.optInt("failCount", 0),
                        suspectUntilMs = item.optLong("suspectUntilMs", 0L),
                        suspectRemainingSec = item.optInt("suspectRemainingSec", 0),
                    )
                )
            }
        }

        val parsedAvail = root.optInt("availableCount", -1)
        val parsedTotal = root.optInt("totalCount", -1)
        val effectiveAvail = if (parsedAvail >= 0) parsedAvail else members.count { !it.isUnavailable && it.delay > 0 }
        val effectiveTotal = if (parsedTotal >= 0) parsedTotal else members.size

        return StrategyGroupSnapshot(
            active = true,
            strategyKey = root.optString("strategyKey"),
            strategyKind = root.optString("strategyKind"),
            strategyId = root.optLong("strategyId", 0L),
            editProfileId = root.optLong("editProfileId", 0L),
            editGroupId = root.optLong("editGroupId", 0L),
            groupName = root.optString("groupName"),
            groupTag = root.optString("groupTag"),
            strategyType = root.optString("strategyType"),
            activeTag = root.optString("activeTag"),
            activeProfileId = root.optLong("activeProfileId", 0L),
            activeName = root.optString("activeName"),
            activeDelay = root.optInt("activeDelay", 0),
            pinnedTag = root.optString("pinnedTag"),
            pinnedProfileId = root.optLong("pinnedProfileId", 0L),
            pinnedName = root.optString("pinnedName"),
            checking = root.optBoolean("checking", false),
            lastCheckMs = root.optLong("lastCheckMs", 0L),
            members = members,
            selectionMode = root.optString("selectionMode", ""),
            balanceEnabled = root.optBoolean("balanceEnabled", false),
            availableCount = effectiveAvail,
            totalCount = effectiveTotal,
            lastSwitchMs = root.optLong("lastSwitchMs", 0L),
            switchReason = root.optString("switchReason", ""),
            nextCheckSec = root.optInt("nextCheckSec", 0),
            learnedSiteCount = root.optInt("learnedSiteCount", 0),
            learnEnabled = root.optBoolean("learnEnabled", false),
            localOffline = root.optBoolean("localOffline", false),
        )
    }

    fun resolveMemberSubtitleKind(member: StrategyMemberSnapshot): MemberSubtitleKind {
        return when {
            member.role == "suspect" -> MemberSubtitleKind.SUSPECT
            member.role == "failed" || member.error.isNotBlank() -> MemberSubtitleKind.FAILED
            member.role == "active" || member.active -> MemberSubtitleKind.CARRYING_TRAFFIC
            member.role == "standby" -> MemberSubtitleKind.STANDBY
            member.failCount > 0 && member.checkCount > 0 -> MemberSubtitleKind.PARTIAL_FAILURES
            else -> MemberSubtitleKind.NONE
        }
    }

    fun formatStrategyLabel(
        context: Context,
        strategyType: String,
        selectionMode: String = "",
        balanceEnabled: Boolean = false,
    ): String {
        val typeTrim = strategyType.trim()
        val selTrim = selectionMode.trim()
        if (typeTrim == "autoselect" || selTrim.isNotEmpty()) {
            val base = context.getString(
                if (selTrim == "priority") R.string.balancer_selection_priority else R.string.balancer_selection_latency
            )
            return if (balanceEnabled) {
                "$base · ${context.getString(R.string.balancer_cat_load_balance)}"
            } else {
                base
            }
        }
        val resId = when (typeTrim) {
            "leastPing", "least_ping" -> R.string.strategy_mode_least_ping
            "leastLoad", "least_load" -> R.string.strategy_mode_least_load
            "round_robin", "roundRobin" -> R.string.strategy_mode_round_robin
            "random" -> R.string.strategy_mode_random
            "consistent_hash", "consistentHash" -> R.string.strategy_mode_consistent_hash
            "failover" -> R.string.strategy_mode_failover
            "stable" -> R.string.strategy_mode_stable
            else -> 0
        }
        return if (resId != 0) context.getString(resId) else typeTrim.ifBlank { context.getString(R.string.balancer_selection_latency) }
    }

    fun computeRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): RelativeTime {
        if (timestampMs <= 0L) return RelativeTime(TimeBucket.NEVER)
        val diffSec = ((nowMs - timestampMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 10L -> RelativeTime(TimeBucket.JUST_NOW)
            diffSec < 60L -> RelativeTime(TimeBucket.SECONDS, diffSec.toInt())
            diffSec < 3600L -> RelativeTime(TimeBucket.MINUTES, (diffSec / 60L).toInt())
            diffSec < 86400L -> RelativeTime(TimeBucket.HOURS, (diffSec / 3600L).toInt())
            else -> RelativeTime(TimeBucket.DAYS, (diffSec / 86400L).toInt())
        }
    }

    fun formatRelativeTime(context: Context, timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val rel = computeRelativeTime(timestampMs, nowMs)
        return when (rel.bucket) {
            TimeBucket.NEVER -> context.getString(R.string.strategy_time_never)
            TimeBucket.JUST_NOW -> context.getString(R.string.strategy_time_just_now)
            TimeBucket.SECONDS -> context.getString(R.string.strategy_time_seconds_ago, rel.amount)
            TimeBucket.MINUTES -> context.getString(R.string.strategy_time_minutes_ago, rel.amount)
            TimeBucket.HOURS -> context.getString(R.string.strategy_time_hours_ago, rel.amount)
            TimeBucket.DAYS -> context.getString(R.string.strategy_time_days_ago, rel.amount)
        }
    }

    fun resolveLatencyColor(context: Context, delay: Int): Int {
        return when {
            delay < 300 -> context.getColour(R.color.material_green_500)
            delay < 800 -> Color.parseColor("#FFA000")
            else -> context.getColour(R.color.material_red_500)
        }
    }
}
