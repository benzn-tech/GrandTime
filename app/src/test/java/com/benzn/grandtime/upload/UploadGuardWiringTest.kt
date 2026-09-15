package com.benzn.grandtime.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins where UploadWorker applies UploadReadiness. Source-level, because the worker needs a real
 * WorkManager, network and Android context; the rules themselves are driven in UploadReadinessTest.
 *
 * Measured 2026-09-15: a sweep uploaded two video segments while they were still being recorded,
 * both became "uploaded" with an unplayable object in S3, and one good local copy was then deleted.
 */
class UploadGuardWiringTest {

    private val worker = File("src/main/java/com/benzn/grandtime/upload/UploadWorker.kt").readText()
    private val doWork = worker.substring(worker.indexOf("override suspend fun doWork()"), worker.indexOf("private suspend fun CaptureRecordDao.record("))

    @Test
    fun `a live segment is turned away before anything is asked of the server`() {
        val guard = doWork.indexOf("UploadReadiness.readyToUpload(")
        assertTrue("UploadWorker must check readiness", guard >= 0)
        assertTrue(
            "the check must come before the row is marked uploading and an upload URL is requested",
            guard < doWork.indexOf("markUploadStatus(recordId, \"uploading\")") && guard < doWork.indexOf("client.uploadUrl("),
        )
    }

    @Test
    fun `a file that changed while it was sent is never completed`() {
        val put = doWork.indexOf("client.putFile(")
        val check = doWork.indexOf("UploadReadiness.unchangedDuringUpload(")
        val complete = doWork.indexOf("client.completeStatus(")
        assertTrue("UploadWorker must compare the file before and after the PUT", check >= 0)
        assertTrue("the comparison must sit between the PUT and complete", put in 0 until check && check < complete)
    }

    @Test
    fun `complete reports the bytes that were sent, not the file as it is afterwards`() {
        // Re-measuring after the PUT is what let the server's size check be told the finished
        // size of a file whose half-written snapshot was already in S3.
        val call = doWork.substring(doWork.indexOf("client.completeStatus("), doWork.indexOf("gpsTrack = record.gpsTrack"))
        assertTrue(call.contains("sizeBeforePut"))
        assertFalse(call.contains("file.length()"))
    }
}
