package com.benzn.grandtime.capture

import com.benzn.grandtime.capture.StoragePolicy.Candidate
import com.benzn.grandtime.capture.StoragePolicy.MB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device this was written against filled its only partition to 0 bytes, and the app then
 * crashed on every launch before the sign-in screen: WorkManager opens its own SQLite database
 * during process start, and SQLite cannot set WAL journal mode on a full disk.
 *
 * Recordings and the app's databases share one partition on the F2SP, so every byte a
 * recording takes is a byte the database may need to open. These pin the decisions that keep
 * that from happening again. The Android glue that acts on them lives in StorageReclaimer.
 */
class StoragePolicyTest {

    private fun cand(
        id: String, sizeMb: Long, startedAt: Long, uploaded: Boolean, session: String = "s-$id",
    ) = Candidate(id, "/r/$id", sizeMb * MB, startedAt, uploaded, session)

    // ---------------------------------------------------------------- eviction order

    @Test
    fun `uploaded recordings go first, oldest first`() {
        val plan = StoragePolicy.planEviction(
            candidates = listOf(
                cand("new-up", 10, startedAt = 300, uploaded = true),
                cand("old-pending", 10, startedAt = 100, uploaded = false),
                cand("old-up", 10, startedAt = 200, uploaded = true),
            ),
            freeBytes = 0, targetBytes = 20 * MB, protectSessionId = null,
        )
        assertEquals(listOf("old-up", "new-up"), plan.map { it.id })
    }

    @Test
    fun `un-uploaded recordings are only taken once every uploaded one is gone`() {
        // The owner chose this order: a device that cannot record is worse than losing the
        // oldest unsent segment, but anything already safe in the cloud must be spent first.
        val plan = StoragePolicy.planEviction(
            candidates = listOf(
                cand("up", 10, startedAt = 500, uploaded = true),
                cand("pending-old", 10, startedAt = 100, uploaded = false),
                cand("pending-new", 10, startedAt = 200, uploaded = false),
            ),
            freeBytes = 0, targetBytes = 25 * MB, protectSessionId = null,
        )
        assertEquals(listOf("up", "pending-old", "pending-new"), plan.map { it.id })
    }

    @Test
    fun `eviction stops as soon as the target is reached`() {
        val plan = StoragePolicy.planEviction(
            candidates = (1..10).map { cand("r$it", 10, startedAt = it.toLong(), uploaded = true) },
            freeBytes = 5 * MB, targetBytes = 30 * MB, protectSessionId = null,
        )
        // Needs 25 MB; three 10 MB files is the first sum that reaches it.
        assertEquals(listOf("r1", "r2", "r3"), plan.map { it.id })
    }

    @Test
    fun `nothing is deleted when there is already enough space`() {
        val plan = StoragePolicy.planEviction(
            candidates = listOf(cand("r", 10, startedAt = 1, uploaded = true)),
            freeBytes = 600 * MB, targetBytes = 500 * MB, protectSessionId = null,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `the session being recorded right now is never evicted`() {
        // Deleting a segment of the live session would upload a session with a hole in the
        // middle, and the backend would stitch the transcript across it without complaint.
        val plan = StoragePolicy.planEviction(
            candidates = listOf(
                cand("live-1", 10, startedAt = 1, uploaded = true, session = "live"),
                cand("other", 10, startedAt = 2, uploaded = true, session = "other"),
            ),
            freeBytes = 0, targetBytes = 100 * MB, protectSessionId = "live",
        )
        assertEquals(listOf("other"), plan.map { it.id })
    }

    @Test
    fun `ownership is not considered - a previous user's recordings are evicted too`() {
        // Asked for directly: the device rotates between clients monthly, and space must be
        // reclaimable whoever recorded it. Candidate carries no author on purpose.
        val fields = Candidate::class.java.declaredFields.map { it.name }
        assertFalse("eviction must not be able to filter by author", fields.any { "author" in it.lowercase() })
    }

    @Test
    fun `a zero-byte row frees nothing and is skipped`() {
        val plan = StoragePolicy.planEviction(
            candidates = listOf(
                cand("empty", 0, startedAt = 1, uploaded = true),
                cand("real", 10, startedAt = 2, uploaded = true),
            ),
            freeBytes = 0, targetBytes = 10 * MB, protectSessionId = null,
        )
        assertEquals(listOf("real"), plan.map { it.id })
    }

    // ---------------------------------------------------------------- the floors

    @Test
    fun `a new capture is refused below the start floor`() {
        assertFalse(StoragePolicy.canStart(freeBytes = StoragePolicy.START_FLOOR - 1))
        assertTrue(StoragePolicy.canStart(freeBytes = StoragePolicy.START_FLOOR))
    }

    @Test
    fun `a running session continues while two more segments fit above the reserve`() {
        val last = 36 * MB // measured: one 720p HEVC segment on the F2SP
        val need = StoragePolicy.EMERGENCY_FLOOR + 2 * last
        assertTrue(StoragePolicy.canContinue(freeBytes = need, lastSegmentBytes = last))
        assertFalse(StoragePolicy.canContinue(freeBytes = need - 1, lastSegmentBytes = last))
    }

    @Test
    fun `the continue floor follows the real segment size, not a guess`() {
        // Segment length is a user setting. A fixed floor sized for one-minute segments would
        // let a five-minute segment write straight through it to zero.
        val short = StoragePolicy.EMERGENCY_FLOOR + 2 * 36 * MB
        assertTrue(StoragePolicy.canContinue(freeBytes = short, lastSegmentBytes = 36 * MB))
        assertFalse(StoragePolicy.canContinue(freeBytes = short, lastSegmentBytes = 180 * MB))
    }

    @Test
    fun `a first segment with no history still leaves a working margin`() {
        // lastSegmentBytes = 0 before any segment has finished; that must not read as "free".
        assertFalse(StoragePolicy.canContinue(freeBytes = StoragePolicy.EMERGENCY_FLOOR, lastSegmentBytes = 0))
    }

    @Test
    fun `the floors are ordered so they cannot fight each other`() {
        assertTrue(StoragePolicy.EMERGENCY_FLOOR < StoragePolicy.START_FLOOR)
        assertTrue(StoragePolicy.START_FLOOR < StoragePolicy.startTarget())
    }

    @Test
    fun `a rolling reclaim aims just above the floor, not at a large fixed amount`() {
        // On the device that prompted this, 4.7 GB of 5.3 GB was somebody else's files. A fixed
        // 500 MB target would have deleted nearly every recording on the device in one pass.
        assertEquals(StoragePolicy.START_FLOOR + StoragePolicy.RECLAIM_HEADROOM, StoragePolicy.startTarget())
        assertTrue(StoragePolicy.startTarget() <= 300 * MB)
    }

    @Test
    fun `the continue target clears the continue floor so the next boundary does not reclaim again`() {
        val last = 36 * MB
        val target = StoragePolicy.continueTarget(last)
        assertTrue(StoragePolicy.canContinue(target, last))
        assertTrue(target - StoragePolicy.RECLAIM_HEADROOM < target)
        assertFalse(StoragePolicy.canContinue(target - StoragePolicy.RECLAIM_HEADROOM - 1, last))
    }

    // ---------------------------------------------------------------- the update reserve

    @Test
    fun `the update reserve leaves room for Android to install an update`() {
        // Measured on DQF2S: devicestoragemonitor lowBytes=289175552 and a 26 MB release APK.
        // A device that rolled over at the old floors sat at 265 MB free and could not be updated.
        val androidInstallLine = 289_175_552L + 26 * MB
        assertTrue(StoragePolicy.UPDATE_RESERVE > androidInstallLine)
        assertTrue(StoragePolicy.belowUpdateReserve(androidInstallLine))
        assertFalse(StoragePolicy.belowUpdateReserve(StoragePolicy.UPDATE_RESERVE))
    }

    @Test
    fun `the update reserve is a reclaim trigger, never a reason to stop recording`() {
        // Below the reserve with nothing uploaded left to delete, capture must still run down to
        // the floors. A device that refuses to record with 400 MB free is the worse failure.
        val belowReserve = StoragePolicy.UPDATE_RESERVE - 1
        assertTrue(StoragePolicy.belowUpdateReserve(belowReserve))
        assertTrue(StoragePolicy.canStart(belowReserve))
        assertTrue(StoragePolicy.canContinue(belowReserve, lastSegmentBytes = 73 * MB))
    }

    @Test
    fun `an update reserve reclaim overshoots so the next segment does not reclaim again`() {
        assertTrue(StoragePolicy.updateReserveTarget() > StoragePolicy.UPDATE_RESERVE)
        assertFalse(StoragePolicy.belowUpdateReserve(StoragePolicy.updateReserveTarget()))
    }
}
