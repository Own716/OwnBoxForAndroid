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
) {
    val isUnavailable: Boolean get() = error.isNotBlank()
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
) {
    val isPinned: Boolean get() = pinnedProfileId > 0L || pinnedTag.isNotBlank()
    val canEdit: Boolean get() = editProfileId > 0L || editGroupId > 0L
}

object StrategySnapshot {

    enum class TimeBucket { NEVER, JUST_NOW, SECONDS, MINUTES, HOURS, DAYS }
    data class RelativeTime(val bucket: TimeBucket, val amount: Int = 0)

    fun parse(json: String?): StrategyGroupSnapshot? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.optBoolean("active", false)) return null

        val membersArr = root.optJSONArray("members")
        val members = ArrayList<StrategyMemberSnapshot>(membersArr?.length() ?: 0)
        if (membersArr != null) {
            for (i in 0 until membersArr.length()) {
                val item = membersArr.optJSONObject(i) ?: continue
                members.add(
                    StrategyMemberSnapshot(
                        tag = item.optString("tag"),
                        profileId = item.optLong("profileId", 0L),
                        name = item.optString("name"),
                        delay = item.optInt("delay", 0),
                        error = item.optString("error", ""),
                        lastCheckMs = item.optLong("lastCheckMs", 0L),
                        active = item.optBoolean("active", false),
                        pinned = item.optBoolean("pinned", false),
                    )
                )
            }
        }

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
        )
    }

    fun formatStrategyLabel(context: Context, strategyType: String): String {
        val resId = when (strategyType.trim()) {
            "leastPing", "least_ping" -> R.string.strategy_mode_least_ping
            "leastLoad", "least_load" -> R.string.strategy_mode_least_load
            "round_robin", "roundRobin" -> R.string.strategy_mode_round_robin
            "random" -> R.string.strategy_mode_random
            "consistent_hash", "consistentHash" -> R.string.strategy_mode_consistent_hash
            "failover" -> R.string.strategy_mode_failover
            "stable" -> R.string.strategy_mode_stable
            else -> 0
        }
        return if (resId != 0) context.getString(resId) else strategyType.ifBlank { context.getString(R.string.strategy_mode_least_ping) }
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
