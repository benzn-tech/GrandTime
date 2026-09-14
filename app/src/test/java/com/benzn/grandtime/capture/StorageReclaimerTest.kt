package com.benzn.grandtime.capture

import com.benzn.grandtime.db.CaptureRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Both reclaim paths against a real directory tree. The free-space numbers are supplied by the
 * test, derived from which files have actually been deleted, so "the database gained space" is a
 * consequence of a deletion and not a constant somebody set.
 */
class StorageReclaimerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var volume: File
    private lateinit var root: File
    private val created = mutableListOf<File>()
    private val sizes = mutableMapOf<String, Long>()

    private fun setUp() {
        volume = tmp.newFolder("volume")
        root = File(volume, "FieldSight").apply { mkdirs() }
    }

    private fun file(parent: File, name: String, bytes: Int, modifiedAt: Long): File {
        parent.mkdirs()
        return File(parent, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modifiedAt)
            created += this
            sizes[path] = bytes.toLong()
        }
    }

    private fun media(kind: String, name: String, bytes: Int, modifiedAt: Long, folder: String = "ben_lin_1") =
        file(File(File(root, folder), kind), name, bytes, modifiedAt)

    private fun deletedBytes(): Long = created.filter { !it.exists() }.sumOf { sizes.getValue(it.path) }

    private fun reclaimer(
        baseFree: Long,
        sameVolume: Boolean = true,
        databaseGainsSpace: Boolean = true,
        log: MutableList<String> = mutableListOf(),
    ) = StorageReclaimer(
        mediaRoot = { root },
        recordingFreeBytes = { baseFree + deletedBytes() },
        databaseFreeBytes = { if (databaseGainsSpace) baseFree + deletedBytes() else baseFree },
        sameVolume = { sameVolume },
        log = { log += it },
    )

    private fun row(f: File, status: String, startedAt: Long, session: String = "s-${f.name}") = CaptureRecord(
        id = f.name, kind = "video", filePath = f.path, fileName = f.name, startedAt = startedAt,
        codec = "hevc", sessionId = session, uploadStatus = status, createdAt = startedAt,
    )

    // ---------------------------------------------------------------- emergency

    @Test
    fun `emergency deletes the oldest recordings until the databases have room`() {
        setUp()
        val old = media("video", "old.mp4", 30, modifiedAt = 1_000)
        val mid = media("audio", "mid.wav", 30, modifiedAt = 2_000)
        val new = media("photo", "new.jpg", 30, modifiedAt = 3_000)
        reclaimer(baseFree = 0).emergency(floorBytes = 50)
        assertFalse(old.exists())
        assertFalse(mid.exists())
        assertTrue("stops once the floor is reached", new.exists())
    }

    @Test
    fun `emergency never touches a file that is not a recording`() {
        // The device that prompted this held 4.7 GB of drawings, and the app has All-files access.
        setUp()
        val drawings = file(volume, "GPRC-STN-ZZ-ZZZ-M3-ME-00001.zip", 100, modifiedAt = 1)
        val diagnostics = file(root, "diagnostics.txt", 100, modifiedAt = 1)
        val probe = file(File(root, "_probe"), "probe_hevc.mp4", 100, modifiedAt = 1)
        val recording = media("video", "rec.mp4", 10, modifiedAt = 5)
        reclaimer(baseFree = 0).emergency(floorBytes = 1_000)
        assertTrue(drawings.exists())
        assertTrue(diagnostics.exists())
        assertTrue(probe.exists())
        assertFalse("its own recordings are all it may take", recording.exists())
    }

    @Test
    fun `emergency deletes nothing when recordings are on a different volume`() {
        // Recordings on an SD card: deleting them frees nothing for a full internal disk.
        setUp()
        val rec = media("video", "rec.mp4", 30, modifiedAt = 1)
        val log = mutableListOf<String>()
        reclaimer(baseFree = 0, sameVolume = false, log = log).emergency(floorBytes = 50)
        assertTrue(rec.exists())
        assertTrue(log.single().contains("different volume"))
    }

    @Test
    fun `emergency stops after a deletion that did not free the database volume`() {
        // A same-volume check can be wrong. The cost of trusting it is bounded to one file.
        setUp()
        val big = StorageReclaimer.PROBE_BYTES.toInt()
        val first = media("video", "first.mp4", big, modifiedAt = 1)
        val second = media("video", "second.mp4", big, modifiedAt = 2)
        reclaimer(baseFree = 0, databaseGainsSpace = false).emergency(floorBytes = 10L * big)
        assertFalse(first.exists())
        assertTrue("does not keep destroying footage that is not helping", second.exists())
    }

    @Test
    fun `emergency does nothing above the floor`() {
        setUp()
        val rec = media("video", "rec.mp4", 30, modifiedAt = 1)
        assertEquals(0L, reclaimer(baseFree = 100).emergency(floorBytes = 50))
        assertTrue(rec.exists())
    }

    // ---------------------------------------------------------------- database-guided

    @Test
    fun `reclaim deletes uploaded recordings first and marks exactly those missing`() = runTest {
        setUp()
        val pendingOld = media("video", "pending-old.mp4", 30, modifiedAt = 1)
        val uploadedNew = media("video", "uploaded-new.mp4", 30, modifiedAt = 2)
        val marked = mutableListOf<String>()
        val deleted = reclaimer(baseFree = 0).reclaim(
            rows = listOf(row(pendingOld, "pending", startedAt = 1), row(uploadedNew, "uploaded", startedAt = 2)),
            protectSessionId = null,
            targetBytes = 30,
            markMissing = { marked += it },
        )
        assertEquals(listOf("uploaded-new.mp4"), deleted)
        assertEquals(deleted, marked)
        assertTrue("the unsent one survives while an uploaded one could be taken", pendingOld.exists())
    }

    @Test
    fun `reclaim takes an unsent recording once nothing uploaded is left`() = runTest {
        setUp()
        val up = media("video", "up.mp4", 30, modifiedAt = 1)
        val pending = media("video", "pending.mp4", 30, modifiedAt = 2)
        val log = mutableListOf<String>()
        reclaimer(baseFree = 0, log = log).reclaim(
            rows = listOf(row(up, "uploaded", 1), row(pending, "pending", 2)),
            protectSessionId = null, targetBytes = 60, markMissing = {},
        )
        assertFalse(up.exists())
        assertFalse(pending.exists())
        assertTrue("an unsent deletion is called out in the log", log.single().contains("NOT YET UPLOADED"))
    }

    @Test
    fun `reclaim never deletes the session being recorded`() = runTest {
        setUp()
        val live = media("video", "live.mp4", 30, modifiedAt = 1)
        val other = media("video", "other.mp4", 30, modifiedAt = 2)
        reclaimer(baseFree = 0).reclaim(
            rows = listOf(row(live, "uploaded", 1, session = "live"), row(other, "uploaded", 2, session = "other")),
            protectSessionId = "live", targetBytes = 1_000, markMissing = {},
        )
        assertTrue(live.exists())
        assertFalse(other.exists())
    }

    @Test
    fun `reclaim will not delete a file a row points at outside the media tree`() = runTest {
        // Rows can still carry pre-migration private paths. A stored path is not a licence.
        setUp()
        val outside = file(volume, "not-ours.mp4", 30, modifiedAt = 1)
        val marked = mutableListOf<String>()
        reclaimer(baseFree = 0).reclaim(
            rows = listOf(row(outside, "uploaded", 1)),
            protectSessionId = null, targetBytes = 1_000, markMissing = { marked += it },
        )
        assertTrue(outside.exists())
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `reclaim ignores rows already marked missing`() = runTest {
        setUp()
        val rec = media("video", "rec.mp4", 30, modifiedAt = 1)
        reclaimer(baseFree = 0).reclaim(
            rows = listOf(row(rec, "uploaded", 1).copy(missing = true)),
            protectSessionId = null, targetBytes = 1_000, markMissing = {},
        )
        assertTrue(rec.exists())
    }

    @Test
    fun `reclaim with enough space deletes and marks nothing`() = runTest {
        setUp()
        val rec = media("video", "rec.mp4", 30, modifiedAt = 1)
        var called = false
        val deleted = reclaimer(baseFree = 500).reclaim(
            rows = listOf(row(rec, "uploaded", 1)),
            protectSessionId = null, targetBytes = 100, markMissing = { called = true },
        )
        assertTrue(deleted.isEmpty())
        assertFalse(called)
        assertTrue(rec.exists())
    }

    // ---------------------------------------------------------------- the tree rule

    @Test
    fun `a stored path with dot-dot cannot walk out of the media tree`() {
        setUp()
        val escaped = File(root, "ben_lin_1/video/../../../escape.mp4")
        assertFalse(StorageReclaimer.isMediaFile(root, escaped))
    }

    @Test
    fun `only the three media directories count`() {
        setUp()
        assertTrue(StorageReclaimer.isMediaFile(root, File(root, "u/video/a.mp4")))
        assertTrue(StorageReclaimer.isMediaFile(root, File(root, "u/audio/a.wav")))
        assertTrue(StorageReclaimer.isMediaFile(root, File(root, "u/photo/a.jpg")))
        assertFalse(StorageReclaimer.isMediaFile(root, File(root, "u/exports/a.mp4")))
        assertFalse(StorageReclaimer.isMediaFile(root, File(root, "video/a.mp4")))
    }
}
