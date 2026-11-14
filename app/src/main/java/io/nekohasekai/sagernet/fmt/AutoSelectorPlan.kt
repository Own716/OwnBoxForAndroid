package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorCountry
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import java.util.EnumMap

enum class AutoSelectorSkip {
    MISSING,
    META,
    EXTRA_CORE,
    FULL_CONFIG,
    MALFORMED,
    UNSUPPORTED,
    NAME,
    COUNTRY,
    UNAVAILABLE,
}

data class SelectorMember(
    val id: Long,
    val groupId: Long,
    val name: String,
    val meta: Boolean = false,
    val extraCore: Boolean = false,
    val fullConfig: Boolean = false,
    val malformed: Boolean = false,
    val supported: Boolean = true,
    val latency: Int = 0,
    val latencyAt: Long = 0L,
    val country: String = "",
    val error: String? = null,
) {
    fun effectiveCountry(): String {
        return country.ifBlank { AutoSelectorCountry.inferCountryCode(name) }
    }
}

data class SelectorGroup(
    val id: Long,
    val name: String,
    val memberIds: List<Long>,
)

interface SelectorStore {
    fun group(id: Long): SelectorGroup?
    fun member(id: Long): SelectorMember?
}

data class AutoSelectorPlan(
    val trackedGroups: List<Long>,
    val missingGroups: Int,
    val totalInSources: Int,
    val candidates: List<SelectorMember>,
    val pool: List<SelectorMember>,
    val build: List<SelectorMember>,
    val skipped: Map<AutoSelectorSkip, Int>,
    val truncated: Boolean,
    val keptAllUnavailable: Boolean,
    val reusedCount: Int,
    val unmeasuredInBuild: Int,
    val unmeasuredInPool: Int,
)

/**
 * Pure Kotlin planner for `BalancerBean(strategy = STRATEGY_AUTO_SELECTOR)`.
 * Decides which group/explicit nodes are eligible, applies regex & country filters,
 * reuses recent probe latencies within `resultValidityMins`, rescues all-failed pools,
 * and caps both the measurement pool (`poolCap`) and running sing-box group (`buildLimit`).
 */
object AutoSelectorPlanner {

    fun collectTrackedGroupIds(bean: BalancerBean): List<Long> {
        val result = ArrayList<Long>()
        val seen = HashSet<Long>()
        bean.targetGroupIds?.forEach { gid ->
            if (gid > 0L && seen.add(gid)) result.add(gid)
        }
        if (result.isEmpty() && bean.targetGroupId > 0L) {
            result.add(bean.targetGroupId)
        }
        return result
    }

    fun hasFreshResult(member: SelectorMember, nowSec: Long, validitySec: Long): Boolean {
        if (validitySec <= 0L || member.latencyAt <= 0L) return false
        val age = nowSec - member.latencyAt
        return age >= -60L && age <= validitySec
    }

    fun memberWorks(member: SelectorMember): Boolean {
        return member.latency > 0 && member.error.isNullOrBlank()
    }

    fun effectiveLatency(member: SelectorMember, maxRttMs: Int): Int {
        if (!memberWorks(member)) return Int.MAX_VALUE
        if (maxRttMs > 0 && member.latency > maxRttMs) return Int.MAX_VALUE - 1
        return member.latency
    }

    fun startQuorum(totalUnmeasured: Int, expected: Int, buildSize: Int): Int {
        if (totalUnmeasured <= 0 || buildSize <= 0) return 0
        val target = expected.coerceAtLeast(1).coerceAtMost(buildSize)
        return totalUnmeasured.coerceAtMost(target)
    }

    fun plan(
        bean: BalancerBean,
        store: SelectorStore,
        nowSec: Long = System.currentTimeMillis() / 1000L,
        excludeProfileId: Long = 0L,
    ): AutoSelectorPlan {
        val trackedGroups = collectTrackedGroupIds(bean)
        var missingGroups = 0
        val rawMemberIds = ArrayList<Long>()
        val seenMemberIds = HashSet<Long>()
        if (excludeProfileId > 0L) {
            seenMemberIds.add(excludeProfileId)
        }

        for (gid in trackedGroups) {
            val grp = store.group(gid)
            if (grp == null) {
                missingGroups++
                continue
            }
            for (mid in grp.memberIds) {
                if (mid > 0L && seenMemberIds.add(mid)) {
                    rawMemberIds.add(mid)
                }
            }
        }
        bean.proxies?.forEach { mid ->
            if (mid > 0L && seenMemberIds.add(mid)) {
                rawMemberIds.add(mid)
            }
        }

        val nameIncludeRegex = compileRegexSafely(bean.nameInclude)
        val nameExcludeRegex = compileRegexSafely(bean.nameExclude)
        val countryFilter = AutoSelectorCountry.parseFilter(bean.countryFilter)
        val validitySec = bean.resultValidityMins.coerceAtLeast(0) * 60L
        val poolCap = bean.poolCap.coerceIn(1, BalancerBean.MAX_POOL_CAP)
        val buildLimit = bean.buildLimit.coerceIn(1, BalancerBean.MAX_BUILD_LIMIT).coerceAtMost(poolCap)
        val maxRtt = bean.maxRttMs.coerceAtLeast(0)

        val skipped = EnumMap<AutoSelectorSkip, Int>(AutoSelectorSkip::class.java)
        fun skip(reason: AutoSelectorSkip) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }

        val passed = ArrayList<SelectorMember>()
        val failedWithinWindow = ArrayList<SelectorMember>()

        for (mid in rawMemberIds) {
            val member = store.member(mid)
            if (member == null) {
                skip(AutoSelectorSkip.MISSING)
                continue
            }
            if (member.meta) {
                skip(AutoSelectorSkip.META)
                continue
            }
            if (member.extraCore) {
                skip(AutoSelectorSkip.EXTRA_CORE)
                continue
            }
            if (member.fullConfig) {
                skip(AutoSelectorSkip.FULL_CONFIG)
                continue
            }
            if (member.malformed) {
                skip(AutoSelectorSkip.MALFORMED)
                continue
            }
            if (!member.supported) {
                skip(AutoSelectorSkip.UNSUPPORTED)
                continue
            }
            if (nameIncludeRegex != null && !nameIncludeRegex.containsMatchIn(member.name)) {
                skip(AutoSelectorSkip.NAME)
                continue
            }
            if (nameExcludeRegex != null && nameExcludeRegex.containsMatchIn(member.name)) {
                skip(AutoSelectorSkip.NAME)
                continue
            }
            if (countryFilter.isActive && !countryFilter.matches(member.effectiveCountry())) {
                skip(AutoSelectorSkip.COUNTRY)
                continue
            }

            val fresh = hasFreshResult(member, nowSec, validitySec)
            if (bean.excludeUnavailable && fresh && !memberWorks(member)) {
                failedWithinWindow.add(member)
                continue
            }
            passed.add(member)
        }

        var keptAllUnavailable = false
        val candidates = if (passed.isEmpty() && failedWithinWindow.isNotEmpty()) {
            keptAllUnavailable = true
            failedWithinWindow
        } else {
            if (failedWithinWindow.isNotEmpty()) {
                skipped[AutoSelectorSkip.UNAVAILABLE] = failedWithinWindow.size
            }
            passed
        }

        val ranked = rankMembers(candidates, nowSec, validitySec, maxRtt)
        val truncated = ranked.size > poolCap
        val poolList = (if (truncated) ranked.subList(0, poolCap) else ranked).toMutableList()
        val pinnedId = bean.pinnedId
        if (pinnedId > 0L && poolList.none { it.id == pinnedId }) {
            val pinnedMember = ranked.firstOrNull { it.id == pinnedId }
            if (pinnedMember != null) {
                if (poolList.size >= poolCap && poolList.isNotEmpty()) {
                    poolList[poolList.lastIndex] = pinnedMember
                } else {
                    poolList.add(pinnedMember)
                }
            }
        }
        val pool = poolList.toList()

        val buildList = (if (pool.size > buildLimit) pool.subList(0, buildLimit) else pool).toMutableList()
        if (pinnedId > 0L && buildList.none { it.id == pinnedId }) {
            val pinnedMember = pool.firstOrNull { it.id == pinnedId }
            if (pinnedMember != null) {
                if (buildList.size >= buildLimit && buildList.isNotEmpty()) {
                    buildList[buildList.lastIndex] = pinnedMember
                } else {
                    buildList.add(pinnedMember)
                }
            }
        }
        val build = buildList.toList()

        var reusedCount = 0
        var unmeasuredInPool = 0
        for (m in pool) {
            if (hasFreshResult(m, nowSec, validitySec)) {
                if (memberWorks(m)) reusedCount++
            } else {
                unmeasuredInPool++
            }
        }
        val unmeasuredInBuild = build.count { !hasFreshResult(it, nowSec, validitySec) }

        return AutoSelectorPlan(
            trackedGroups = trackedGroups,
            missingGroups = missingGroups,
            totalInSources = rawMemberIds.size,
            candidates = ranked,
            pool = pool,
            build = build,
            skipped = skipped,
            truncated = truncated,
            keptAllUnavailable = keptAllUnavailable,
            reusedCount = reusedCount,
            unmeasuredInBuild = unmeasuredInBuild,
            unmeasuredInPool = unmeasuredInPool,
        )
    }

    fun rankingCandidates(
        pool: List<SelectorMember>,
        bean: BalancerBean,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): List<SelectorMember> {
        val validitySec = bean.resultValidityMins.coerceAtLeast(0) * 60L
        return pool.filter { !hasFreshResult(it, nowSec, validitySec) }
    }

    fun rerank(
        pool: List<SelectorMember>,
        bean: BalancerBean,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): List<SelectorMember> {
        val validitySec = bean.resultValidityMins.coerceAtLeast(0) * 60L
        val maxRtt = bean.maxRttMs.coerceAtLeast(0)
        return rankMembers(pool, nowSec, validitySec, maxRtt)
    }

    fun buildChanged(previousIds: List<Long>, nextIds: List<Long>): Boolean {
        return previousIds != nextIds
    }

    private fun rankMembers(
        members: List<SelectorMember>,
        nowSec: Long,
        validitySec: Long,
        maxRtt: Int,
    ): List<SelectorMember> {
        val indexOrder = HashMap<Long, Int>(members.size)
        members.forEachIndexed { idx, member -> indexOrder[member.id] = idx }

        return members.sortedWith { a, b ->
            val aTier = memberTier(a, nowSec, validitySec, maxRtt)
            val bTier = memberTier(b, nowSec, validitySec, maxRtt)
            if (aTier != bTier) return@sortedWith aTier.compareTo(bTier)

            if (aTier == 0 || aTier == 2) {
                val cmp = a.latency.compareTo(b.latency)
                if (cmp != 0) return@sortedWith cmp
            }
            (indexOrder[a.id] ?: 0).compareTo(indexOrder[b.id] ?: 0)
        }
    }

    private fun memberTier(
        member: SelectorMember,
        nowSec: Long,
        validitySec: Long,
        maxRtt: Int,
    ): Int {
        val fresh = hasFreshResult(member, nowSec, validitySec)
        if (!fresh) return 1
        if (!memberWorks(member)) return 3
        if (maxRtt > 0 && member.latency > maxRtt) return 2
        return 0
    }

    private fun compileRegexSafely(pattern: String?): Regex? {
        val trimmed = pattern?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrNull()
    }
}
