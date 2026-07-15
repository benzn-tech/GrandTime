package com.benzn.grandtime.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CaptureRecord::class], version = 3, exportSchema = false)
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

        fun get(context: Context): CaptureDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CaptureDb::class.java, "capture.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
