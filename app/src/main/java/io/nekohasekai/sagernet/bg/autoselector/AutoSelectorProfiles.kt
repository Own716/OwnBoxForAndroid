package io.nekohasekai.sagernet.bg.autoselector

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AutoSelectorPlan
import io.nekohasekai.sagernet.fmt.AutoSelectorPlanner
import io.nekohasekai.sagernet.fmt.SelectorGroup
import io.nekohasekai.sagernet.fmt.SelectorMember
import io.nekohasekai.sagernet.fmt.SelectorStore
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import kotlin.math.abs

/**
 * Bridges OwnBox's Room database (`ProxyEntity`, `ProxyGroup`) and `AutoSelectorHealthStore`
 * with the pure-Kotlin `AutoSelectorPlanner` and `AutoSelectorRuntime`.
 */
object AutoSelectorProfiles {

    data class HealthUpdate(
        val profileId: Long,
        val latencyMs: Int,
        val atSec: Long,
        val error: String? = null,
    )

    fun isAutoSelectorEntity(entity: ProxyEntity?): Boolean {
        if (entity == null || entity.type != ProxyEntity.TYPE_BALANCER) return false
        val bean = entity.balancerBean ?: (runCatching { entity.requireBean() }.getOrNull() as? BalancerBean)
        return bean?.strategy == BalancerBean.STRATEGY_AUTO_SELECTOR
    }

    fun getBalancerBean(entity: ProxyEntity?): BalancerBean? {
        if (entity == null || entity.type != ProxyEntity.TYPE_BALANCER) return null
        return entity.balancerBean ?: (runCatching { entity.requireBean() }.getOrNull() as? BalancerBean)
    }

    fun findAutoSelector(selectorId: Long): Pair<ProxyEntity, BalancerBean>? {
        if (selectorId <= 0L) return null
        val entity = runCatching { SagerDatabase.proxyDao.getById(selectorId) }.getOrNull() ?: return null
        val bean = getBalancerBean(entity) ?: return null
        if (bean.strategy != BalancerBean.STRATEGY_AUTO_SELECTOR) return null
        return entity to bean
    }

    fun loadAllAutoSelectors(): List<Pair<ProxyEntity, BalancerBean>> {
        val entities = runCatching { SagerDatabase.proxyDao.getByType(ProxyEntity.TYPE_BALANCER) }
            .getOrDefault(emptyList())
        return entities.mapNotNull { entity ->
            val bean = getBalancerBean(entity) ?: return@mapNotNull null
            if (bean.strategy == BalancerBean.STRATEGY_AUTO_SELECTOR) entity to bean else null
        }
    }

    fun storeSnapshot(): SelectorStore {
        val groups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())
        val allEntities = runCatching { SagerDatabase.proxyDao.getAll() }.getOrDefault(emptyList())
        val healthMap = AutoSelectorHealthStore.snapshot()

        val membersById = HashMap<Long, SelectorMember>(allEntities.size)
        val memberIdsByGroup = HashMap<Long, MutableList<Long>>()
        for (entity in allEntities.sortedBy { it.userOrder }) {
            val member = toSelectorMember(entity, healthMap)
            membersById[entity.id] = member
            memberIdsByGroup.getOrPut(entity.groupId) { ArrayList() }.add(entity.id)
        }

        val groupsById = HashMap<Long, SelectorGroup>(groups.size)
        for (g in groups) {
            groupsById[g.id] = SelectorGroup(
                id = g.id,
                name = g.displayName(),
                memberIds = memberIdsByGroup[g.id] ?: emptyList(),
            )
        }

        return object : SelectorStore {
            override fun group(id: Long): SelectorGroup? = groupsById[id]
            override fun member(id: Long): SelectorMember? = membersById[id]
        }
    }

    fun buildPlan(
        bean: BalancerBean,
        excludeProfileId: Long = 0L,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): AutoSelectorPlan {
        return AutoSelectorPlanner.plan(
            bean = bean,
            store = storeSnapshot(),
            nowSec = nowSec,
            excludeProfileId = excludeProfileId,
        )
    }

    suspend fun saveHealth(updates: List<HealthUpdate>) {
        if (updates.isEmpty()) return
        val storeUpdates = HashMap<Long, AutoSelectorHealthStore.Entry>(updates.size)
        val changedIds = ArrayList<Long>()

        for (u in updates) {
            if (u.profileId <= 0L) continue
            val latency = u.latencyMs.coerceAtLeast(0)
            val err = u.error?.trim()?.takeIf { it.isNotEmpty() }
            storeUpdates[u.profileId] = AutoSelectorHealthStore.Entry(
                latency = latency,
                at = u.atSec,
                error = err,
            )

            val existing = runCatching { SagerDatabase.proxyDao.getById(u.profileId) }.getOrNull() ?: continue
            val newStatus = if (latency > 0 && err == null) 1 else 3
            val meaningfulChange = existing.status != newStatus ||
                (newStatus == 1 && abs(existing.ping - latency) >= 20) ||
                (newStatus == 3 && existing.error != err)
            if (meaningfulChange) {
                runCatching {
                    SagerDatabase.proxyDao.updatePingResult(
                        proxyId = u.profileId,
                        status = newStatus,
                        ping = latency,
                        error = err,
                    )
                }
                changedIds.add(u.profileId)
            }
        }

        AutoSelectorHealthStore.recordBatch(storeUpdates)
        for (id in changedIds) {
            runCatching { ProfileManager.postUpdate(id, true) }
        }
    }

    suspend fun setPinned(selectorId: Long, memberProfileId: Long): Boolean {
        val (entity, bean) = findAutoSelector(selectorId) ?: return false
        val normalizedPin = if (memberProfileId > 0L) memberProfileId else -1L
        if (bean.pinnedId == normalizedPin) return true
        bean.pinnedId = normalizedPin
        entity.balancerBean = bean
        runCatching { SagerDatabase.proxyDao.updateProxy(entity) }.onFailure { return false }
        runCatching { ProfileManager.postUpdate(selectorId, true) }
        return true
    }

    suspend fun recordPlanOutcome(
        selectorId: Long,
        poolIds: List<Long>,
        buildIds: List<Long>,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ) {
        val (entity, bean) = findAutoSelector(selectorId) ?: return
        val poolChanged = bean.pool != poolIds
        val buildChanged = bean.lastBuilt != buildIds
        if (!poolChanged && !buildChanged && bean.lastBuiltAt > 0L) return
        bean.pool = ArrayList(poolIds)
        bean.poolRankedAt = nowSec
        bean.lastBuilt = ArrayList(buildIds)
        bean.lastBuiltAt = nowSec
        entity.balancerBean = bean
        runCatching { SagerDatabase.proxyDao.updateProxy(entity) }
        runCatching { ProfileManager.postUpdate(selectorId, true) }
    }

    private fun toSelectorMember(
        entity: ProxyEntity,
        healthMap: Map<Long, AutoSelectorHealthStore.Entry>,
    ): SelectorMember {
        var meta = entity.type == ProxyEntity.TYPE_BALANCER || entity.type == ProxyEntity.TYPE_CHAIN
        var extraCore = false
        var fullConfig = false
        var malformed = false
        var supported = entity.type != ProxyEntity.TYPE_NEKO

        val name = runCatching { entity.displayName() }.getOrElse {
            malformed = true
            "#${entity.id}"
        }

        if (!meta && !malformed) {
            if (entity.type == ProxyEntity.TYPE_CONFIG) {
                val cfg = entity.configBean ?: (runCatching { entity.requireBean() }.getOrNull() as? ConfigBean)
                if (cfg == null) {
                    malformed = true
                } else if (cfg.type != 1) {
                    fullConfig = true
                } else {
                    val validObj = runCatching { JsonParser.parseString(cfg.config).isJsonObject }.getOrDefault(false)
                    if (!validObj) malformed = true
                }
            } else {
                extraCore = runCatching { entity.needExternal() }.getOrDefault(false)
                if (extraCore) supported = false
            }
        }

        val health = AutoSelectorHealthStore.resolveMemberHealth(entity, healthMap)
        return SelectorMember(
            id = entity.id,
            groupId = entity.groupId,
            name = name,
            meta = meta,
            extraCore = extraCore,
            fullConfig = fullConfig,
            malformed = malformed,
            supported = supported,
            latency = health.latency,
            latencyAt = health.at,
            country = AutoSelectorCountry.inferCountryCode(name),
            error = health.error,
        )
    }
}
