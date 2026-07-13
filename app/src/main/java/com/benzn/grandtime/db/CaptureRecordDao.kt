package com.benzn.grandtime.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CaptureRecord)

    @Query("UPDATE capture_records SET endedAt = :endedAt, durationMs = :durationMs, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun finalize(id: String, endedAt: Long, durationMs: Long, sizeBytes: Long)

    @Query("SELECT * FROM capture_records WHERE missing = 0 ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CaptureRecord>>

    @Query("SELECT * FROM capture_records")
    suspend fun listAll(): List<CaptureRecord>

    @Query("UPDATE capture_records SET missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<String>)

    @Query("UPDATE capture_records SET filePath = :newPath, missing = 0 WHERE filePath = :oldPath")
    suspend fun updatePath(oldPath: String, newPath: String)

    @Query("UPDATE capture_records SET authorSub = :sub WHERE authorSub IS NULL")
    suspend fun backfillAuthorSub(sub: String)
}
