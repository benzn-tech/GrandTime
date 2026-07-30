package com.benzn.grandtime.ui

import com.benzn.grandtime.db.CaptureRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingGroupingTest {

    private fun record(
        id: String,
        kind: String,
        sessionId: String,
        segmentIndex: Int? = null,
        startedAt: Long = 0L,
        durationMs: Long? = null,
        uploadStatus: String = "pending",
    ) = CaptureRecord(
        id = id, kind = kind, filePath = "/m/$id", fileName = id,
        startedAt = startedAt, durationMs = durationMs, codec = "x",
        segmentIndex = segmentIndex, sessionId = sessionId, createdAt = startedAt,
        uploadStatus = uploadStatus,
    )

    @Test fun `photos never group even with matching kind-less identity`() {
        val a = record("p1", "photo", "s1", startedAt = 1L)
        val b = record("p2", "photo", "s1", startedAt = 2L)

        val units = groupIntoRecordingUnits(listOf(a, b))

        assertEquals(2, units.size)
        assertTrue(units.all { !it.isGroup })
    }

    @Test fun `video segments sharing kind and sessionId collapse into one unit`() {
        val seg1 = record("v1", "video", "s1", segmentIndex = 1, startedAt = 100L, durationMs = 30_000L)
        val seg2 = record("v2", "video", "s1", segmentIndex = 2, startedAt = 130L, durationMs = 30_000L)
        val seg3 = record("v3", "video", "s1", segmentIndex = 3, startedAt = 160L, durationMs = 10_000L)

        // Deliberately shuffled input order — grouping must not depend on input order.
        val units = groupIntoRecordingUnits(listOf(seg3, seg1, seg2))

        assertEquals(1, units.size)
        val unit = units.single()
        assertTrue(unit.isGroup)
        assertEquals(3, unit.segmentCount)
        assertEquals("v1", unit.representative.id) // earliest segmentIndex
        assertEquals(listOf("v1", "v2", "v3"), unit.segments.map { it.id })
        assertEquals(70_000L, unit.totalDurationMs)
        assertEquals(listOf("v1", "v2", "v3"), unit.ids)
    }

    @Test fun `same sessionId across different kinds does not merge`() {
        val video = record("v1", "video", "shared", segmentIndex = 1, startedAt = 1L)
        val audio = record("a1", "audio", "shared", segmentIndex = 1, startedAt = 1L)

        val units = groupIntoRecordingUnits(listOf(video, audio))

        assertEquals(2, units.size)
    }

    @Test fun `single-segment recording is not a group`() {
        val only = record("v1", "video", "s1", segmentIndex = 1, startedAt = 1L, durationMs = 5_000L)

        val unit = groupIntoRecordingUnits(listOf(only)).single()

        assertFalse(unit.isGroup)
        assertEquals(1, unit.segmentCount)
        assertEquals(5_000L, unit.totalDurationMs)
    }

    @Test fun `units are ordered by representative startedAt descending`() {
        val older = record("v1", "video", "s1", segmentIndex = 1, startedAt = 10L)
        val newer = record("p1", "photo", "s2", startedAt = 20L)

        val units = groupIntoRecordingUnits(listOf(older, newer))

        assertEquals(listOf("p1", "v1"), units.map { it.representative.id })
    }

    @Test fun `aggregate status is uploaded only when every segment uploaded`() {
        val unit = RecordingUnit(
            listOf(
                record("v1", "video", "s1", segmentIndex = 1, uploadStatus = "uploaded"),
                record("v2", "video", "s1", segmentIndex = 2, uploadStatus = "uploaded"),
            )
        )
        assertEquals("uploaded", unit.aggregateUploadStatus())
    }

    @Test fun `aggregate status is failed when any segment failed and not all uploaded`() {
        val unit = RecordingUnit(
            listOf(
                record("v1", "video", "s1", segmentIndex = 1, uploadStatus = "uploaded"),
                record("v2", "video", "s1", segmentIndex = 2, uploadStatus = "failed"),
            )
        )
        assertEquals("failed", unit.aggregateUploadStatus())
    }

    @Test fun `aggregate status is pending for a pending-uploading mix with no failures`() {
        val unit = RecordingUnit(
            listOf(
                record("v1", "video", "s1", segmentIndex = 1, uploadStatus = "uploading"),
                record("v2", "video", "s1", segmentIndex = 2, uploadStatus = "pending"),
            )
        )
        assertEquals("pending", unit.aggregateUploadStatus())
    }

    @Test fun `summarizeRecordingUploads counts recordings not segments`() {
        val uploadedGroup = RecordingUnit(
            listOf(
                record("v1", "video", "s1", segmentIndex = 1, uploadStatus = "uploaded"),
                record("v2", "video", "s1", segmentIndex = 2, uploadStatus = "uploaded"),
                record("v3", "video", "s1", segmentIndex = 3, uploadStatus = "uploaded"),
            )
        )
        val failedGroup = RecordingUnit(
            listOf(
                record("v4", "video", "s2", segmentIndex = 1, uploadStatus = "uploaded"),
                record("v5", "video", "s2", segmentIndex = 2, uploadStatus = "failed"),
            )
        )
        val waitingPhoto = RecordingUnit(listOf(record("p1", "photo", "s3", uploadStatus = "pending")))

        val summary = summarizeRecordingUploads(listOf(uploadedGroup, failedGroup, waitingPhoto))

        assertEquals(1, summary.uploaded)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.inProgress)
        assertEquals(3, summary.total)
        assertFalse(summary.allDone)
    }
}
