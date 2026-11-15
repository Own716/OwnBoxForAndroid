package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.proto.BoxInstance
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coordinates live strategy-group snapshots, manual checks, strict pinning,
 * and learned-site resets between Go BoxInstance and Android :bg service.
 */
object StrategySnapshotHelper {

    const val ACTION_CLEAR_LEARNED = -2L

    data class ActiveStrategyContext(
        val strategyTag: String,
        val strategyKey: String,
        val kind: String,
        val strategyId: Long,
        val editProfileId: Long,
        val editGroupId: Long,
        val displayGroupName: String,
    )

    fun resolveActiveStrategy(proxy: ProxyInstance): ActiveStrategyContext? {
        if (!proxy.isInitialized()) return null
        val config = proxy.config
        if (config.activeStrategyKind == "group" && config.activeStrategyTag.isNotBlank()) {
            val gid = config.activeStrategyId
            val grp = if (gid > 0L) runCatching { SagerDatabase.groupDao.getById(gid) }.getOrNull() else null
            return ActiveStrategyContext(
                strategyTag = config.activeStrategyTag.ifBlank { TAG_PROXY },
                strategyKey = "group_$gid",
                kind = "group",
                strategyId = gid,
                editProfileId = 0L,
                editGroupId = gid,
                displayGroupName = grp?.displayName() ?: config.activeStrategyTag,
            )
        }
        val curProfile = proxy.profile
        val balancerId = when {
            curProfile.type == ProxyEntity.TYPE_BALANCER -> curProfile.id
            config.activeStrategyKind == "balancer" && config.activeStrategyId > 0L -> config.activeStrategyId
            else -> 0L
        }
        if (balancerId > 0L) {
            val tag = (config.activeStrategyTag.takeIf { it.isNotBlank() }
                ?: config.balancerTagMap[balancerId])
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val displayName = if (curProfile.type == ProxyEntity.TYPE_BALANCER) {
                curProfile.displayName()
            } else {
                val balancerName = runCatching { SagerDatabase.proxyDao.getById(balancerId)?.displayName() }.getOrNull()
                balancerName?.takeIf { it.isNotBlank() } ?: curProfile.displayName()
            }
            return ActiveStrategyContext(
                strategyTag = tag,
                strategyKey = "balancer_$balancerId",
                kind = "balancer",
                strategyId = balancerId,
                editProfileId = balancerId,
                editGroupId = 0L,
                displayGroupName = displayName,
            )
        }
        return null
    }

    fun applyPersistedPin(proxy: BoxInstance) {
        val ctx = (proxy as? ProxyInstance)?.let { resolveActiveStrategy(it) } ?: run {
            if (!proxy.isInitialized()) return
            val cfg = proxy.config
            if (cfg.activeStrategyTag.isBlank()) return
            ActiveStrategyContext(
                strategyTag = cfg.activeStrategyTag,
                strategyKey = "${cfg.activeStrategyKind}_${cfg.activeStrategyId}",
                kind = cfg.activeStrategyKind,
                strategyId = cfg.activeStrategyId,
                editProfileId = if (cfg.activeStrategyKind == "balancer") cfg.activeStrategyId else 0L,
                editGroupId = if (cfg.activeStrategyKind == "group") cfg.activeStrategyId else 0L,
                displayGroupName = cfg.activeStrategyTag,
            )
        }
        val pinnedProfileId = DataStore.getStrategyPinnedProfileId(ctx.strategyKey)
        if (pinnedProfileId <= 0L) return

        val rawJson = runCatching { proxy.box.getGroupSnapshotJSON(ctx.strategyTag) }.getOrNull().orEmpty()
        val targetTag = findMemberTagInRawSnapshot(rawJson, proxy, pinnedProfileId)
        if (targetTag != null && runCatching { proxy.box.pinGroupOutbound(ctx.strategyTag, targetTag) }.getOrDefault(false)) {
            Logs.i("StrategySnapshotHelper: restored strict pin $pinnedProfileId -> $targetTag for ${ctx.strategyKey}")
        } else {
            // Clear stale pin if the member was deleted or filtered out of this strategy group.
            DataStore.setStrategyPinnedProfileId(ctx.strategyKey, 0L)
            Logs.i("StrategySnapshotHelper: cleared stale pin $pinnedProfileId for ${ctx.strategyKey}")
        }
    }

    fun getOrCheckSnapshotJson(proxy: ProxyInstance, forceCheck: Boolean): String {
        val ctx = resolveActiveStrategy(proxy) ?: return ""
        val raw = runCatching {
            if (forceCheck) {
                proxy.box.checkGroupOutboundsJSON(ctx.strategyTag)
            } else {
                proxy.box.getGroupSnapshotJSON(ctx.strategyTag)
            }
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return ""
        return enrichSnapshotJson(raw, proxy, ctx)
    }

    fun pinMemberAndSnapshotJson(data: BaseService.Data, memberProfileId: Long): String {
        val proxy = data.proxy ?: return ""
        val ctx = resolveActiveStrategy(proxy) ?: return ""
        when {
            memberProfileId == ACTION_CLEAR_LEARNED -> {
                runCatching { proxy.box.pinGroupOutbound(ctx.strategyTag, StrategySnapshot.CLEAR_LEARNED_TAG) }
                return getOrCheckSnapshotJson(proxy, forceCheck = false)
            }

            memberProfileId <= 0L -> {
                runCatching { proxy.box.pinGroupOutbound(ctx.strategyTag, "") }
                DataStore.setStrategyPinnedProfileId(ctx.strategyKey, 0L)
            }

            else -> {
                val rawBefore = runCatching { proxy.box.getGroupSnapshotJSON(ctx.strategyTag) }.getOrNull().orEmpty()
                val targetTag = findMemberTagInRawSnapshot(rawBefore, proxy, memberProfileId)
                    ?: return enrichSnapshotJson(rawBefore, proxy, ctx)
                val ok = runCatching { proxy.box.pinGroupOutbound(ctx.strategyTag, targetTag) }.getOrDefault(false)
                if (!ok) {
                    return if (rawBefore.isBlank()) "" else enrichSnapshotJson(rawBefore, proxy, ctx)
                }
                DataStore.setStrategyPinnedProfileId(ctx.strategyKey, memberProfileId)
            }
        }

        val updated = ActiveOutboundTracker.checkAndUpdate(data)
        val newTitle = if (updated) {
            ActiveOutboundTracker.formatNotificationTitle(proxy.profile).also {
                proxy.displayProfileName = it
            }
        } else {
            null
        }
        val mainId = proxy.profile.id
        data.binder.launch {
            if (newTitle != null) {
                runCatching { data.notification?.refresh(newTitle) }
            }
            data.binder.broadcast { cb ->
                cb.cbSelectorUpdate(mainId)
            }
        }
        return getOrCheckSnapshotJson(proxy, forceCheck = false)
    }

    internal fun findMemberTagInRawSnapshot(
        rawJson: String,
        proxy: BoxInstance,
        memberProfileId: Long,
    ): String? {
        if (memberProfileId <= 0L || rawJson.isBlank()) return null
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val members = root.optJSONArray("members") ?: return null
        val tagProfileMap = proxy.config.outboundTagProfileMap
        for (i in 0 until members.length()) {
            val item = members.optJSONObject(i) ?: continue
            val tag = item.optString("tag")
            if (tag.isNotBlank() && tagProfileMap[tag] == memberProfileId) {
                return tag
            }
        }
        val fallbackTag = proxy.config.profileTagMap[memberProfileId]
        if (!fallbackTag.isNullOrBlank()) {
            for (i in 0 until members.length()) {
                val item = members.optJSONObject(i) ?: continue
                if (item.optString("tag") == fallbackTag) {
                    return fallbackTag
                }
            }
        }
        return null
    }

    internal fun enrichSnapshotJson(
        rawJson: String,
        proxy: BoxInstance,
        ctx: ActiveStrategyContext,
    ): String {
        val raw = runCatching { JSONObject(rawJson) }.getOrNull() ?: return ""
        val rawMembers = raw.optJSONArray("members") ?: JSONArray()
        val tagProfileMap = proxy.config.outboundTagProfileMap
        val reverseProfileTagMap = proxy.config.profileTagMap.entries
            .filter { it.key > 0L }
            .associate { it.value to it.key }

        val memberIds = LinkedHashSet<Long>()
        for (i in 0 until rawMembers.length()) {
            val m = rawMembers.optJSONObject(i) ?: continue
            val tag = m.optString("tag")
            val pid = tagProfileMap[tag] ?: reverseProfileTagMap[tag] ?: 0L
            if (pid > 0L) memberIds.add(pid)
        }
        val entities = if (memberIds.isNotEmpty()) {
            runCatching { SagerDatabase.proxyDao.getEntities(memberIds.toList()).associateBy { it.id } }
                .getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val activeTag = raw.optString("activeTag")
        val pinnedTag = raw.optString("pinnedTag")
        var activeProfileId = 0L
        var activeName = ""
        var activeDelay = 0
        var pinnedProfileId = 0L
        var pinnedName = ""
        var computedAvailable = 0

        val enrichedMembers = JSONArray()
        for (i in 0 until rawMembers.length()) {
            val m = rawMembers.optJSONObject(i) ?: continue
            val tag = m.optString("tag")
            val pid = tagProfileMap[tag] ?: reverseProfileTagMap[tag] ?: 0L
            val displayName = entities[pid]?.displayName() ?: tag
            val delay = m.optInt("delay", 0)
            val error = m.optString("error", "")
            val lastCheckMs = m.optLong("lastCheckMs", 0L)
            val role = m.optString("role", "")
            val isActive = (activeTag.isNotBlank() && tag == activeTag) || role == "active"
            val isPinned = pinnedTag.isNotBlank() && tag == pinnedTag
            val checkCount = m.optInt("checkCount", 0)
            val failCount = m.optInt("failCount", 0)
            val suspectUntilMs = m.optLong("suspectUntilMs", 0L)
            val suspectRemainingSec = m.optInt("suspectRemainingSec", 0)

            val memberAvailable = role != "failed" && role != "suspect" && error.isBlank() && delay > 0
            if (memberAvailable) {
                computedAvailable++
            }

            if (isActive) {
                activeProfileId = pid
                activeName = displayName
                activeDelay = if (error.isBlank() && delay > 0) delay else 0
            }
            if (isPinned) {
                pinnedProfileId = pid
                pinnedName = displayName
            }

            enrichedMembers.put(JSONObject().apply {
                put("tag", tag)
                put("profileId", pid)
                put("name", displayName)
                put("delay", delay)
                put("error", error)
                put("lastCheckMs", lastCheckMs)
                put("active", isActive)
                put("pinned", isPinned)
                put("role", role)
                put("checkCount", checkCount)
                put("failCount", failCount)
                put("suspectUntilMs", suspectUntilMs)
                put("suspectRemainingSec", suspectRemainingSec)
            })
        }

        if (activeName.isBlank() && activeTag.isNotBlank()) {
            activeProfileId = tagProfileMap[activeTag] ?: reverseProfileTagMap[activeTag] ?: 0L
            activeName = entities[activeProfileId]?.displayName() ?: activeTag
        }
        if (pinnedName.isBlank() && pinnedTag.isNotBlank()) {
            pinnedProfileId = tagProfileMap[pinnedTag] ?: reverseProfileTagMap[pinnedTag] ?: 0L
            pinnedName = entities[pinnedProfileId]?.displayName() ?: pinnedTag
        }

        val availCount = raw.optInt("availableCount", -1).takeIf { it >= 0 } ?: computedAvailable
        val totalCount = raw.optInt("totalCount", -1).takeIf { it >= 0 } ?: enrichedMembers.length()

        return JSONObject().apply {
            put("active", true)
            put("strategyKey", ctx.strategyKey)
            put("strategyKind", ctx.kind)
            put("strategyId", ctx.strategyId)
            put("editProfileId", ctx.editProfileId)
            put("editGroupId", ctx.editGroupId)
            put("groupName", ctx.displayGroupName)
            put("groupTag", raw.optString("groupTag", ctx.strategyTag))
            put("strategyType", raw.optString("strategyType", ""))
            put("selectionMode", raw.optString("selectionMode", ""))
            put("balanceEnabled", raw.optBoolean("balanceEnabled", false))
            put("availableCount", availCount)
            put("totalCount", totalCount)
            put("lastSwitchMs", raw.optLong("lastSwitchMs", 0L))
            put("switchReason", raw.optString("switchReason", ""))
            put("nextCheckSec", raw.optInt("nextCheckSec", 0))
            put("learnedSiteCount", raw.optInt("learnedSiteCount", 0))
            put("learnEnabled", raw.optBoolean("learnEnabled", false))
            put("localOffline", raw.optBoolean("localOffline", false))
            put("activeTag", activeTag)
            put("activeProfileId", activeProfileId)
            put("activeName", activeName)
            put("activeDelay", activeDelay)
            put("pinnedTag", pinnedTag)
            put("pinnedProfileId", pinnedProfileId)
            put("pinnedName", pinnedName)
            put("checking", raw.optBoolean("checking", false))
            put("lastCheckMs", raw.optLong("lastCheckMs", 0L))
            put("members", enrichedMembers)
        }.toString()
    }
}
