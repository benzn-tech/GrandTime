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

    /** Records created at/after [sinceMs] (e.g. local midnight) — used by Home to aggregate
     *  today's uploads at the recording level rather than the per-segment status counts. */
    @Query("SELECT * FROM capture_records WHERE missing = 0 AND createdAt >= :sinceMs ORDER BY startedAt DESC")
    fun observeSince(sinceMs: Long): Flow<List<CaptureRecord>>

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

    /**
     * DO NOT call this on sign-in.
     *
     * It claims EVERY unowned row for whoever just signed in. On a device that
     * rotates between clients that hands the previous client's pending
     * recordings to the next one, which then uploads them under their account.
     * Kept only for a one-off migration of rows created before ownership was
     * stamped at capture time.
     */
    @Query("UPDATE capture_records SET authorSub = :sub WHERE authorSub IS NULL")
    suspend fun backfillAuthorSub(sub: String)

    /**
     * The signed-in account's own unsent work.
     *
     * A NULL authorSub matches nobody on purpose: a row recorded before
     * ownership was stamped belongs to whoever made it, that is no longer
     * knowable, and uploading it under the current account would attribute one
     * client's recording to another.
     */
    @Query(
        "SELECT * FROM capture_records WHERE uploadStatus IN (:statuses) " +
            "AND missing = 0 AND authorSub = :authorSub"
    )
    suspend fun listPendingForAuthor(statuses: List<String>, authorSub: String): List<CaptureRecord>

    /** Unsent rows that can never be uploaded because their recorder is unknown. Surfaced, not hidden. */
    @Query(
        "SELECT COUNT(*) FROM capture_records WHERE " +
            "uploadStatus IN ('pending','failed','uploading','retrying','frozen') AND missing = 0 AND authorSub IS NULL"
    )
    suspend fun countOrphanedPending(): Int

    /** How much this account leaves behind if it signs out now. */
    @Query(
        "SELECT COUNT(*) FROM capture_records WHERE " +
            "uploadStatus IN ('pending','failed','uploading','retrying','frozen') AND missing = 0 AND authorSub = :authorSub"
    )
    suspend fun countPendingForAuthor(authorSub: String): Int

    @Query("UPDATE capture_records SET siteId = :siteId WHERE id = :id")
    suspend fun setSiteId(id: String, siteId: String?)

    @Query("UPDATE capture_records SET uploadStatus = :status WHERE id = :id")
    suspend fun markUploadStatus(id: String, status: String)

    @Query("SELECT * FROM capture_records WHERE uploadStatus IN (:statuses) AND missing = 0")
    suspend fun listByUploadStatus(statuses: List<String>): List<CaptureRecord>

    @Query(
        "UPDATE capture_records SET uploadStatus = 'frozen', failureClass = :cls, " +
            "failureCode = :code, frozenAtBuild = :build, frozenSinceMs = :sinceMs, " +
            "lastAttemptAt = :now WHERE id = :id"
    )
    suspend fun freeze(id: String, cls: String, code: String, build: String?, sinceMs: Long, now: Long)

    @Query(
        "UPDATE capture_records SET uploadStatus = 'pending', failureClass = NULL, " +
            "failureCode = NULL, frozenAtBuild = NULL, frozenSinceMs = NULL, " +
            "frozenCreditMs = :creditMs WHERE id = :id"
    )
    suspend fun thaw(id: String, creditMs: Long)

    @Query("UPDATE capture_records SET frozenAtBuild = :build WHERE id = :id")
    suspend fun adoptFrozenBuild(id: String, build: String)

    @Query(
        "UPDATE capture_records SET uploadStatus = 'dead', failureClass = :cls, " +
            "failureCode = :code, lastAttemptAt = :now WHERE id = :id"
    )
    suspend fun markDead(id: String, cls: String, code: String, now: Long)

    @Query(
        "UPDATE capture_records SET uploadStatus = 'retrying', failureClass = :cls, " +
            "failureCode = :code, lastAttemptAt = :now WHERE id = :id"
    )
    suspend fun markRetrying(id: String, cls: String, code: String?, now: Long)

    @Query("SELECT * FROM capture_records WHERE uploadStatus = 'frozen' AND missing = 0")
    suspend fun listFrozen(): List<CaptureRecord>

    /** Oldest still-unsent recording, for the ledger's backlog age. Null when nothing waits. */
    @Query(
        "SELECT MIN(startedAt) FROM capture_records WHERE " +
            "uploadStatus IN ('pending','uploading','retrying','frozen') AND missing = 0"
    )
    suspend fun oldestUnsentStartedAt(): Long?

    /**
     * Dead rows for the status probe. Deliberately NOT filtered on `missing`: a vanished
     * file is invisible on the device by design, but the ledger must still hear about it.
     */
    @Query("SELECT COUNT(*) FROM capture_records WHERE uploadStatus = 'dead'")
    suspend fun countDead(): Int

    @Query(
        "SELECT COUNT(*) FROM capture_records WHERE " +
            "uploadStatus IN ('pending','uploading','retrying') AND missing = 0"
    )
    suspend fun countUnsent(): Int

    @Query(
        "SELECT DISTINCT failureCode FROM capture_records " +
            "WHERE uploadStatus = 'frozen' AND failureCode IS NOT NULL"
    )
    suspend fun frozenFingerprints(): List<String>

    @Query("SELECT uploadStatus AS status, COUNT(*) AS n FROM capture_records WHERE missing = 0 GROUP BY uploadStatus")
    fun observeUploadStatusCounts(): Flow<List<UploadStatusCount>>

    @Query("SELECT uploadStatus AS status, COUNT(*) AS n FROM capture_records WHERE missing = 0 AND createdAt >= :sinceMs GROUP BY uploadStatus")
    fun observeUploadStatusCountsSince(sinceMs: Long): Flow<List<UploadStatusCount>>

    /** Projection for [observeUploadStatusCounts]: one row per distinct uploadStatus value. */
    data class UploadStatusCount(val status: String, val n: Int)
}
