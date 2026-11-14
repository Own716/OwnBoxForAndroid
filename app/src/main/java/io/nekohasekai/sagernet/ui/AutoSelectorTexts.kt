package io.nekohasekai.sagernet.ui

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorMemberStatus
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.fmt.AutoSelectorPlan
import io.nekohasekai.sagernet.fmt.AutoSelectorSkip
import io.nekohasekai.sagernet.fmt.internal.BalancerBean

/**
 * Human-readable formatters for AutoSelector configuration plan summaries,
 * live status headlines, details, footers, and member diagnostic rows.
 */
object AutoSelectorTexts {

    fun ago(context: Context, atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (atMs <= 0L) return context.getString(R.string.autosel_never)
        val deltaSec = ((nowMs - atMs) / 1000L).coerceAtLeast(0L)
        return when {
            deltaSec < 5L -> context.getString(R.string.autosel_just_now)
            deltaSec < 60L -> context.getString(R.string.autosel_ago_seconds, deltaSec.toInt())
            deltaSec < 3600L -> context.getString(R.string.autosel_ago_minutes, (deltaSec / 60L).toInt())
            deltaSec < 86_400L -> context.getString(R.string.autosel_ago_hours, (deltaSec / 3600L).toInt())
            else -> context.getString(R.string.autosel_ago_days, (deltaSec / 86_400L).toInt())
        }
    }

    fun agoSeconds(context: Context, atSec: Long, nowMs: Long = System.currentTimeMillis()): String {
        return ago(context, atSec * 1000L, nowMs)
    }

    fun skipLabel(context: Context, reason: AutoSelectorSkip): String {
        return context.getString(
            when (reason) {
                AutoSelectorSkip.MISSING -> R.string.autosel_skip_missing
                AutoSelectorSkip.META -> R.string.autosel_skip_meta
                AutoSelectorSkip.EXTRA_CORE -> R.string.autosel_skip_extra_core
                AutoSelectorSkip.FULL_CONFIG -> R.string.autosel_skip_full_config
                AutoSelectorSkip.MALFORMED -> R.string.autosel_skip_malformed
                AutoSelectorSkip.UNSUPPORTED -> R.string.autosel_skip_unsupported
                AutoSelectorSkip.NAME -> R.string.autosel_skip_name
                AutoSelectorSkip.COUNTRY -> R.string.autosel_skip_country
                AutoSelectorSkip.UNAVAILABLE -> R.string.autosel_skip_unavailable
            }
        )
    }

    fun planSummary(context: Context, plan: AutoSelectorPlan, poolCap: Int): String {
        val parts = ArrayList<String>()
        val sourceCount = plan.trackedGroups.size + if (plan.totalInSources > 0 && plan.trackedGroups.isEmpty()) 1 else 0
        if (sourceCount > 1) {
            parts.add(
                context.getString(
                    R.string.autosel_plan_counts_groups,
                    plan.candidates.size,
                    plan.totalInSources,
                    sourceCount,
                    plan.build.size,
                )
            )
        } else {
            parts.add(
                context.getString(
                    R.string.autosel_plan_counts,
                    plan.candidates.size,
                    plan.totalInSources,
                    plan.build.size,
                )
            )
        }

        if (plan.missingGroups > 0) {
            parts.add(
                context.resources.getQuantityString(
                    R.plurals.autosel_plan_missing_groups,
                    plan.missingGroups,
                    plan.missingGroups,
                )
            )
        }

        val sep = context.getString(R.string.autosel_list_separator)
        if (plan.skipped.isNotEmpty()) {
            val skipItems = plan.skipped.entries
                .filter { it.value > 0 }
                .joinToString(sep) { (reason, count) ->
                    context.getString(R.string.autosel_plan_skip_item, count, skipLabel(context, reason))
                }
            if (skipItems.isNotEmpty()) {
                parts.add(context.getString(R.string.autosel_plan_skipped, skipItems))
            }
        }

        if (plan.keptAllUnavailable && plan.candidates.isNotEmpty()) {
            parts.add(context.getString(R.string.autosel_plan_kept_unavailable, plan.candidates.size))
        }

        if (plan.truncated) {
            val cap = poolCap.coerceIn(1, BalancerBean.MAX_POOL_CAP)
            parts.add(context.getString(R.string.autosel_plan_truncated, cap))
        }

        if (plan.reusedCount > 0) {
            parts.add(context.getString(R.string.autosel_plan_reused, plan.reusedCount))
        }

        if (plan.unmeasuredInPool > 0) {
            parts.add(context.getString(R.string.autosel_plan_needs_ranking))
        }

        return parts.joinToString("\n")
    }

    fun statusHeadline(
        context: Context,
        status: AutoSelectorStatus,
        nowMs: Long = System.currentTimeMillis(),
        resolveName: (Long, String) -> String = { id, tag -> tag.ifBlank { "#$id" } },
    ): String {
        if (!status.running) {
            return context.getString(R.string.autosel_status_stopped)
        }
        if (status.suspended) {
            return context.getString(R.string.autosel_status_paused, status.membersTotal)
        }
        val selected = status.selectedTag.isNotBlank() || status.selectedId > 0L
        if (!selected) {
            return when {
                status.rankingActive && status.rankingTotal > 0 ->
                    context.getString(R.string.autosel_status_measuring, status.rankingTotal)
                status.membersProbed == 0 ->
                    context.getString(R.string.autosel_status_starting, status.membersTotal)
                status.membersProbed < status.membersTotal ->
                    context.getString(R.string.autosel_status_probing, status.membersProbed, status.membersTotal)
                else ->
                    context.getString(R.string.autosel_status_no_working, status.membersTotal)
            }
        }

        val selectedName = resolveName(status.selectedId, status.selectedTag)
        val base = context.getString(
            R.string.autosel_status_on_working,
            selectedName,
            status.membersAlive,
            status.membersTotal,
        )
        return if (status.lastSwitchMs > 0L) {
            base + context.getString(R.string.autosel_status_switched, ago(context, status.lastSwitchMs, nowMs))
        } else {
            base
        }
    }

    fun statusDetail(context: Context, status: AutoSelectorStatus): String {
        if (!status.running) return ""
        val sep = context.getString(R.string.autosel_list_separator)
        val parts = ArrayList<String>()
        if (status.membersAlive > 0) {
            parts.add(context.getString(R.string.autosel_detail_working, status.membersAlive))
        }
        if (status.membersCooldown > 0) {
            parts.add(context.getString(R.string.autosel_detail_cooling, status.membersCooldown))
        }
        if (status.probesInFlight > 0) {
            parts.add(context.getString(R.string.autosel_detail_checking, status.probesInFlight))
        }
        val untested = (status.membersTotal - status.membersProbed).coerceAtLeast(0)
        if (untested > 0) {
            parts.add(context.getString(R.string.autosel_detail_untested, untested))
        }
        if (status.balance && status.membersQualified > 1) {
            parts.add(context.getString(R.string.autosel_detail_balancing, status.membersQualified))
        }
        val summary = if (parts.isEmpty()) {
            context.getString(R.string.autosel_detail_untested, status.membersTotal)
        } else {
            parts.joinToString(sep)
        }
        return context.getString(R.string.autosel_detail_health, status.membersTotal, summary)
    }

    fun statusFooter(
        context: Context,
        status: AutoSelectorStatus,
        nowMs: Long = System.currentTimeMillis(),
        resolveName: (Long, String) -> String = { id, tag -> tag.ifBlank { "#$id" } },
    ): String {
        if (!status.running) return ""
        val lines = ArrayList<String>()
        if (status.suspended) {
            lines.add(context.getString(R.string.autosel_footer_suspended))
        } else {
            val roundParts = ArrayList<String>()
            if (status.lastRoundMs > 0L) {
                roundParts.add(context.getString(R.string.autosel_footer_last_round, ago(context, status.lastRoundMs, nowMs)))
            }
            if (status.nextRoundMs > nowMs) {
                val sec = ((status.nextRoundMs - nowMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                roundParts.add(context.getString(R.string.autosel_footer_next_round, sec))
            }
            if (roundParts.isNotEmpty()) {
                lines.add(roundParts.joinToString(" "))
            }
        }

        val hasPin = status.pinnedId > 0L || status.pinnedTag.isNotBlank()
        if (hasPin) {
            val pinnedName = resolveName(status.pinnedId, status.pinnedTag)
            val pinnedInUse = (status.pinnedId > 0L && status.pinnedId == status.selectedId) ||
                (status.pinnedTag.isNotBlank() && status.pinnedTag == status.selectedTag)
            lines.add(
                context.getString(
                    if (pinnedInUse) R.string.autosel_footer_pinned_using else R.string.autosel_footer_pinned_other,
                    pinnedName,
                )
            )
        }

        if (status.lastSwitchMs > 0L && status.lastSwitchReason.isNotBlank()) {
            lines.add(
                context.getString(
                    R.string.autosel_footer_last_switch_reason,
                    ago(context, status.lastSwitchMs, nowMs),
                    status.lastSwitchReason,
                )
            )
        }

        if (status.exhausted && !status.suspended) {
            lines.add(context.getString(R.string.autosel_footer_exhausted))
        }

        return lines.joinToString("\n")
    }

    fun stateLabel(context: Context, state: String): String {
        return context.getString(
            when (state) {
                "ok" -> R.string.autosel_state_ok
                "degraded" -> R.string.autosel_state_degraded
                "dead" -> R.string.autosel_state_dead
                "cooldown" -> R.string.autosel_state_cooldown
                else -> R.string.autosel_state_untested
            }
        )
    }

    fun memberSubtitle(
        context: Context,
        member: AutoSelectorMemberStatus,
        status: AutoSelectorStatus,
        exists: Boolean,
        testedAtSec: Long,
        lastTestError: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val parts = ArrayList<String>()
        if (!exists) {
            parts.add(context.getString(R.string.autosel_note_missing))
        }
        val isPinned = (status.pinnedId > 0L && status.pinnedId == member.profileId) ||
            (status.pinnedTag.isNotBlank() && status.pinnedTag == member.tag)
        val isSelected = member.selected ||
            (status.selectedId > 0L && status.selectedId == member.profileId) ||
            (status.selectedTag.isNotBlank() && status.selectedTag == member.tag)

        when {
            isPinned && isSelected -> parts.add(context.getString(R.string.autosel_note_pinned_selected))
            isPinned && member.state in setOf("dead", "cooldown") ->
                parts.add(context.getString(R.string.autosel_note_pinned_unusable))
            isPinned -> parts.add(context.getString(R.string.autosel_note_pinned))
            isSelected -> parts.add(context.getString(R.string.autosel_note_selected))
        }

        val err = member.lastError.ifBlank { lastTestError.orEmpty() }.trim()
        when (member.state) {
            "cooldown" -> {
                if (member.cooldownUntilMs > nowMs) {
                    val sec = ((member.cooldownUntilMs - nowMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                    parts.add(context.getString(R.string.autosel_note_cooldown, sec))
                } else {
                    parts.add(context.getString(R.string.autosel_note_cooldown_now))
                }
            }
            "dead" -> parts.add(err.ifBlank { context.getString(R.string.autosel_note_dead) })
            "degraded" -> {
                if (member.probes > 0 && member.failures > 0) {
                    parts.add(context.getString(R.string.autosel_note_failures, member.failures, member.probes))
                }
                if (err.isNotBlank()) parts.add(err)
            }
            "ok" -> {
                if (member.qualified && !isSelected && status.running) {
                    parts.add(context.getString(R.string.autosel_note_qualified))
                }
            }
            else -> {
                if (status.running) {
                    if (member.active) {
                        parts.add(context.getString(R.string.autosel_note_checking))
                    } else {
                        parts.add(context.getString(R.string.autosel_note_queued))
                    }
                } else if (err.isNotBlank()) {
                    parts.add(err)
                }
            }
        }

        val sep = context.getString(R.string.autosel_list_separator)
        if (!status.running && err.isNotBlank() && testedAtSec > 0L) {
            // Avoid duplicating identical error string while ensuring stopped errors are visible.
            return parts.distinct().joinToString(sep)
        }
        return parts.distinct().joinToString(sep)
    }
}
