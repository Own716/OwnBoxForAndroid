package io.nekohasekai.sagernet.utils

import android.os.Parcel
import android.os.Parcelable
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.toStringPretty
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private fun Parcelable.toBase64Str(): String {
    val parcel = Parcel.obtain()
    writeToParcel(parcel, 0)
    try {
        return Util.b64EncodeUrlSafe(parcel.marshall())
    } finally {
        parcel.recycle()
    }
}

object BackupHelper {

    fun doBackup(
        profile: Boolean = true,
        rule: Boolean = true,
        setting: Boolean = true
    ): ByteArray {
        val out = JSONObject().apply {
            if (profile) {
                put("proxies", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty().toByteArray(Charsets.UTF_8)
    }

    fun autoBackupLocal(): Boolean {
        return try {
            val baseDir = app.getExternalFilesDir("backup") ?: app.filesDir
            val backupDir = File(baseDir, "auto_backup").apply { mkdirs() }
            val data = doBackup()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(backupDir, "backup_$timestamp.json")
            file.writeBytes(data)

            // Retain up to 5 newest backups
            val existing = backupDir.listFiles { f ->
                f.name.startsWith("backup_") && f.name.endsWith(".json")
            }?.sortedBy { it.lastModified() }

            if (existing != null && existing.size > 5) {
                existing.take(existing.size - 5).forEach { it.delete() }
            }
            true
        } catch (e: Exception) {
            Logs.w(e)
            false
        }
    }
}
