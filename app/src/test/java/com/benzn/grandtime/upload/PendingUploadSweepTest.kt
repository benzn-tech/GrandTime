package com.benzn.grandtime.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sweep exists so recovery does not depend on someone noticing. These pin
 * the decisions that make that true, and the two that would quietly make it
 * harmful.
 */
class PendingUploadSweepTest {

    private fun src(path: String) = File("src/main/java/com/benzn/grandtime/$path").readText()

    @Test
    fun `the sweep is actually scheduled`() {
        // Phases of this codebase have shipped working functions that nothing
        // called. A sweep nobody schedules is exactly that defect.
        assertTrue(src("service/CoreService.kt").contains("PendingUploadSweepWorker.schedule"))
    }

    @Test
    fun `frozen records are never swept back onto the queue`() {
        // A freeze means the failure needs the office, not another attempt.
        // Sweeping it would restart that loop every fifteen minutes, forever.
        val s = src("upload/PendingUploadSweepWorker.kt")
        val statuses = s.substringAfter("listPendingForAuthor(").substringBefore(")")
        assertEquals("frozen must not be swept", false, statuses.contains("frozen"))
        assertEquals("dead must not be swept", false, statuses.contains("dead"))
    }

    @Test
    fun `an unattributable recording is left alone`() {
        // Uploading it under whoever happens to be signed in attributes one
        // client's recording to another.
        val s = src("upload/PendingUploadSweepWorker.kt")
        assertTrue(s.contains("authorSub"))
        assertTrue(s.substringBefore("listPendingForAuthor").contains("Result.success()"))
    }

    @Test
    fun `re-scheduling does not reset the period`() {
        // The service restarts often. With REPLACE the period would restart each
        // time and the sweep would never come due.
        assertTrue(src("upload/PendingUploadSweepWorker.kt")
            .contains("ExistingPeriodicWorkPolicy.KEEP"))
    }

    @Test
    fun `the sweep does not impose upload order`() {
        // The backend sorts by chunk index and never by arrival, and each chunk
        // is transcribed independently. Sequencing would only add head-of-line
        // blocking: one bad chunk holding up every chunk behind it — the failure
        // this worker exists to prevent.
        val s = src("upload/PendingUploadSweepWorker.kt")
        assertEquals("must not serialise uploads", false, s.contains("awaitAll"))
        assertEquals("must not chain work", false, s.contains("beginWith"))
    }
}
