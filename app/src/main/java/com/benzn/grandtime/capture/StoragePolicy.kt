package com.benzn.grandtime.capture

/**
 * Pure decisions about free space: when to refuse a capture, when to end one, and what to delete.
 * No Android dependency, so every rule here is unit-tested; [StorageReclaimer] acts on them.
 *
 * WHY THIS EXISTS. On the F2SP, recordings and the app's own SQLite databases share one
 * partition. A device filled to 0 bytes crashed on every launch, before the sign-in screen:
 * WorkManager opens its database during process start, and SQLite cannot set WAL journal mode
 * on a full disk (`SQLiteFullException` inside `ForceStopRunnable`). The old guard only refused
 * to START a capture below 200 MB, so a session already running wrote straight through it to
 * zero, and nothing ever gave space back.
 */
object StoragePolicy {

    const val MB = 1024L * 1024L

    /**
     * Below this the databases cannot be trusted to open, so space is reclaimed WITHOUT them:
     * oldest recording file first, by modification time. It is the only point at which a file may
     * be deleted without knowing whether it was uploaded, because the alternative is an app that
     * cannot start at all.
     */
    const val EMERGENCY_FLOOR = 64 * MB

    /** Below this a new capture is refused. Unchanged from the capture spec (§3, 200 MB). */
    const val START_FLOOR = 200 * MB

    /**
     * How far above a floor a reclaim pass goes, so the next segment boundary does not reclaim
     * again.
     *
     * Deliberately small. "Rolling" means deleting the oldest recordings just enough to keep
     * recording, not clearing a large fixed amount: on the device that prompted this, 4.7 GB of
     * the 5.3 GB partition was somebody else's files, and a fixed 500 MB target would have deleted
     * nearly every recording on the device in one pass.
     */
    const val RECLAIM_HEADROOM = 100 * MB

    /**
     * Assumed size of a segment that has not finished yet. A first segment has no history, and a
     * zero there would read as "costs nothing" and let the session start into the reserve.
     */
    const val UNKNOWN_SEGMENT_ESTIMATE = 40 * MB

    /**
     * Free space rolling overwrite keeps so the device can still install an update.
     *
     * Android refuses any install unless free space exceeds its low-storage line -- 276 MiB on the
     * F2SP's 5.4 GB partition (devicestoragemonitor lowBytes=289175552) -- plus the APK (~26 MB).
     * The floors above all sit below that line, and reclaiming only back to them kept a device in
     * rolling overwrite hovering at 210-310 MB free. Measured on DQF2S 2026-09-15: 265 MB free
     * after a morning of rolling overwrite, and no update could be installed at all -- by hand, or
     * by the in-app updater planned for later.
     *
     * A reclaim TRIGGER, never a stop line. When nothing deletable is left, capture still runs down
     * to the floors: a device that refuses to record with 400 MB free is the worse failure.
     *
     * Bought with UPLOADED recordings only. On the device that prompted the floors, 4.7 GB of the
     * partition was somebody else's files; aiming every reclaim at this reserve there would delete
     * every recording on the device, unsent ones included, for headroom nobody is using yet.
     * Unsent recordings are still only taken to keep a capture above the floors.
     */
    const val UPDATE_RESERVE = 450 * MB

    fun canStart(freeBytes: Long): Boolean = freeBytes >= START_FLOOR

    /** Free space a reclaim before a new capture aims for. */
    fun startTarget(): Long = START_FLOOR + RECLAIM_HEADROOM

    /** Whether to reclaim uploaded recordings to restore [UPDATE_RESERVE]. */
    fun belowUpdateReserve(freeBytes: Long): Boolean = freeBytes < UPDATE_RESERVE

    /** Free space an update-reserve reclaim aims for, so the next segment does not reclaim again. */
    fun updateReserveTarget(): Long = UPDATE_RESERVE + RECLAIM_HEADROOM

    /**
     * Whether a running session may begin its next segment.
     *
     * Two segments of headroom above the reserve, sized from the segment that just finished, not
     * from a constant. Segment length is a user setting and video bitrate follows the quality
     * setting, so a fixed floor tuned for one-minute segments would let a five-minute segment
     * write straight through it. Two, not one, because the check runs at the START of a segment
     * and the next boundary is the first chance to stop again.
     */
    fun canContinue(freeBytes: Long, lastSegmentBytes: Long): Boolean =
        freeBytes >= continueFloor(lastSegmentBytes)

    /** Free space a reclaim at a segment boundary aims for. */
    fun continueTarget(lastSegmentBytes: Long): Long = continueFloor(lastSegmentBytes) + RECLAIM_HEADROOM

    private fun continueFloor(lastSegmentBytes: Long): Long {
        val segment = if (lastSegmentBytes > 0) lastSegmentBytes else UNKNOWN_SEGMENT_ESTIMATE
        return EMERGENCY_FLOOR + 2 * segment
    }

    /**
     * One recording the reclaimer may delete.
     *
     * Deliberately carries NO author. The device rotates between clients, and space has to be
     * reclaimable whoever recorded it; a field that could be filtered on is a field somebody would
     * eventually filter on, and a previous client's backlog would then fill the disk forever.
     */
    data class Candidate(
        val id: String,
        val path: String,
        val sizeBytes: Long,
        val startedAt: Long,
        val uploaded: Boolean,
        val sessionId: String,
    )

    /**
     * What to delete to reach [targetBytes] of free space, in the order to delete it.
     *
     * Uploaded recordings first, oldest first -- they are already safe in the cloud. Only when
     * every one of those is gone are unsent recordings taken, again oldest first. The owner chose
     * that trade explicitly: a device that cannot record is worse than losing its oldest unsent
     * segment.
     *
     * The live session is never a candidate. Deleting one of its segments would upload a session
     * with a hole in the middle, which the backend stitches across without complaint.
     */
    fun planEviction(
        candidates: List<Candidate>,
        freeBytes: Long,
        targetBytes: Long,
        protectSessionId: String?,
    ): List<Candidate> {
        var need = targetBytes - freeBytes
        if (need <= 0) return emptyList()
        val ordered = candidates
            .filter { it.sizeBytes > 0 && (protectSessionId == null || it.sessionId != protectSessionId) }
            .sortedWith(compareBy<Candidate>({ !it.uploaded }, { it.startedAt }))
        val out = ArrayList<Candidate>()
        for (c in ordered) {
            if (need <= 0) break
            out += c
            need -= c.sizeBytes
        }
        return out
    }
}
