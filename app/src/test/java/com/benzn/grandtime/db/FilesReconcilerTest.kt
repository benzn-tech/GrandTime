package com.benzn.grandtime.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDao : CaptureRecordDao {
    val rows = mutableListOf<CaptureRecord>()
    val missingIds = mutableListOf<String>()
    override suspend fun insert(record: CaptureRecord) { rows.add(record) }
    override suspend fun finalize(id: String, endedAt: Long, durationMs: Long, sizeBytes: Long) {}
    override fun observeAll(): Flow<List<CaptureRecord>> = flowOf(rows)
    override suspend fun listAll(): List<CaptureRecord> = rows.toList()
    override suspend fun markMissing(ids: List<String>) { missingIds.addAll(ids) }
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
}
