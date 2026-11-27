package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.TrafficStatsDb
import org.json.JSONArray
import org.json.JSONObject

/**
 * Buffers per-tick traffic deltas in the :bg service process and persists them in batches to [TrafficStatsDb].
 * Also tracks current session breakdowns for instant "Current session" display without extra DB queries.
 */
object TrafficStatsRecorder {

    private const val FLUSH_INTERVAL_MS = 30_000L

    private var pendingDay = TrafficStatsDb.formatDay()
    private var pendingDaily = TrafficStatsDb.DailyDelta()
    private val pendingProfiles = HashMap<Long, TrafficStatsDb.ProfileDelta>()
    private val pendingApps = HashMap<String, TrafficStatsDb.AppDelta>()

    private var sessionSummary = TrafficStatsDb.DailyDelta()
    private val sessionProfiles = HashMap<Long, TrafficStatsDb.ProfileDelta>()
    private val sessionApps = HashMap<String, TrafficStatsDb.AppDelta>()
    private var sessionDay = TrafficStatsDb.formatDay()

    private var lastFlushMs = 0L
    private var lastPruneDay = ""

    @Synchronized
    fun onSessionStart() {
        sessionSummary = TrafficStatsDb.DailyDelta()
        sessionProfiles.clear()
        sessionApps.clear()
        sessionDay = TrafficStatsDb.formatDay()
        lastFlushMs = System.currentTimeMillis()
    }

    @Synchronized
    fun recordTrafficDelta(proxyRx: Long, proxyTx: Long, directRx: Long, directTx: Long) {
        if (proxyRx <= 0L && proxyTx <= 0L && directRx <= 0L && directTx <= 0L) return
        rollDayIfNeeded()
        val pRx = proxyRx.coerceAtLeast(0L)
        val pTx = proxyTx.coerceAtLeast(0L)
        val dRx = directRx.coerceAtLeast(0L)
        val dTx = directTx.coerceAtLeast(0L)

        pendingDaily.proxyRx += pRx
        pendingDaily.proxyTx += pTx
        pendingDaily.directRx += dRx
        pendingDaily.directTx += dTx

        sessionSummary.proxyRx += pRx
        sessionSummary.proxyTx += pTx
        sessionSummary.directRx += dRx
        sessionSummary.directTx += dTx
    }

    @Synchronized
    fun recordProfileDelta(
        profileId: Long,
        profileName: String,
        profileType: String,
        rx: Long,
        tx: Long,
    ) {
        if (profileId <= 0L || (rx <= 0L && tx <= 0L)) return
        rollDayIfNeeded()
        val addRx = rx.coerceAtLeast(0L)
        val addTx = tx.coerceAtLeast(0L)

        val pending = pendingProfiles.getOrPut(profileId) {
            TrafficStatsDb.ProfileDelta(profileId, profileName, profileType)
        }
        if (profileName.isNotBlank()) pending.profileName = profileName
        if (profileType.isNotBlank()) pending.profileType = profileType
        pending.rx += addRx
        pending.tx += addTx

        val sess = sessionProfiles.getOrPut(profileId) {
            TrafficStatsDb.ProfileDelta(profileId, profileName, profileType)
        }
        if (profileName.isNotBlank()) sess.profileName = profileName
        if (profileType.isNotBlank()) sess.profileType = profileType
        sess.rx += addRx
        sess.tx += addTx
    }

    @Synchronized
    fun recordAppDeltasJson(json: String?) {
        if (json.isNullOrBlank()) return
        rollDayIfNeeded()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pkg = obj.optString("packageName", "").trim()
            val uid = obj.optInt("uid", -1)
            val pRx = obj.optLong("proxyDown", 0L).coerceAtLeast(0L)
            val pTx = obj.optLong("proxyUp", 0L).coerceAtLeast(0L)
            val dRx = obj.optLong("directDown", 0L).coerceAtLeast(0L)
            val dTx = obj.optLong("directUp", 0L).coerceAtLeast(0L)
            if (pRx == 0L && pTx == 0L && dRx == 0L && dTx == 0L) continue

            val appKey = when {
                pkg.isNotEmpty() -> pkg
                uid >= 0 -> "uid:$uid"
                else -> "system"
            }

            val pending = pendingApps.getOrPut(appKey) {
                TrafficStatsDb.AppDelta(appKey, uid, pkg)
            }
            pending.proxyRx += pRx
            pending.proxyTx += pTx
            pending.directRx += dRx
            pending.directTx += dTx

            val sess = sessionApps.getOrPut(appKey) {
                TrafficStatsDb.AppDelta(appKey, uid, pkg)
            }
            sess.proxyRx += pRx
            sess.proxyTx += pTx
            sess.directRx += dRx
            sess.directTx += dTx
        }
    }

    fun flushToDb(force: Boolean = false) {
        val snapshotDay: String
        val daily: TrafficStatsDb.DailyDelta
        val profiles: List<TrafficStatsDb.ProfileDelta>
        val apps: List<TrafficStatsDb.AppDelta>
        var needPrune = false

        synchronized(this) {
            val now = System.currentTimeMillis()
            if (!force && now - lastFlushMs < FLUSH_INTERVAL_MS) return
            lastFlushMs = now

            snapshotDay = pendingDay
            daily = pendingDaily
            profiles = pendingProfiles.values.toList()
            apps = pendingApps.values.toList()

            pendingDaily = TrafficStatsDb.DailyDelta()
            pendingProfiles.clear()
            pendingApps.clear()

            if (lastPruneDay != snapshotDay) {
                lastPruneDay = snapshotDay
                needPrune = true
            }
        }

        if (!daily.isEmpty || profiles.isNotEmpty() || apps.isNotEmpty()) {
            TrafficStatsDb.addBatch(snapshotDay, daily, profiles, apps)
        }
        if (needPrune) {
            TrafficStatsDb.pruneOlderThanDays(90)
        }
    }

    @Synchronized
    fun removeProfile(profileId: Long) {
        if (profileId <= 0L) return
        pendingProfiles.remove(profileId)
        sessionProfiles.remove(profileId)
    }

    @Synchronized
    fun clearSession() {
        pendingDaily = TrafficStatsDb.DailyDelta()
        pendingProfiles.clear()
        pendingApps.clear()
        sessionSummary = TrafficStatsDb.DailyDelta()
        sessionProfiles.clear()
        sessionApps.clear()
        sessionDay = TrafficStatsDb.formatDay()
    }

    @Synchronized
    fun getPendingJson(): String {
        return buildStatsJson(pendingDay, pendingDaily, pendingProfiles.values, pendingApps.values)
    }

    @Synchronized
    fun getSessionJson(): String {
        return buildStatsJson(sessionDay, sessionSummary, sessionProfiles.values, sessionApps.values)
    }

    private fun buildStatsJson(
        day: String,
        summary: TrafficStatsDb.DailyDelta,
        profiles: Collection<TrafficStatsDb.ProfileDelta>,
        apps: Collection<TrafficStatsDb.AppDelta>,
    ): String {
        val root = JSONObject()
        root.put("day", day)
        root.put("summary", JSONObject().apply {
            put("proxyRx", summary.proxyRx)
            put("proxyTx", summary.proxyTx)
            put("directRx", summary.directRx)
            put("directTx", summary.directTx)
        })
        val pArr = JSONArray()
        for (p in profiles) {
            if (p.rx + p.tx <= 0L) continue
            pArr.put(JSONObject().apply {
                put("profileId", p.profileId)
                put("profileName", p.profileName)
                put("profileType", p.profileType)
                put("rx", p.rx)
                put("tx", p.tx)
            })
        }
        root.put("profiles", pArr)

        val aArr = JSONArray()
        for (a in apps) {
            if (a.proxyRx + a.proxyTx + a.directRx + a.directTx <= 0L) continue
            aArr.put(JSONObject().apply {
                put("appKey", a.appKey)
                put("uid", a.uid)
                put("packageName", a.packageName)
                put("proxyRx", a.proxyRx)
                put("proxyTx", a.proxyTx)
                put("directRx", a.directRx)
                put("directTx", a.directTx)
            })
        }
        root.put("apps", aArr)
        return root.toString()
    }

    private fun rollDayIfNeeded() {
        val today = TrafficStatsDb.formatDay()
        if (today == pendingDay) return
        val oldDay = pendingDay
        val oldDaily = pendingDaily
        val oldProfiles = pendingProfiles.values.toList()
        val oldApps = pendingApps.values.toList()
        pendingDay = today
        pendingDaily = TrafficStatsDb.DailyDelta()
        pendingProfiles.clear()
        pendingApps.clear()
        sessionDay = today
        if (!oldDaily.isEmpty || oldProfiles.isNotEmpty() || oldApps.isNotEmpty()) {
            TrafficStatsDb.addBatch(oldDay, oldDaily, oldProfiles, oldApps)
        }
    }
}
