package io.nekohasekai.sagernet.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TrafficStatsDb {

    private const val DB_NAME = "traffic_stats.db"
    private const val DB_VERSION = 1

    data class TrafficSummary(
        val proxyRx: Long = 0L,
        val proxyTx: Long = 0L,
        val directRx: Long = 0L,
        val directTx: Long = 0L,
    ) {
        val totalProxy: Long get() = proxyRx + proxyTx
        val totalDirect: Long get() = directRx + directTx
        val totalRx: Long get() = proxyRx + directRx
        val totalTx: Long get() = proxyTx + directTx
        val totalAll: Long get() = totalProxy + totalDirect
    }

    data class DailyRow(
        val day: String,
        val proxyRx: Long,
        val proxyTx: Long,
        val directRx: Long,
        val directTx: Long,
    ) {
        val totalProxy: Long get() = proxyRx + proxyTx
        val totalDirect: Long get() = directRx + directTx
        val totalAll: Long get() = totalProxy + totalDirect
    }

    data class ProfileRow(
        val profileId: Long,
        val profileName: String,
        val profileType: String,
        val rx: Long,
        val tx: Long,
    ) {
        val total: Long get() = rx + tx
    }

    data class AppRow(
        val appKey: String,
        val uid: Int,
        val packageName: String,
        val proxyRx: Long,
        val proxyTx: Long,
        val directRx: Long,
        val directTx: Long,
    ) {
        val totalProxy: Long get() = proxyRx + proxyTx
        val totalDirect: Long get() = directRx + directTx
        val totalAll: Long get() = totalProxy + totalDirect
    }

    data class DailyDelta(
        var proxyRx: Long = 0L,
        var proxyTx: Long = 0L,
        var directRx: Long = 0L,
        var directTx: Long = 0L,
    ) {
        val isEmpty: Boolean get() = proxyRx == 0L && proxyTx == 0L && directRx == 0L && directTx == 0L
    }

    data class ProfileDelta(
        val profileId: Long,
        var profileName: String,
        var profileType: String,
        var rx: Long = 0L,
        var tx: Long = 0L,
    )

    data class AppDelta(
        val appKey: String,
        val uid: Int,
        val packageName: String,
        var proxyRx: Long = 0L,
        var proxyTx: Long = 0L,
        var directRx: Long = 0L,
        var directTx: Long = 0L,
    )

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            runCatching {
                db.rawQuery("PRAGMA busy_timeout=3000", null)?.close()
            }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS daily_traffic (
                    day TEXT PRIMARY KEY,
                    proxy_rx INTEGER NOT NULL DEFAULT 0,
                    proxy_tx INTEGER NOT NULL DEFAULT 0,
                    direct_rx INTEGER NOT NULL DEFAULT 0,
                    direct_tx INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS daily_profile_traffic (
                    day TEXT NOT NULL,
                    profile_id INTEGER NOT NULL,
                    profile_name TEXT NOT NULL DEFAULT '',
                    profile_type TEXT NOT NULL DEFAULT '',
                    rx INTEGER NOT NULL DEFAULT 0,
                    tx INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (day, profile_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS daily_app_traffic (
                    day TEXT NOT NULL,
                    app_key TEXT NOT NULL,
                    uid INTEGER NOT NULL DEFAULT -1,
                    package_name TEXT NOT NULL DEFAULT '',
                    proxy_rx INTEGER NOT NULL DEFAULT 0,
                    proxy_tx INTEGER NOT NULL DEFAULT 0,
                    direct_rx INTEGER NOT NULL DEFAULT 0,
                    direct_tx INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (day, app_key)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_profile_day ON daily_profile_traffic(day)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_app_day ON daily_app_traffic(day)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 initial schema
        }
    }

    private val helper: Helper by lazy {
        Helper(SagerNet.application)
    }

    fun formatDay(timeMs: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(timeMs))
    }

    fun dayDaysAgo(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(cal.time)
    }

    @Synchronized
    fun addBatch(
        day: String,
        daily: DailyDelta?,
        profiles: List<ProfileDelta>,
        apps: List<AppDelta>,
    ) {
        val hasDaily = daily != null && !daily.isEmpty
        if (!hasDaily && profiles.isEmpty() && apps.isEmpty()) return
        try {
            val db = helper.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                if (hasDaily && daily != null) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO daily_traffic (day, proxy_rx, proxy_tx, direct_rx, direct_tx) VALUES (?, 0, 0, 0, 0)",
                        arrayOf(day)
                    )
                    db.execSQL(
                        "UPDATE daily_traffic SET proxy_rx = proxy_rx + ?, proxy_tx = proxy_tx + ?, direct_rx = direct_rx + ?, direct_tx = direct_tx + ? WHERE day = ?",
                        arrayOf(daily.proxyRx, daily.proxyTx, daily.directRx, daily.directTx, day)
                    )
                }

                for (p in profiles) {
                    if (p.rx <= 0L && p.tx <= 0L) continue
                    db.execSQL(
                        "INSERT OR IGNORE INTO daily_profile_traffic (day, profile_id, profile_name, profile_type, rx, tx) VALUES (?, ?, ?, ?, 0, 0)",
                        arrayOf(day, p.profileId, p.profileName, p.profileType)
                    )
                    db.execSQL(
                        "UPDATE daily_profile_traffic SET profile_name = CASE WHEN ? != '' THEN ? ELSE profile_name END, profile_type = CASE WHEN ? != '' THEN ? ELSE profile_type END, rx = rx + ?, tx = tx + ? WHERE day = ? AND profile_id = ?",
                        arrayOf(p.profileName, p.profileName, p.profileType, p.profileType, p.rx, p.tx, day, p.profileId)
                    )
                }

                for (a in apps) {
                    if (a.proxyRx <= 0L && a.proxyTx <= 0L && a.directRx <= 0L && a.directTx <= 0L) continue
                    db.execSQL(
                        "INSERT OR IGNORE INTO daily_app_traffic (day, app_key, uid, package_name, proxy_rx, proxy_tx, direct_rx, direct_tx) VALUES (?, ?, ?, ?, 0, 0, 0, 0)",
                        arrayOf(day, a.appKey, a.uid, a.packageName)
                    )
                    db.execSQL(
                        "UPDATE daily_app_traffic SET uid = CASE WHEN ? >= 0 THEN ? ELSE uid END, package_name = CASE WHEN ? != '' THEN ? ELSE package_name END, proxy_rx = proxy_rx + ?, proxy_tx = proxy_tx + ?, direct_rx = direct_rx + ?, direct_tx = direct_tx + ? WHERE day = ? AND app_key = ?",
                        arrayOf(a.uid, a.uid, a.packageName, a.packageName, a.proxyRx, a.proxyTx, a.directRx, a.directTx, day, a.appKey)
                    )
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Logs.w("TrafficStatsDb addBatch failed", t)
        }
    }

    @Synchronized
    fun pruneOlderThanDays(days: Int = 90) {
        val cutoff = dayDaysAgo(days)
        try {
            val db = helper.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.delete("daily_traffic", "day < ?", arrayOf(cutoff))
                db.delete("daily_profile_traffic", "day < ?", arrayOf(cutoff))
                db.delete("daily_app_traffic", "day < ?", arrayOf(cutoff))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Logs.w("TrafficStatsDb prune failed", t)
        }
    }

    @Synchronized
    fun deleteProfileStats(profileId: Long) {
        if (profileId <= 0L) return
        try {
            val db = helper.writableDatabase
            db.delete("daily_profile_traffic", "profile_id = ?", arrayOf(profileId.toString()))
        } catch (t: Throwable) {
            Logs.w("TrafficStatsDb deleteProfileStats failed", t)
        }
    }

    @Synchronized
    fun clearAll() {
        try {
            val db = helper.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.delete("daily_traffic", null, null)
                db.delete("daily_profile_traffic", null, null)
                db.delete("daily_app_traffic", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Logs.w("TrafficStatsDb clearAll failed", t)
        }
    }

    fun querySummary(sinceDay: String?): TrafficSummary {
        return try {
            val db = helper.readableDatabase
            val sql = if (sinceDay != null) {
                "SELECT COALESCE(SUM(proxy_rx),0), COALESCE(SUM(proxy_tx),0), COALESCE(SUM(direct_rx),0), COALESCE(SUM(direct_tx),0) FROM daily_traffic WHERE day >= ?"
            } else {
                "SELECT COALESCE(SUM(proxy_rx),0), COALESCE(SUM(proxy_tx),0), COALESCE(SUM(direct_rx),0), COALESCE(SUM(direct_tx),0) FROM daily_traffic"
            }
            val args = if (sinceDay != null) arrayOf(sinceDay) else null
            db.rawQuery(sql, args).use { c ->
                if (c.moveToFirst()) {
                    TrafficSummary(
                        proxyRx = c.getLong(0),
                        proxyTx = c.getLong(1),
                        directRx = c.getLong(2),
                        directTx = c.getLong(3),
                    )
                } else {
                    TrafficSummary()
                }
            }
        } catch (t: Throwable) {
            Logs.w("TrafficStatsDb querySummary failed", t)
            TrafficSummary()
        }
    }

    fun queryDailyRows(sinceDay: String?): List<DailyRow> {
        val list = ArrayList<DailyRow>()
        try {
            val db = helper.readableDatabase
            val sql = if (sinceDay != null) {
                "SELECT day, proxy_rx, proxy_tx, direct_rx, direct_tx FROM daily_traffic WHERE day >= ? ORDER BY day DESC"
            } else {
                "SELECT day, proxy_rx, proxy_tx, direct_rx, direct_tx FROM daily_traffic ORDER BY day DESC"
            }
            val args = if (sinceDay != null) arrayOf(sinceDay) else null
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val row = DailyRow(
                        day = c.getString(0),
                        proxyRx = c.getLong(1),
                        proxyTx = c.getLong(2),
                        directRx = c.getLong(3),
                        directTx = c.getLong(4),
                    )
                    if (row.totalAll > 0L) list.add(row)
                }
            }
        } catch (t: Throwable) {
            Logs.w("TrafficStatsDb queryDailyRows failed", t)
        }
        return list
    }

    fun queryProfileRows(sinceDay: String?): List<ProfileRow> {
        val list = ArrayList<ProfileRow>()
        try {
            val db = helper.readableDatabase
            val where = if (sinceDay != null) "WHERE day >= ? " else ""
            val sql = """
                SELECT profile_id, MAX(profile_name), MAX(profile_type), SUM(rx), SUM(tx)
                FROM daily_profile_traffic
                $where
                GROUP BY profile_id
                ORDER BY (SUM(rx) + SUM(tx)) DESC
            """.trimIndent()
            val args = if (sinceDay != null) arrayOf(sinceDay) else null
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val rx = c.getLong(3)
                    val tx = c.getLong(4)
                    if (rx + tx > 0L) {
                        list.add(
                            ProfileRow(
                                profileId = c.getLong(0),
                                profileName = c.getString(1).orEmpty(),
                                profileType = c.getString(2).orEmpty(),
                                rx = rx,
                                tx = tx,
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Logs.w("TrafficStatsDb queryProfileRows failed", t)
        }
        return list
    }

    fun queryAppRows(sinceDay: String?): List<AppRow> {
        val list = ArrayList<AppRow>()
        try {
            val db = helper.readableDatabase
            val where = if (sinceDay != null) "WHERE day >= ? " else ""
            val sql = """
                SELECT app_key, MAX(uid), MAX(package_name), SUM(proxy_rx), SUM(proxy_tx), SUM(direct_rx), SUM(direct_tx)
                FROM daily_app_traffic
                $where
                GROUP BY app_key
                ORDER BY (SUM(proxy_rx) + SUM(proxy_tx) + SUM(direct_rx) + SUM(direct_tx)) DESC
            """.trimIndent()
            val args = if (sinceDay != null) arrayOf(sinceDay) else null
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val pRx = c.getLong(3)
                    val pTx = c.getLong(4)
                    val dRx = c.getLong(5)
                    val dTx = c.getLong(6)
                    if (pRx + pTx + dRx + dTx > 0L) {
                        list.add(
                            AppRow(
                                appKey = c.getString(0),
                                uid = c.getInt(1),
                                packageName = c.getString(2).orEmpty(),
                                proxyRx = pRx,
                                proxyTx = pTx,
                                directRx = dRx,
                                directTx = dTx,
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Logs.w("TrafficStatsDb queryAppRows failed", t)
        }
        return list
    }
}
