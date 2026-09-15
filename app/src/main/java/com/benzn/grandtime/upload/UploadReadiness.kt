package com.benzn.grandtime.upload

/**
 * Whether a recording's file may be uploaded yet, and whether what was sent is what is on disk.
 *
 * WHY. A video segment is given its database row when it STARTS -- status "pending", no end time
 * -- and PendingUploadSweepWorker (every 15 minutes) and the startup sweep select rows by status
 * alone. So a sweep landing mid-segment uploaded a file that was still being written.
 *
 * Measured 2026-09-15 on DQF2S, prod: two chunks out of ~200 in 14 days. The S3 objects were
 * 48,971,004 and 33,681,778 bytes against finished files of 76,949,699 and 41,377,853, and they
 * were not a prefix of the finished file -- they already differed at byte 3,225, where the muxer
 * patches the header when a segment ends -- and had no moov atom, so neither would play. The
 * later "complete" call measured the finished file, so the server logged a size mismatch in
 * observe mode and accepted it; the row became "uploaded", the real file's own upload then
 * stopped at "already uploaded", and rolling overwrite was free to delete the only good copy. One
 * of the two was deleted before anyone looked; that footage is gone.
 *
 * Pure, so every rule is unit-tested; UploadWorker applies it.
 */
object UploadReadiness {

    /**
     * A file nothing has written to for this long is not being recorded. The muxer writes several
     * times a second while a segment is live, so this is far from a live file, and short enough
     * that a segment a crash left behind -- never finalized, so no end time -- still uploads soon.
     */
    const val SETTLE_MS = 10_000L

    /** Finished: its row was finalized, or nothing has written to the file for [SETTLE_MS]. */
    fun readyToUpload(endedAt: Long?, lastModifiedMs: Long, nowMs: Long): Boolean =
        endedAt != null || nowMs - lastModifiedMs >= SETTLE_MS

    /**
     * The file did not change while it was being sent. Size AND modification time: finishing a
     * segment rewrites its header in place, which can leave the length unchanged.
     */
    fun unchangedDuringUpload(sizeBefore: Long, modifiedBefore: Long, sizeAfter: Long, modifiedAfter: Long): Boolean =
        sizeBefore == sizeAfter && modifiedBefore == modifiedAfter
}
