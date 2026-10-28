package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.IOException
import java.sql.SQLException
import java.util.*


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: List<TrafficData>)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateProxy(profile)
        iterator { onUpdated(profile, false) }
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        SagerDatabase.proxyDao.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
    }

    suspend fun updateTraffic(profileId: Long, rx: Long, tx: Long) {
        SagerDatabase.proxyDao.updateTraffic(profileId, rx, tx)
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        if (profileIds.isNotEmpty()) {
            SagerDatabase.proxyDao.resetTraffic(profileIds)
        }
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: List<TrafficData>) {
        if (data.isEmpty()) return
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    suspend fun getRules(): List<RuleEntity> {
        var rules = SagerDatabase.rulesDao.allRules()
        if (rules.isEmpty() && !DataStore.rulesFirstCreate) {
            DataStore.rulesFirstCreate = true
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_quic),
                    network = "udp",
                    protocol = "quic",
                    outbound = -2
                )
            )
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_ads),
                    domains = "geosite:category-ads-all",
                    outbound = -2
                )
            )
            val fuckedCountry = mutableListOf("cn:中国")
            if (Locale.getDefault().country != Locale.CHINA.country) {
                // 非中文用户
                fuckedCountry += "ir:Iran"
                fuckedCountry += "ru:Russia"
            }
            for (c in fuckedCountry) {
                val country = c.substringBefore(":")
                val displayCountry = c.substringAfter(":")
                //
                if (country == "cn") createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_play_store, displayCountry),
                        domains = listOf(
                            "geosite:google-play",
                            "geosite:google@cn",
                            "domain:googleapis.cn",
                            "domain:gvt1.com",
                            "domain:gvt2.com",
                            "domain:gvt3.com",
                            "domain:gvt5.com",
                            "domain:gvt6.com",
                            "domain:gvt7.com",
                            "domain:gvt9.com",
                            "domain:gvt1-cn.com",
                            "domain:gvt2-cn.com",
                            "domain:googleusercontent.com",
                            "domain:play.googleapis.com",
                            "domain:android.clients.google.com",
                            "domain:playstoregatewayadapter-pa.googleapis.com",
                            "domain:firebaselogging-pa.googleapis.com",
                            "domain:ggpht.com",
                            "domain:xn--ngstr-lra8j.com",
                            "domain:xn--ngstr-cn-8za9o.com"
                        ).joinToString("\n"),
                        packages = setOf(
                            "com.android.vending",
                            "com.google.android.gms",
                            "com.google.android.gsf",
                            "com.android.providers.downloads",
                            "com.android.providers.downloads.ui"
                        )
                    ), false
                )
                createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_bypass_domain, displayCountry),
                        domains = "geosite:$country",
                        outbound = -1
                    ), false
                )
                createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_bypass_ip, displayCountry),
                        ip = "geoip:$country",
                        outbound = -1
                    ), false
                )
            }
            rules = SagerDatabase.rulesDao.allRules()
        } else if (rules.isNotEmpty()) {
            // Auto-enrich existing Play Store rules for existing users without resetting DB
            var needReload = false
            rules.forEach { rule ->
                val isPlayStoreRule = rule.domains.contains("googleapis.cn") || rule.packages.contains("com.android.vending")
                if (isPlayStoreRule && (!rule.packages.contains("com.android.providers.downloads") || !rule.domains.contains("playstoregatewayadapter-pa.googleapis.com"))) {
                    val currentDomains = rule.domains.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    val newDomains = listOf(
                        "geosite:google-play",
                        "geosite:google@cn",
                        "domain:googleapis.cn",
                        "domain:gvt1.com",
                        "domain:gvt2.com",
                        "domain:gvt3.com",
                        "domain:gvt5.com",
                        "domain:gvt6.com",
                        "domain:gvt7.com",
                        "domain:gvt9.com",
                        "domain:gvt1-cn.com",
                        "domain:gvt2-cn.com",
                        "domain:googleusercontent.com",
                        "domain:play.googleapis.com",
                        "domain:android.clients.google.com",
                        "domain:playstoregatewayadapter-pa.googleapis.com",
                        "domain:firebaselogging-pa.googleapis.com",
                        "domain:ggpht.com",
                        "domain:xn--ngstr-lra8j.com",
                        "domain:xn--ngstr-cn-8za9o.com"
                    )
                    rule.domains = (currentDomains + newDomains).distinct().joinToString("\n")
                    rule.packages = rule.packages + setOf(
                        "com.android.vending",
                        "com.google.android.gms",
                        "com.google.android.gsf",
                        "com.android.providers.downloads",
                        "com.android.providers.downloads.ui"
                    )
                    SagerDatabase.rulesDao.updateRule(rule)
                    needReload = true
                }
            }
            if (needReload) {
                rules = SagerDatabase.rulesDao.allRules()
            }
        }
        return rules
    }

    fun memberIds(groupId: Long): List<Long> {
        return SagerDatabase.proxyDao.getByGroup(groupId).map { it.id }
    }

    fun members(groupId: Long): List<ProxyEntity> {
        return SagerDatabase.proxyDao.getByGroup(groupId)
    }

    fun runningProfileId(): Long = DataStore.currentProfile

    suspend fun clearGroupTestResults(groupId: Long) {
        val list = SagerDatabase.proxyDao.getByGroup(groupId)
        list.forEach {
            it.status = 0
            it.ping = 0
            it.error = null
        }
        SagerDatabase.proxyDao.updateProxy(list)
    }

    class DeleteOutcome(
        @JvmField val ok: Boolean,
        @JvmField val deleted: List<Long>,
        @JvmField val kept: List<Long>,
    )

    suspend fun batchDeleteProfiles(
        profileIds: List<Long>,
        stopRunning: Boolean = false,
    ): DeleteOutcome {
        val deleted = ArrayList<Long>()
        val kept = ArrayList<Long>()
        val runningId = runningProfileId()
        for (id in profileIds) {
            if (!stopRunning && id == runningId) {
                kept.add(id)
                continue
            }
            val profile = getProfile(id) ?: continue
            deleteProfile(profile.groupId, id)
            deleted.add(id)
        }
        return DeleteOutcome(true, deleted, kept)
    }


    suspend fun emplace(groupId: Long, from: Int, to: Int): Boolean {
        val list = SagerDatabase.proxyDao.getByGroup(groupId).toMutableList()
        if (from !in list.indices || to !in list.indices) return false
        val item = list.removeAt(from)
        list.add(to, item)
        list.forEachIndexed { index, proxy ->
            proxy.userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(list)
        GroupRepo.postReload(groupId)
        return true
    }

    suspend fun setOrder(groupId: Long, ids: List<Long>) {
        val list = SagerDatabase.proxyDao.getByGroup(groupId).associateBy { it.id }.toMutableMap()
        var order = 1L
        val updated = mutableListOf<ProxyEntity>()
        for (id in ids) {
            val p = list.remove(id) ?: continue
            p.userOrder = order++
            updated.add(p)
        }
        for ((_, p) in list) {
            p.userOrder = order++
            updated.add(p)
        }
        SagerDatabase.proxyDao.updateProxy(updated)
        GroupRepo.postReload(groupId)
    }

    fun clearTestResults(profileIds: List<Long>) {
        if (profileIds.isEmpty()) return
        val list = getProfiles(profileIds)
        for (p in list) {
            p.status = 0
            p.ping = 0
            p.error = null
            p.speedTestDownloadBitsPerSecond = 0
            p.speedTestUploadBitsPerSecond = 0
            p.speedTestMode = ""
        }
        SagerDatabase.proxyDao.updateProxy(list)
    }

    suspend fun moveToGroup(profileIds: List<Long>, targetGroupId: Long) = moveProfilesToGroup(profileIds, targetGroupId)

    suspend fun moveProfilesToGroup(profileIds: List<Long>, targetGroupId: Long) {
        val targetList = SagerDatabase.proxyDao.getByGroup(targetGroupId)
        var maxOrder = targetList.maxOfOrNull { it.userOrder } ?: 0L
        val profiles = getProfiles(profileIds)
        val sources = profiles.map { it.groupId }.distinct()
        for (p in profiles) {
            p.groupId = targetGroupId
            p.userOrder = ++maxOrder
        }
        SagerDatabase.proxyDao.updateProxy(profiles)
        sources.forEach { GroupRepo.postReload(it) }
        GroupRepo.postReload(targetGroupId)
    }

}
