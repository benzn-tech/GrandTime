package com.benzn.grandtime.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class FinalizeCall(val id: String, val endedAt: Long, val durationMs: Long, val sizeBytes: Long)

private class FakeDao : CaptureRecordDao {
    val rows = mutableListOf<CaptureRecord>()
    val missingIds = mutableListOf<String>()
    val finalizedCalls = mutableListOf<FinalizeCall>()
    override suspend fun insert(record: CaptureRecord) { rows.add(record) }
    override suspend fun finalize(id: String, endedAt: Long, durationMs: Long, sizeBytes: Long) {
        finalizedCalls.add(FinalizeCall(id, endedAt, durationMs, sizeBytes))
    }
    override fun observeAll(): Flow<List<CaptureRecord>> = flowOf(rows)
    override fun observeSince(sinceMs: Long): Flow<List<CaptureRecord>> =
        flowOf(rows.filter { !it.missing && it.createdAt >= sinceMs })
    override suspend fun listAll(): List<CaptureRecord> = rows.toList()
    override suspend fun getById(id: String): CaptureRecord? = rows.firstOrNull { it.id == id }
    override suspend fun markMissing(ids: List<String>) { missingIds.addAll(ids) }
    override suspend fun updatePath(oldPath: String, newPath: String) {
        val idx = rows.indexOfFirst { it.filePath == oldPath }
        if (idx >= 0) rows[idx] = rows[idx].copy(filePath = newPath)
    }
    override suspend fun updateGpsTrack(id: String, json: String) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(gpsTrack = json)
    }
    override suspend fun backfillAuthorSub(sub: String) {
        for (i in rows.indices) {
            if (rows[i].authorSub == null) rows[i] = rows[i].copy(authorSub = sub)
        }
    }
    override suspend fun listPendingForAuthor(statuses: List<String>, authorSub: String) =
        rows.filter { it.uploadStatus in statuses && !it.missing && it.authorSub == authorSub }
    override suspend fun countOrphanedPending() = rows.count {
        it.uploadStatus in UNSENT_STATUSES && !it.missing && it.authorSub == null
    }
    override suspend fun countPendingForAuthor(authorSub: String) = rows.count {
        it.uploadStatus in UNSENT_STATUSES && !it.missing && it.authorSub == authorSub
    }
    override suspend fun setSiteId(id: String, siteId: String?) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(siteId = siteId)
    }
    override suspend fun markUploadStatus(id: String, status: String) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(uploadStatus = status)
    }
    override suspend fun listByUploadStatus(statuses: List<String>): List<CaptureRecord> =
        rows.filter { it.uploadStatus in statuses && !it.missing }
    // --- v5 freeze/thaw surface (spec 2026-08-06) ---
    override suspend fun freeze(id: String, cls: String, code: String, build: String?, sinceMs: Long, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(
            uploadStatus = "frozen", failureClass = cls, failureCode = code,
            frozenAtBuild = build, frozenSinceMs = sinceMs, lastAttemptAt = now,
        )
    }
    override suspend fun thaw(id: String, creditMs: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(
            uploadStatus = "pending", failureClass = null, failureCode = null,
            frozenAtBuild = null, frozenSinceMs = null, frozenCreditMs = creditMs,
        )
    }
    override suspend fun adoptFrozenBuild(id: String, build: String) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(frozenAtBuild = build)
    }
    override suspend fun markDead(id: String, cls: String, code: String, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(
            uploadStatus = "dead", failureClass = cls, failureCode = code, lastAttemptAt = now,
        )
    }
    override suspend fun markRetrying(id: String, cls: String, code: String?, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(
            uploadStatus = "retrying", failureClass = cls, failureCode = code, lastAttemptAt = now,
        )
    }
    override suspend fun listFrozen(): List<CaptureRecord> =
        rows.filter { it.uploadStatus == "frozen" && !it.missing }
    override suspend fun oldestUnsentStartedAt(): Long? =
        rows.filter { it.uploadStatus in setOf("pending", "uploading", "retrying", "frozen") && !it.missing }
            .minOfOrNull { it.startedAt }
    override suspend fun countDead(): Int = rows.count { it.uploadStatus == "dead" }
    override suspend fun countUnsent(): Int =
        rows.count { it.uploadStatus in setOf("pending", "uploading", "retrying") && !it.missing }
    override suspend fun frozenFingerprints(): List<String> =
        rows.filter { it.uploadStatus == "frozen" }.mapNotNull { it.failureCode }.distinct()

    override fun observeUploadStatusCounts(): Flow<List<CaptureRecordDao.UploadStatusCount>> =
        flowOf(
            rows.filter { !it.missing }
                .groupingBy { it.uploadStatus }
                .eachCount()
                .map { (status, n) -> CaptureRecordDao.UploadStatusCount(status, n) }
        )
    override fun observeUploadStatusCountsSince(sinceMs: Long): Flow<List<CaptureRecordDao.UploadStatusCount>> =
        flowOf(
            rows.filter { !it.missing && it.createdAt >= sinceMs }
                .groupingBy { it.uploadStatus }
                .eachCount()
                .map { (status, n) -> CaptureRecordDao.UploadStatusCount(status, n) }
        )
}

class FilesReconcilerTest {

    private fun record(id: String, path: String) = CaptureRecord(
        id = id, kind = "video", filePath = path, fileName = path.substringAfterLast('/'),
        startedAt = 1L, codec = "h264", sessionId = "s", createdAt = 1L,
    )

    @Test
    fun `disk file without db row gets inserted with duration`() = runTest {
        val dao = FakeDao()
        val reconciler = FilesReconciler(dao, durationReader = { 42_000L }, clock = { 99L })
        reconciler.reconcile(
            listOf(FilesReconciler.DiskFile("/m/video/VID_1.mp4", "VID_1.mp4", "video", 10L, 5L))
        )
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals("VID_1.mp4", row.fileName)
        assertEquals("video", row.kind)
        assertEquals(42_000L, row.durationMs)
        assertEquals(5L, row.startedAt)
        assertEquals(99L, row.createdAt)
    }

    @Test
    fun `db row without disk file marked missing`() = runTest {
        val dao = FakeDao()
        dao.rows.add(record("gone", "/m/video/GONE.mp4"))
        val reconciler = FilesReconciler(dao, durationReader = { null }, clock = { 0L })
        reconciler.reconcile(emptyList())
        assertEquals(listOf("gone"), dao.missingIds)
    }

    @Test
    fun `legacy Android data path missing on disk is not marked missing, normal public path still is`() = runTest {
        val dao = FakeDao()
        dao.rows.add(record("legacy", "/storage/emulated/0/Android/data/com.benzn.grandtime/files/media/video/OLD.mp4"))
        dao.rows.add(record("public-gone", "/storage/emulated/0/FieldSight/device/video/GONE.mp4"))
        val reconciler = FilesReconciler(dao, durationReader = { null }, clock = { 0L })
        reconciler.reconcile(emptyList())
        assertEquals(listOf("public-gone"), dao.missingIds)
    }

    @Test
    fun `matching rows untouched`() = runTest {
        val dao = FakeDao()
        dao.rows.add(record("keep", "/m/video/KEEP.mp4"))
        val reconciler = FilesReconciler(dao, durationReader = { null }, clock = { 0L })
        reconciler.reconcile(
            listOf(FilesReconciler.DiskFile("/m/video/KEEP.mp4", "KEEP.mp4", "video", 10L, 5L))
        )
        assertEquals(1, dao.rows.size)
        assertTrue(dao.missingIds.isEmpty())
    }

    @Test
    fun `orphan row with null endedAt and existing disk file gets repaired`() = runTest {
        val dao = FakeDao()
        dao.rows.add(record("orphan", "/m/video/ORPHAN.mp4"))
        val reconciler = FilesReconciler(dao, durationReader = { 15_000L }, clock = { 0L })
        reconciler.reconcile(
            listOf(FilesReconciler.DiskFile("/m/video/ORPHAN.mp4", "ORPHAN.mp4", "video", 777L, 888L))
        )
        assertEquals(1, dao.finalizedCalls.size)
        val call = dao.finalizedCalls.single()
        assertEquals("orphan", call.id)
        assertEquals(888L, call.endedAt)
        assertEquals(15_000L, call.durationMs)
        assertEquals(777L, call.sizeBytes)
    }
}
