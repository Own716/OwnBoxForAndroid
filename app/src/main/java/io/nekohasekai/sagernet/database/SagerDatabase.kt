package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.asExecutor

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    val cursor = database.query("PRAGMA table_info(proxy_entities)")
                    var hasColumn = false
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == "balancerBean") {
                            hasColumn = true
                            break
                        }
                    }
                    cursor.close()
                    if (!hasColumn) {
                        database.execSQL("ALTER TABLE proxy_entities ADD COLUMN balancerBean BLOB DEFAULT NULL")
                    }
                } catch (e: Throwable) {
                    Logs.w(e)
                }
            }
        }

        val instance by lazy {
            val app = SagerNet.application
            app.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            fun buildDatabase(): SagerDatabase {
                return Room.databaseBuilder(app, SagerDatabase::class.java, Key.DB_PROFILE)
                    .setJournalMode(JournalMode.AUTOMATIC)
                    .allowMainThreadQueries()
                    .enableMultiInstanceInvalidation()
                    .addMigrations(MIGRATION_9_10)
                    .setQueryExecutor(kotlinx.coroutines.Dispatchers.IO.asExecutor())
                    .build()
            }
            try {
                val db = buildDatabase()
                db.openHelper.writableDatabase
                db
            } catch (e: Throwable) {
                Logs.e("SagerDatabase initial open failed, performing emergency backup", e)
                val timestamp = System.currentTimeMillis()
                try {
                    val dbFile = app.getDatabasePath(Key.DB_PROFILE)
                    if (dbFile.exists()) {
                        val parent = dbFile.parentFile
                        dbFile.copyTo(java.io.File(parent, "${Key.DB_PROFILE}.bak_$timestamp"), overwrite = true)
                        val wal = java.io.File(parent, "${Key.DB_PROFILE}-wal")
                        if (wal.exists()) wal.copyTo(java.io.File(parent, "${Key.DB_PROFILE}-wal.bak_$timestamp"), overwrite = true)
                        val shm = java.io.File(parent, "${Key.DB_PROFILE}-shm")
                        if (shm.exists()) shm.copyTo(java.io.File(parent, "${Key.DB_PROFILE}-shm.bak_$timestamp"), overwrite = true)
                    }
                } catch (t: Throwable) {
                    Logs.e("Failed to create emergency backup for corrupted database", t)
                }
                // Try recovery: attempt to reopen before any destructive actions
                try {
                    val recoveredDb = buildDatabase()
                    recoveredDb.openHelper.writableDatabase
                    recoveredDb
                } catch (fatal: Throwable) {
                    Logs.e("SagerDatabase unrecoverable after backup, recreating clean database", fatal)
                    try {
                        app.deleteDatabase(Key.DB_PROFILE)
                    } catch (t: Throwable) {
                        Logs.e(t)
                    }
                    buildDatabase()
                }
            }
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
