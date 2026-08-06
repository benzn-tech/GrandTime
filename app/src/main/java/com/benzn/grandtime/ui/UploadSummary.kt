package com.benzn.grandtime.ui

import com.benzn.grandtime.db.CaptureRecordDao.UploadStatusCount

/**
 * Home-screen rollup of the capture_records upload status distribution.
 *
 * [failed] is "the last attempt failed and another one is worth making" — including a
 * record sitting in exponential backoff, which is what the user sees as failed and what
 * the Retry button acts on.
 *
 * [stuck] is "no attempt will help": frozen, waiting on a fix only we can make, or dead.
 * Kept apart from [failed] because offering to retry them would be offering nothing.
 */
data class UploadSummary(
    val uploaded: Int,
    val inProgress: Int,
    val failed: Int,
    val stuck: Int = 0,
) {
    val total get() = uploaded + inProgress + failed + stuck
    val allDone get() = failed == 0 && inProgress == 0 && stuck == 0 && total > 0
}

/**
 * Folds DAO status counts into the UI summary.
 *
 * Every status lands somewhere on purpose. This `when` used to end with
 * `// unknown status values are ignored`, which meant the v5 rename
 * (failed -> retrying/frozen/dead) would have silently emptied the rollup with every test
 * still passing. An unrecognised status is now counted rather than buried.
 */
fun summarizeUploads(counts: List<UploadStatusCount>): UploadSummary {
    var uploaded = 0
    var inProgress = 0
    var failed = 0
    var stuck = 0
    for (c in counts) {
        when (c.status) {
            "uploaded" -> uploaded += c.n
            "uploading", "pending" -> inProgress += c.n
            // "failed" is the pre-v5 spelling of "retrying" and survives until the migration
            // has run on every device.
            "retrying", "failed" -> failed += c.n
            "frozen", "dead" -> stuck += c.n
            else -> failed += c.n
        }
    }
    return UploadSummary(uploaded, inProgress, failed, stuck)
}
