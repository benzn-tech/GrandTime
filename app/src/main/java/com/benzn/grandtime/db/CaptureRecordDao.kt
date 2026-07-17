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

    @Query("SELECT * FROM capture_records WHERE id = :id")
    suspend fun getById(id: String): CaptureRecord?

    @Query("UPDATE capture_records SET missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<String>)

    @Query("UPDATE capture_records SET filePath = :newPath, missing = 0 WHERE filePath = :oldPath")
    suspend fun updatePath(oldPath: String, newPath: String)

    @Query("UPDATE capture_records SET gpsTrack = :json WHERE id = :id")
    suspend fun updateGpsTrack(id: String, json: String)

    @Query("UPDATE capture_records SET authorSub = :sub WHERE authorSub IS NULL")
    suspend fun backfillAuthorSub(sub: String)

    @Query("UPDATE capture_records SET siteId = :siteId WHERE id = :id")
    suspend fun setSiteId(id: String, siteId: String?)

    @Query("UPDATE capture_records SET uploadStatus = :status WHERE id = :id")
    suspend fun markUploadStatus(id: String, status: String)

    @Query("SELECT * FROM capture_records WHERE uploadStatus IN (:statuses) AND missing = 0")
    suspend fun listByUploadStatus(statuses: List<String>): List<CaptureRecord>

    @Query("SELECT uploadStatus AS status, COUNT(*) AS n FROM capture_records WHERE missing = 0 GROUP BY uploadStatus")
    fun observeUploadStatusCounts(): Flow<List<UploadStatusCount>>

    @Query("SELECT uploadStatus AS status, COUNT(*) AS n FROM capture_records WHERE missing = 0 AND createdAt >= :sinceMs GROUP BY uploadStatus")
    fun observeUploadStatusCountsSince(sinceMs: Long): Flow<List<UploadStatusCount>>

    /** Projection for [observeUploadStatusCounts]: one row per distinct uploadStatus value. */
    data class UploadStatusCount(val status: String, val n: Int)
}
