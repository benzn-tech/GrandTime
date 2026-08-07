package com.benzn.grandtime.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CaptureRecord::class], version = 5, exportSchema = false)
abstract class CaptureDb : RoomDatabase() {
    abstract fun captureRecords(): CaptureRecordDao

    companion object {
        @Volatile private var instance: CaptureDb? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE capture_records ADD COLUMN siteId TEXT")
            }
        }

        // gpsTrack:GPS 轨迹点 JSON 数组字符串(spec §P3),null=无轨迹。
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE capture_records ADD COLUMN gpsTrack TEXT")
            }
        }

        // groupId:多设备合并中主设备的 session id(spec 2026-08-04),null=单机录制。
        // 持久化而非只在内存里,是因为 /open 是 best-effort:离线时那次调用会失败,
        // 而组关系丢了无法重建,必须跟着录音行一起活下来。
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE capture_records ADD COLUMN groupId TEXT")
            }
        }

        /**
         * Upload honesty (spec 2026-08-06). "failed" used to mean three different things —
         * backing off, gone for good, and stale — so every existing row moves to the one
         * that is true of all of them: it will be tried again.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE capture_records ADD COLUMN failureClass TEXT")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN failureCode TEXT")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN frozenAtBuild TEXT")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN frozenSinceMs INTEGER")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN frozenCreditMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE capture_records SET uploadStatus = 'retrying' WHERE uploadStatus = 'failed'")
            }
        }

        fun get(context: Context): CaptureDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CaptureDb::class.java, "capture.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }
    }
}
