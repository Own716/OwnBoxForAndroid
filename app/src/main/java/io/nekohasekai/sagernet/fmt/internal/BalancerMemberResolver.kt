package io.nekohasekai.sagernet.fmt.internal

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase

/**
 * Resolves effective strategy-group member nodes cleanly for both ConfigBuilder and settings UI.
 */
object BalancerMemberResolver {

    data class FilterSpec(
        val balancerType: Int,
        val targetGroupIds: List<Long>,
        val explicitProxyIds: List<Long>,
        val nameExclude: String = "",
        val nameInclude: String = "",
        val selfId: Long = 0L,
        val frontProxyId: Long = 0L,
        val landingProxyId: Long = 0L,
    )

    data class ResolutionResult(
        val members: List<ProxyEntity> = emptyList(),
        val groupMatchedCount: Int = 0,
        val extraAddedCount: Int = 0,
        val excludeRegexInvalid: Boolean = false,
        val includeRegexInvalid: Boolean = false,
    ) {
        val totalCount: Int get() = members.size
        val hasRegexError: Boolean get() = excludeRegexInvalid || includeRegexInvalid
    }

    fun resolve(
        balancer: BalancerBean,
        selfId: Long = 0L,
        frontProxyId: Long = balancer.frontProxy,
        landingProxyId: Long = balancer.landingProxy,
        groupProfilesProvider: (Long) -> List<ProxyEntity> = { gid -> SagerDatabase.proxyDao.getByGroup(gid) },
        entitiesByIdsProvider: (List<Long>) -> List<ProxyEntity> = { ids -> SagerDatabase.proxyDao.getEntities(ids) },
        isGroupDisabled: (Long) -> Boolean = { gid -> DataStore.isGroupDisabled(gid) },
    ): ResolutionResult {
        val targetGids = when {
            !balancer.targetGroupIds.isNullOrEmpty() -> balancer.targetGroupIds.filter { it > 0L }
            balancer.targetGroupId > 0L -> listOf(balancer.targetGroupId)
            else -> emptyList()
        }
        return resolve(
            spec = FilterSpec(
                balancerType = balancer.balancerType,
                targetGroupIds = targetGids,
                explicitProxyIds = balancer.proxies.orEmpty(),
                nameExclude = balancer.nameExclude.orEmpty(),
                nameInclude = balancer.nameInclude.orEmpty(),
                selfId = selfId,
                frontProxyId = frontProxyId,
                landingProxyId = landingProxyId,
            ),
            groupProfilesProvider = groupProfilesProvider,
            entitiesByIdsProvider = entitiesByIdsProvider,
            isGroupDisabled = isGroupDisabled,
        )
    }

    fun resolve(
        spec: FilterSpec,
        groupProfilesProvider: (Long) -> List<ProxyEntity> = { gid -> SagerDatabase.proxyDao.getByGroup(gid) },
        entitiesByIdsProvider: (List<Long>) -> List<ProxyEntity> = { ids -> SagerDatabase.proxyDao.getEntities(ids) },
        isGroupDisabled: (Long) -> Boolean = { gid -> DataStore.isGroupDisabled(gid) },
    ): ResolutionResult {
        var includeRegex: Regex? = null
        var includeInvalid = false
        if (spec.nameInclude.isNotBlank()) {
            val parsed = runCatching { Regex(spec.nameInclude) }.getOrNull()
            if (parsed == null) {
                includeInvalid = true
            } else {
                includeRegex = parsed
            }
        }

        var excludeRegex: Regex? = null
        var excludeInvalid = false
        if (spec.nameExclude.isNotBlank()) {
            val parsed = runCatching { Regex(spec.nameExclude) }.getOrNull()
            if (parsed == null) {
                excludeInvalid = true
            } else {
                excludeRegex = parsed
            }
        }

        fun isEligibleMember(entity: ProxyEntity): Boolean {
            if (entity.id == spec.selfId && spec.selfId > 0L) return false
            if (entity.id == spec.frontProxyId && spec.frontProxyId > 0L) return false
            if (entity.id == spec.landingProxyId && spec.landingProxyId > 0L) return false
            if (entity.type == ProxyEntity.TYPE_BALANCER) return false
            if (isGroupDisabled(entity.groupId)) return false
            return true
        }

        val resolved = ArrayList<ProxyEntity>()
        val seenIds = HashSet<Long>()
        var groupMatchedCount = 0
        var extraAddedCount = 0

        val usesGroup = spec.balancerType == BalancerBean.TYPE_GROUP || spec.balancerType == BalancerBean.TYPE_MIXED
        val usesExplicitList = spec.balancerType == BalancerBean.TYPE_LIST || spec.balancerType == BalancerBean.TYPE_MIXED

        if (usesGroup && spec.targetGroupIds.isNotEmpty()) {
            val groupCandidates = spec.targetGroupIds
                .distinct()
                .flatMap { gid -> groupProfilesProvider(gid) }
            for (entity in groupCandidates) {
                if (!seenIds.add(entity.id)) continue
                if (!isEligibleMember(entity)) {
                    seenIds.remove(entity.id)
                    continue
                }
                val name = entity.displayName()
                if (includeRegex != null && !includeRegex.containsMatchIn(name)) {
                    seenIds.remove(entity.id)
                    continue
                }
                if (excludeRegex != null && excludeRegex.containsMatchIn(name)) {
                    seenIds.remove(entity.id)
                    continue
                }
                resolved.add(entity)
                groupMatchedCount++
            }
        }

        if (usesExplicitList && spec.explicitProxyIds.isNotEmpty()) {
            val distinctIds = spec.explicitProxyIds.filter { it > 0L }.distinct()
            if (distinctIds.isNotEmpty()) {
                val byId = entitiesByIdsProvider(distinctIds).associateBy { it.id }
                for (id in distinctIds) {
                    val entity = byId[id] ?: continue
                    if (!seenIds.add(entity.id)) continue
                    if (!isEligibleMember(entity)) {
                        seenIds.remove(entity.id)
                        continue
                    }
                    resolved.add(entity)
                    extraAddedCount++
                }
            }
        }

        return ResolutionResult(
            members = resolved,
            groupMatchedCount = groupMatchedCount,
            extraAddedCount = extraAddedCount,
            excludeRegexInvalid = excludeInvalid,
            includeRegexInvalid = includeInvalid,
        )
    }
}
