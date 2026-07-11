package com.benzn.grandtime.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CaptureRecord::class], version = 1, exportSchema = false)
abstract class CaptureDb : RoomDatabase() {
    abstract fun captureRecords(): CaptureRecordDao

    companion object {
        @Volatile private var instance: CaptureDb? = null

        fun get(context: Context): CaptureDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CaptureDb::class.java, "capture.db")
                .build()
                .also { instance = it }
        }
    }
}
