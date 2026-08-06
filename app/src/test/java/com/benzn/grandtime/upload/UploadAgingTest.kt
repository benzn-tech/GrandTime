package com.benzn.grandtime.upload

import com.benzn.grandtime.db.CaptureRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 24L * 60 * 60 * 1000

private fun record(
    startedAt: Long,
    status: String = "pending",
    frozenSinceMs: Long? = null,
    frozenCreditMs: Long = 0,
    lastAttemptAt: Long? = null,
) = CaptureRecord(
    id = "r", kind = "video", filePath = "/x", fileName = "f.mp4",
    startedAt = startedAt, codec = "h264", sessionId = "s", createdAt = startedAt,
    uploadStatus = status, frozenSinceMs = frozenSinceMs,
    frozenCreditMs = frozenCreditMs, lastAttemptAt = lastAttemptAt,
)

class UploadAgingTest {

    @Test fun `an ordinary record ages in real time`() {
        assertEquals(2 * DAY, UploadAging.effectiveAgeMs(record(startedAt = 0), now = 2 * DAY))
    }

    @Test fun `time spent frozen right now does not count`() {
        val r = record(startedAt = 0, status = "frozen", frozenSinceMs = 1 * DAY)
        assertEquals(1 * DAY, UploadAging.effectiveAgeMs(r, now = 5 * DAY))
    }

    @Test fun `time spent frozen earlier does not count either`() {
        val r = record(startedAt = 0, frozenCreditMs = 4 * DAY)
        assertEquals(1 * DAY, UploadAging.effectiveAgeMs(r, now = 5 * DAY))
    }

    @Test fun `an old record with no freeze is given up on`() {
        assertTrue(UploadAging.shouldGiveUp(record(startedAt = 0), now = 8 * DAY))
    }

    @Test fun `a young record is not given up on`() {
        assertFalse(UploadAging.shouldGiveUp(record(startedAt = 0), now = 3 * DAY))
    }

    /**
     * The regression this file exists for. Frozen on day 2, fixed on day 9: without the
     * credit the thawed record hits the age check with a cleared class and dies without a
     * single retry — the freeze mechanism destroying the data it exists to protect.
     */
    @Test fun `a record thawed after a long freeze still gets its budget`() {
        val thawed = record(startedAt = 0, frozenCreditMs = 7 * DAY, lastAttemptAt = 2 * DAY)
        assertFalse(UploadAging.shouldGiveUp(thawed, now = 9 * DAY))
    }

    @Test fun `a frozen record is never given up on while frozen`() {
        val r = record(startedAt = 0, status = "frozen", frozenSinceMs = 1 * DAY)
        assertFalse(UploadAging.shouldGiveUp(r, now = 90 * DAY))
    }

    /** The credit is not immortality: once spent, the ordinary deadline applies again. */
    @Test fun `a thawed record that then sat unsent for a week is given up on`() {
        val r = record(startedAt = 0, frozenCreditMs = 7 * DAY, lastAttemptAt = 15 * DAY)
        assertTrue(UploadAging.shouldGiveUp(r, now = 15 * DAY + 1))
    }

    @Test fun `thaw banks the frozen span`() {
        val r = record(startedAt = 0, status = "frozen", frozenSinceMs = 2 * DAY, frozenCreditMs = 1 * DAY)
        assertEquals(4 * DAY, UploadAging.creditOnThaw(r, now = 5 * DAY))
    }

    @Test fun `crediting a record that was never frozen changes nothing`() {
        assertEquals(0L, UploadAging.creditOnThaw(record(startedAt = 0), now = 5 * DAY))
    }
}
