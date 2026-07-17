package com.benzn.grandtime.ui

import com.benzn.grandtime.db.CaptureRecordDao.UploadStatusCount

/** Home-screen rollup of the capture_records upload status distribution. */
data class UploadSummary(val uploaded: Int, val inProgress: Int, val failed: Int) {
    val total get() = uploaded + inProgress + failed
    val allDone get() = failed == 0 && inProgress == 0 && total > 0
}

/** Folds DAO status counts into the UI summary. "pending" + "uploading" collapse into inProgress. */
fun summarizeUploads(counts: List<UploadStatusCount>): UploadSummary {
    var uploaded = 0
    var inProgress = 0
    var failed = 0
    for (c in counts) {
        when (c.status) {
            "uploaded" -> uploaded += c.n
            "uploading", "pending" -> inProgress += c.n
            "failed" -> failed += c.n
            // unknown status values are ignored
        }
    }
    return UploadSummary(uploaded, inProgress, failed)
}
