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

    @Test fun `unknown status is ignored`() {
        val summary = summarizeUploads(listOf(UploadStatusCount("weird", 9)))
        assertEquals(0, summary.total)
    }
}
