package io.nekohasekai.sagernet.bg

import android.content.Context
import android.os.SystemClock
import android.text.format.Formatter
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

object ActiveOutboundTracker {

    @Volatile
    var activeLeafProfileId: Long = 0L
        private set

    @Volatile
    var activeLeafProfileName: String = ""
        private set

    fun reset() {
        activeLeafProfileId = 0L
        activeLeafProfileName = ""
        runCatching {
            DataStore.activeLeafProfileId = 0L
            DataStore.activeLeafProfileName = ""
            DataStore.activeBalancerId = 0L
        }
    }

    fun getStrategyDisplayName(profile: ProxyEntity): String {
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.requireBean() as? BalancerBean
            return buildString {
                append(if (bean?.isFallbackMode() == true) "故障转移" else if (bean?.isPriorityMode() == true) "按优先级" else "最快最稳")
                if (bean?.balanceEnabled == true) append(" · 流量分配")
            }
        }
        val isGlobal = runCatching { DataStore.globalMode }.getOrDefault(false)
        val isSmart = runCatching { DataStore.smartMode }.getOrDefault(false)
        return when {
            isGlobal -> "全局模式"
            isSmart -> "智能分流"
            else -> "规则分流"
        }
    }

    fun onProfileSwitched(newProfile: ProxyEntity) {
        val oldId = activeLeafProfileId
        reset()
        if (!LiveUpdateFormatter.isStrategyProfile(newProfile)) {
            updateActiveLeaf(newProfile.id, newProfile.displayName())
        }
        if (oldId > 0L) {
            runOnDefaultDispatcher {
                ProfileManager.postUpdate(oldId, true)
                ProfileManager.postUpdate(newProfile.id, true)
            }
        }
    }

    fun updateActiveLeaf(candidateId: Long, candidateName: String) {
        activeLeafProfileId = candidateId
        activeLeafProfileName = candidateName
        runCatching {
            DataStore.activeLeafProfileId = candidateId
            DataStore.activeLeafProfileName = candidateName
        }
    }

    fun getActiveLeafNodeDisplay(profile: ProxyEntity): String? {
        if (profile.type != ProxyEntity.TYPE_BALANCER) return null

        val leafId = activeLeafProfileId.takeIf { it > 0L }
            ?: runCatching { DataStore.activeLeafProfileId }.getOrDefault(0L)
        if (leafId > 0L && leafId != profile.id) {
            val name = activeLeafProfileName.takeIf { it.isNotBlank() }
                ?: runCatching { DataStore.activeLeafProfileName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { SagerDatabase.proxyDao.getById(leafId)?.displayName() }.getOrNull()
            return name?.takeIf { it.isNotBlank() }
        }
        return null
    }

    data class NotificationTextBundle(
        val title: String,
        val collapsedText: String,
        val bigText: String,
    )

    fun formatNotificationTitle(
        profile: ProxyEntity,
        isGlobalMode: Boolean? = null
    ): String {
        val isStrategy = profile.type == ProxyEntity.TYPE_BALANCER

        if (isStrategy) {
            val leafNode = getActiveLeafNodeDisplay(profile)
            val showGroup = runCatching { DataStore.showGroupInNotification }.getOrDefault(true)
            if (!showGroup && !leafNode.isNullOrBlank()) {
                // 用户关闭了“显示分组”：策略组下直接以当前连上的节点名作为主标题
                return leafNode
            }
            val baseName = profile.displayName()
            if (!leafNode.isNullOrBlank()) {
                return if (baseName.isNotBlank() && !baseName.contains(leafNode)) {
                    "$baseName · $leafNode"
                } else {
                    leafNode
                }
            }
            return baseName
        }

        return runCatching { ServiceNotification.genTitle(profile) }.getOrDefault(profile.displayName())
    }

    fun buildNotificationTexts(
        profile: ProxyEntity?,
        leafNode: String?,
        strategyName: String,
        groupName: String?,
        showGroup: Boolean,
        showDirectSpeed: Boolean,
        proxySpeed: String,
        directSpeed: String,
    ): NotificationTextBundle {
        if (profile == null) {
            return NotificationTextBundle(
                title = "",
                collapsedText = "代理: $proxySpeed",
                bigText = "代理: $proxySpeed" + if (showDirectSpeed) "\n直连: $directSpeed" else ""
            )
        }

        val isStrategy = profile.type == ProxyEntity.TYPE_BALANCER

        val title: String
        val collapsedText: String
        val bigContent: String

        if (isStrategy) {
            if (showGroup) {
                // Template 1: 策略组 + 显示组名开启
                val baseGroupName = profile.displayName()
                title = if (!leafNode.isNullOrBlank()) {
                    if (baseGroupName.isNotBlank() && !baseGroupName.contains(leafNode)) {
                        "$baseGroupName · $leafNode"
                    } else {
                        leafNode
                    }
                } else {
                    baseGroupName
                }
                collapsedText = "代理: $proxySpeed"
                bigContent = buildString {
                    append("代理: ").append(proxySpeed)
                    if (showDirectSpeed) {
                        append("\n直连: ").append(directSpeed)
                    }
                }
            } else {
                // Template 2: 策略组 + 显示组名关闭
                title = if (!leafNode.isNullOrBlank()) leafNode else profile.displayName()
                collapsedText = "代理: $proxySpeed"
                bigContent = buildString {
                    append("代理: ").append(proxySpeed)
                    if (showDirectSpeed) {
                        append("\n直连: ").append(directSpeed)
                    }
                }
            }
        } else {
            val profileName = profile.displayName()
            if (showGroup && !groupName.isNullOrBlank()) {
                // Template 3: 普通单节点 + 显示组名开启
                title = if (!profileName.startsWith("$groupName · ")) {
                    "$groupName · $profileName"
                } else {
                    profileName
                }
            } else {
                // Template 4: 普通单节点 + 显示组名关闭
                title = profileName
            }
            collapsedText = "代理: $proxySpeed"
            bigContent = buildString {
                append("代理: ").append(proxySpeed)
                if (showDirectSpeed) {
                    append("\n直连: ").append(directSpeed)
                }
            }
        }

        return NotificationTextBundle(
            title = title,
            collapsedText = collapsedText,
            bigText = bigContent
        )
    }

    fun formatNotificationSubText(
        context: Context,
        stats: SpeedDisplayData,
        profile: ProxyEntity,
    ): String {
        val trafficStr = context.getString(
            R.string.traffic,
            Formatter.formatFileSize(context, stats.txTotal),
            Formatter.formatFileSize(context, stats.rxTotal)
        )
        val strategyStr = getStrategyDisplayName(profile)
        return "$trafficStr · $strategyStr"
    }

    private var lastQueryTime = 0L
    private var cachedClashMap: Map<String, String> = emptyMap()

    private fun queryClashMap(): Map<String, String> {
        if (!DataStore.enableClashAPI && !DataStore.allowAccess) return emptyMap()
        val now = SystemClock.elapsedRealtime()
        if (now - lastQueryTime < 3000L && cachedClashMap.isNotEmpty()) {
            return cachedClashMap
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://127.0.0.1:9090/proxies")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 800
                requestMethod = "GET"
                val secret = DataStore.clashApiSecret
                if (secret.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $secret")
                }
            }
            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val content = reader.use { it.readText() }
                val root = JSONObject(content)
                val proxies = root.optJSONObject("proxies") ?: return emptyMap()
                val result = mutableMapOf<String, String>()
                val keys = proxies.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val p = proxies.optJSONObject(key)
                    val nowTag = p?.optString("now")
                    if (!nowTag.isNullOrBlank()) {
                        result[key] = nowTag
                    }
                }
                lastQueryTime = now
                cachedClashMap = result
                result
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        } finally {
            conn?.disconnect()
        }
    }

    fun checkAndUpdate(data: BaseService.Data): Boolean {
        val proxy = data.proxy ?: return false
        if (!proxy.isInitialized()) return false
        val profile = proxy.profile
        val isBalancer = profile.type == ProxyEntity.TYPE_BALANCER

        if (!isBalancer) {
            if (activeLeafProfileId != profile.id) {
                activeLeafProfileId = profile.id
                activeLeafProfileName = profile.displayName()
                return true
            }
            return false
        }

        // Strategy group: resolve active member
        val memberMap = runCatching { proxy.safeConfig?.balancerMemberMap?.get(profile.id) }.getOrNull()

        if (memberMap.isNullOrEmpty()) {
            if (activeLeafProfileId != 0L) {
                reset()
                return true
            }
            return false
        }

        // Target tag for this specific strategy group (prefer Go core direct query without requiring Clash API)
        val strategyCtx = StrategySnapshotHelper.resolveActiveStrategy(proxy)
        val balancerTag = strategyCtx?.strategyTag
            ?: runCatching { proxy.safeConfig?.balancerTagMap?.get(profile.id) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { proxy.safeConfig?.profileTagMap?.get(profile.id) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: profile.displayName()

        var candidateTag = runCatching { proxy.box.getActiveOutboundTag(balancerTag) }.getOrNull()
        if (candidateTag.isNullOrBlank()) {
            val clashMap = queryClashMap()
            candidateTag = clashMap[balancerTag]
        }

        var candidateId: Long? = null
        if (!candidateTag.isNullOrBlank()) {
            val directResolved = runCatching {
                proxy.safeConfig?.outboundTagProfileMap?.get(candidateTag)
            }.getOrNull()
            val resolved = directResolved ?: runCatching {
                proxy.safeConfig?.profileTagMap?.entries
                    ?.firstOrNull { it.value == candidateTag }
                    ?.key
                    ?.let { abs(it) }
            }.getOrNull()
            // Strict member whitelist check: candidate MUST belong to this strategy group's members!
            if (resolved != null && resolved in memberMap) {
                candidateId = resolved
            }
        }

        if (candidateId == null || candidateId <= 0L) {
            // Check traffic deltas in TrafficLooper
            val activeItem = proxy.looper?.getActiveTransmittingMember(memberMap)
            if (activeItem != null && activeItem > 0L && activeItem in memberMap) {
                candidateId = activeItem
            }
        }

        if (candidateId == null || candidateId <= 0L) {
            // If current leaf is already valid for this group, keep it
            if (activeLeafProfileId in memberMap) {
                candidateId = activeLeafProfileId
            } else {
                candidateId = memberMap.firstOrNull()
            }
        }

        if (candidateId != null && candidateId in memberMap && candidateId != activeLeafProfileId) {
            val oldId = activeLeafProfileId
            activeLeafProfileId = candidateId
            val ent = runCatching { SagerDatabase.proxyDao.getById(candidateId) }.getOrNull()
            activeLeafProfileName = ent?.displayName() ?: ""
            runCatching {
                DataStore.activeLeafProfileId = candidateId
                DataStore.activeLeafProfileName = activeLeafProfileName
                DataStore.activeBalancerId = profile.id
            }
            Logs.i("ActiveOutboundTracker: active node changed $oldId -> $candidateId ($activeLeafProfileName)")

            runOnDefaultDispatcher {
                if (oldId > 0L) ProfileManager.postUpdate(oldId, true)
                ProfileManager.postUpdate(candidateId, true)
                ProfileManager.postUpdate(profile.id, true)
            }
            return true
        }

        return false
    }

}
