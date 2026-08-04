package com.benzn.grandtime.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A device is handed from one client to the next by signing out and signing in.
 * Whatever is still queued from the previous account must not travel with it —
 * not uploaded, not listed, not silently re-owned.
 *
 * This is not hypothetical. Before this change the three parts below combined
 * into a working leak:
 *
 *   CognitoAuthManager.onLoggedIn -> backfillAuthorSub(sub)
 *       claims EVERY authorSub IS NULL row for whoever just signed in
 *   CoreService startup sweep -> listByUploadStatus(pending, failed, uploading)
 *       no author filter at all
 *   UploadWorker -> freshIdToken()
 *       uploads under the CURRENT account
 *
 * 0.5.9 raised STARTUP_SWEEP_CAP from 10 to 400 so a real offline backlog could
 * drain, which was right on its own terms but removed the accidental ceiling on
 * how much of the previous client's work could escape.
 *
 * These tests pin the rules rather than the wiring, so they keep their meaning
 * if the queries move.
 */
class UploadQueueOwnershipTest {

    private fun rec(id: String, author: String?, status: String = "pending") = CaptureRecord(
        id = id,
        kind = "audio",
        filePath = "/tmp/$id.wav",
        fileName = "$id.wav",
        startedAt = 0L,
        codec = "wav",
        sessionId = "s1",
        authorSub = author,
        uploadStatus = status,
        createdAt = 0L,
    )

    private val PENDING = setOf("pending", "failed", "uploading")

    private fun sweepFor(rows: List<CaptureRecord>, account: String) =
        rows.filter { it.uploadStatus in PENDING && it.authorSub == account }

    @Test
    fun `the startup sweep only picks up the current account's work`() {
        val rows = listOf(
            rec("a1", "sub-A"),
            rec("b1", "sub-B"),
            rec("a2", "sub-A", status = "failed"),
        )
        assertEquals(listOf("b1"), sweepFor(rows, "sub-B").map { it.id })
    }

    @Test
    fun `an unowned row is claimed by nobody`() {
        val rows = listOf(rec("legacy", null))
        assertTrue(
            "a null author must match no account — uploading it would attribute one " +
                "client's recording to another",
            sweepFor(rows, "sub-B").isEmpty(),
        )
    }

    @Test
    fun `already uploaded work is never swept again regardless of owner`() {
        val rows = listOf(rec("done", "sub-B", status = "uploaded"))
        assertTrue(sweepFor(rows, "sub-B").isEmpty())
    }

    @Test
    fun `unowned rows are counted so they can be surfaced rather than vanish`() {
        val rows = listOf(rec("legacy1", null), rec("legacy2", null), rec("b1", "sub-B"))
        assertEquals(2, rows.count { it.uploadStatus in PENDING && it.authorSub == null })
    }

    @Test
    fun `the sign-out warning counts only the leaving account's unsent work`() {
        val rows = listOf(
            rec("a1", "sub-A"),
            rec("a2", "sub-A", status = "failed"),
            rec("b1", "sub-B"),
            rec("a3", "sub-A", status = "uploaded"),
        )
        val leaving = "sub-A"
        assertEquals(2, rows.count { it.authorSub == leaving && it.uploadStatus in PENDING })
    }

    @Test
    fun `a record carries its recorder from the moment it is created`() {
        val r = CaptureRecord(
            id = "x", kind = "audio", filePath = "/tmp/x.wav", fileName = "x.wav",
            startedAt = 0L, codec = "wav", sessionId = "s1",
            authorSub = "sub-A", createdAt = 0L,
        )
        assertEquals("sub-A", r.authorSub)
    }

    @Test
    fun `a record created with no signed-in account stays unowned rather than guessing`() {
        assertNull(rec("x", null).authorSub)
    }

    /**
     * The tests above pin the RULES and would pass against the old wiring too.
     * This one pins the WIRING: the DAO must expose an author-scoped sweep, so
     * a caller cannot reach for the unscoped one by accident.
     */
    @Test
    fun `the dao offers an author-scoped sweep and an orphan count`() = runTest {
        val dao = OwnershipFakeDao()
        dao.rows += listOf(rec("a1", "sub-A"), rec("b1", "sub-B"), rec("legacy", null))

        assertEquals(
            listOf("b1"),
            dao.listPendingForAuthor(listOf("pending", "failed", "uploading"), "sub-B").map { it.id },
        )
        assertEquals(1, dao.countOrphanedPending())
    }
}

/**
 * Minimal fake over the real interface. It exists so that removing
 * [CaptureRecordDao.listPendingForAuthor] stops this file compiling — a
 * contract test, not a behaviour test.
 */
private class OwnershipFakeDao : CaptureRecordDao {
    val rows = mutableListOf<CaptureRecord>()

    override suspend fun listPendingForAuthor(statuses: List<String>, authorSub: String) =
        rows.filter { it.uploadStatus in statuses && !it.missing && it.authorSub == authorSub }

    override suspend fun countOrphanedPending() =
        rows.count { it.uploadStatus in setOf("pending", "failed", "uploading") && it.authorSub == null }

    override suspend fun countPendingForAuthor(authorSub: String) =
        rows.count { it.uploadStatus in setOf("pending", "failed", "uploading") && it.authorSub == authorSub }

    // --- everything else is irrelevant here ---
    override suspend fun insert(record: CaptureRecord) { rows.add(record) }
    override suspend fun finalize(id: String, endedAt: Long, durationMs: Long, sizeBytes: Long) {}
    override fun observeAll(): Flow<List<CaptureRecord>> = flowOf(rows)
    override fun observeSince(sinceMs: Long): Flow<List<CaptureRecord>> = flowOf(rows)
    override suspend fun listAll(): List<CaptureRecord> = rows.toList()
    override suspend fun getById(id: String): CaptureRecord? = rows.firstOrNull { it.id == id }
    override suspend fun markMissing(ids: List<String>) {}
    override suspend fun updatePath(oldPath: String, newPath: String) {}
    override suspend fun updateGpsTrack(id: String, json: String) {}
    override suspend fun backfillAuthorSub(sub: String) {}
    override suspend fun setSiteId(id: String, siteId: String?) {}
    override suspend fun markUploadStatus(id: String, status: String) {}
    override suspend fun listByUploadStatus(statuses: List<String>): List<CaptureRecord> =
        rows.filter { it.uploadStatus in statuses }
    override fun observeUploadStatusCounts(): Flow<List<CaptureRecordDao.UploadStatusCount>> = flowOf(emptyList())
    override fun observeUploadStatusCountsSince(sinceMs: Long): Flow<List<CaptureRecordDao.UploadStatusCount>> =
        flowOf(emptyList())
}
