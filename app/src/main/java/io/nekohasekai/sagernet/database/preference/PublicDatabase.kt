package io.nekohasekai.sagernet.database.preference

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File

@Database(entities = [KeyValuePair::class], version = 1)
@GenerateRoomMigrations
abstract class PublicDatabase : RoomDatabase() {
    companion object {
        val instance by lazy {
            val app = SagerNet.application
            app.getDatabasePath(Key.DB_PUBLIC).parentFile?.mkdirs()
            fun buildDatabase(): PublicDatabase {
                return Room.databaseBuilder(app, PublicDatabase::class.java, Key.DB_PUBLIC)
                    .setJournalMode(JournalMode.AUTOMATIC)
                    .allowMainThreadQueries()
                    .enableMultiInstanceInvalidation()
                    .fallbackToDestructiveMigration()
                    .setQueryExecutor(Dispatchers.IO.asExecutor())
                    .build()
            }
            try {
                val db = buildDatabase()
                db.openHelper.writableDatabase
                db
            } catch (e: Throwable) {
                Logs.e("PublicDatabase initial open failed, performing emergency backup", e)
                val timestamp = System.currentTimeMillis()
                try {
                    val dbFile = app.getDatabasePath(Key.DB_PUBLIC)
                    if (dbFile.exists()) {
                        val parent = dbFile.parentFile
                        dbFile.copyTo(File(parent, "${Key.DB_PUBLIC}.bak_$timestamp"), overwrite = true)
                        val wal = File(parent, "${Key.DB_PUBLIC}-wal")
                        if (wal.exists()) wal.copyTo(File(parent, "${Key.DB_PUBLIC}-wal.bak_$timestamp"), overwrite = true)
                        val shm = File(parent, "${Key.DB_PUBLIC}-shm")
                        if (shm.exists()) shm.copyTo(File(parent, "${Key.DB_PUBLIC}-shm.bak_$timestamp"), overwrite = true)
                    }
                } catch (t: Throwable) {
                    Logs.e("Failed to create emergency backup for configuration database", t)
                }
                try {
                    val recoveredDb = buildDatabase()
                    recoveredDb.openHelper.writableDatabase
                    recoveredDb
                } catch (fatal: Throwable) {
                    Logs.e("PublicDatabase unrecoverable after backup, recreating clean database", fatal)
                    try {
                        app.deleteDatabase(Key.DB_PUBLIC)
                    } catch (t: Throwable) {
                        Logs.e(t)
                    }
                    buildDatabase()
                }
            }
        }

        val kvPairDao get() = instance.keyValuePairDao()
    }

    abstract fun keyValuePairDao(): KeyValuePair.Dao

}
