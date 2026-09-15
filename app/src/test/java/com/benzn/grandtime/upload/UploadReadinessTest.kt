package com.benzn.grandtime.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadReadinessTest {

    private val now = 1_000_000_000L

    @Test
    fun `a segment still being recorded is not uploaded`() {
        // The measured case: a video row exists from the moment the segment starts, and the
        // 15-minute sweep arrived while the muxer was still writing to the file.
        assertFalse(UploadReadiness.readyToUpload(endedAt = null, lastModifiedMs = now - 100, nowMs = now))
    }

    @Test
    fun `a finalized segment uploads straight away`() {
        // Finalizing touches the file last, so its modification time is always "just now".
        assertTrue(UploadReadiness.readyToUpload(endedAt = now - 50, lastModifiedMs = now - 10, nowMs = now))
    }

    @Test
    fun `a segment a crash left unfinalized still uploads once nothing writes to it`() {
        // No end time will ever be written for it. Waiting on one would strand it forever.
        assertTrue(UploadReadiness.readyToUpload(endedAt = null, lastModifiedMs = now - UploadReadiness.SETTLE_MS, nowMs = now))
    }

    @Test
    fun `just short of settling is still treated as live`() {
        assertFalse(UploadReadiness.readyToUpload(endedAt = null, lastModifiedMs = now - UploadReadiness.SETTLE_MS + 1, nowMs = now))
    }

    @Test
    fun `an unchanged file was sent intact`() {
        assertTrue(UploadReadiness.unchangedDuringUpload(76_949_699, now, 76_949_699, now))
    }

    @Test
    fun `a file that grew during the upload was not sent intact`() {
        assertFalse(UploadReadiness.unchangedDuringUpload(48_971_004, now, 76_949_699, now + 9_000))
    }

    @Test
    fun `a file rewritten in place during the upload was not sent intact`() {
        // Finishing a segment patches its header without necessarily changing the length.
        assertFalse(UploadReadiness.unchangedDuringUpload(76_949_699, now, 76_949_699, now + 1))
    }
}
