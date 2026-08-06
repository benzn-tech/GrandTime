package com.benzn.grandtime.ui

import com.benzn.grandtime.db.CaptureRecordDao.UploadStatusCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadSummaryTest {
    @Test fun `mixed counts fold correctly`() {
        val counts = listOf(
            UploadStatusCount("uploaded", 5),
            UploadStatusCount("uploading", 2),
            UploadStatusCount("pending", 3),
            UploadStatusCount("failed", 1),
        )
        val summary = summarizeUploads(counts)
        assertEquals(5, summary.uploaded)
        assertEquals(5, summary.inProgress)
        assertEquals(1, summary.failed)
        assertEquals(11, summary.total)
        assertFalse(summary.allDone)
    }

    @Test fun `empty counts is all zero and not allDone`() {
        val summary = summarizeUploads(emptyList())
        assertEquals(0, summary.uploaded)
        assertEquals(0, summary.inProgress)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.total)
        assertFalse(summary.allDone)
    }

    @Test fun `only uploaded is allDone`() {
        val summary = summarizeUploads(listOf(UploadStatusCount("uploaded", 4)))
        assertEquals(4, summary.uploaded)
        assertEquals(0, summary.inProgress)
        assertEquals(0, summary.failed)
        assertTrue(summary.allDone)
    }

    @Test fun `any failed is not allDone`() {
        val summary = summarizeUploads(
            listOf(UploadStatusCount("uploaded", 4), UploadStatusCount("failed", 1))
        )
        assertFalse(summary.allDone)
    }

    /**
     * This used to assert that an unknown status is dropped. That was the behaviour that
     * made the v5 status rename dangerous: `retrying`, `frozen` and `dead` would all have
     * vanished from the rollup with every test still green. A status nobody recognises is a
     * bug, and burying it is how a rename deletes a warning — so it is counted, loudly.
     */
    @Test fun `an unknown status is surfaced, not dropped`() {
        val summary = summarizeUploads(listOf(UploadStatusCount("weird", 9)))
        assertEquals(9, summary.total)
        assertEquals(9, summary.failed)
    }

    @Test fun `retrying is a failed attempt that will come back on its own`() {
        val s = summarizeUploads(listOf(UploadStatusCount("retrying", 3)))
        assertEquals(3, s.failed)
        assertEquals(0, s.stuck)
    }

    @Test fun `frozen and dead are stuck, not failed`() {
        val s = summarizeUploads(listOf(UploadStatusCount("frozen", 2), UploadStatusCount("dead", 1)))
        assertEquals(3, s.stuck)
        assertEquals(0, s.failed)
        assertEquals(0, s.inProgress)
    }

    @Test fun `every status in the v5 vocabulary lands in a bucket`() {
        val all = listOf("pending", "uploading", "retrying", "frozen", "dead", "uploaded")
            .map { UploadStatusCount(it, 1) }
        val s = summarizeUploads(all)
        assertEquals(6, s.uploaded + s.inProgress + s.failed + s.stuck)
        assertEquals(6, s.total)
    }

    @Test fun `a stuck record means the day is not done`() {
        val s = summarizeUploads(listOf(UploadStatusCount("uploaded", 4), UploadStatusCount("frozen", 1)))
        assertFalse(s.allDone)
    }
}
