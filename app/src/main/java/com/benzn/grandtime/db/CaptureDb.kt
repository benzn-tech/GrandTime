package com.benzn.grandtime.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CaptureRecord::class], version = 2, exportSchema = false)
abstract class CaptureDb : RoomDatabase() {
    abstract fun captureRecords(): CaptureRecordDao

    companion object {
        @Volatile private var instance: CaptureDb? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE capture_records ADD COLUMN siteId TEXT")
            }
        }

        fun get(context: Context): CaptureDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CaptureDb::class.java, "capture.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
