package com.benzn.grandtime.ui

import com.benzn.grandtime.db.CaptureRecord

/**
 * One tile in the Files grid / one line item in the Home upload summary: a single [CaptureRecord]
 * (a photo, or a lone video/audio segment) or a whole recording — the rolling ~segment-length
 * chunks a video/audio session was split into on disk, which the backend still uploads and
 * processes individually, but which the user experiences as ONE recording.
 *
 * [segments] is ordered earliest-first (by segmentIndex, falling back to startedAt for any that
 * are missing it); the representative used for the thumbnail/time is always segments.first().
 */
data class RecordingUnit(val segments: List<CaptureRecord>) {
    init {
        require(segments.isNotEmpty()) { "RecordingUnit needs at least one segment" }
    }

    val representative: CaptureRecord get() = segments[0]
    val segmentCount: Int get() = segments.size
    val isGroup: Boolean get() = segmentCount > 1
    val totalDurationMs: Long get() = segments.sumOf { it.durationMs ?: 0L }
    val ids: List<String> get() = segments.map { it.id }
}

/**
 * Groups a flat record list into per-recording units: photos stay one unit per record (they have
 * no segmentIndex and aren't part of a rolling session); video/audio segments sharing the same
 * (kind, sessionId) collapse into one group, represented by the earliest segment. Result is
 * ordered by the representative's startedAt, descending — matching the prior per-record ordering
 * from [com.benzn.grandtime.db.CaptureRecordDao.observeAll].
 */
fun groupIntoRecordingUnits(records: List<CaptureRecord>): List<RecordingUnit> {
    val (photos, rest) = records.partition { it.kind == "photo" }
    val units = photos.map { RecordingUnit(listOf(it)) } +
        rest.groupBy { it.kind to it.sessionId }.values.map { segments ->
            RecordingUnit(segments.sortedWith(compareBy({ it.segmentIndex ?: Int.MAX_VALUE }, { it.startedAt })))
        }
    return units.sortedByDescending { it.representative.startedAt }
}

/**
 * Aggregate upload status for a whole recording: "uploaded" iff every segment is uploaded,
 * "failed" if any segment failed (and not all uploaded), "pending" otherwise (mixes of
 * pending/uploading segments — still waiting, not worth distinguishing further at this level).
 */
fun RecordingUnit.aggregateUploadStatus(): String = when {
    segments.all { it.uploadStatus == "uploaded" } -> "uploaded"
    segments.any { it.uploadStatus == "failed" } -> "failed"
    else -> "pending"
}

/** Folds a list of recording units into the same [UploadSummary] shape [summarizeUploads] uses
 *  for per-segment counts — here each unit (a whole recording) counts as one, per
 *  [aggregateUploadStatus]. Used by Home so "Today's uploads" counts recordings, not segments. */
fun summarizeRecordingUploads(units: List<RecordingUnit>): UploadSummary {
    var uploaded = 0
    var inProgress = 0
    var failed = 0
    for (unit in units) {
        when (unit.aggregateUploadStatus()) {
            "uploaded" -> uploaded++
            "failed" -> failed++
            else -> inProgress++
        }
    }
    return UploadSummary(uploaded, inProgress, failed)
}
