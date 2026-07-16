package com.benzn.grandtime.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadWorkerDateTest {
    // 2026-07-16T00:30:00Z is 2026-07-16 12:30 NZST (+12) — same NZ day, but a naive UTC
    // date-folder (startedAt[:10]) would read 2026-07-16 here; the failing case is the evening:
    // 2026-07-15T13:00:00Z = 2026-07-16 01:00 NZST → NZ date must be 2026-07-16, not 2026-07-15.
    @Test fun `iso8601 uses NZ-local date across the UTC-evening boundary`() {
        val utcEvening = java.time.Instant.parse("2026-07-15T13:00:00Z").toEpochMilli()
        val s = iso8601(utcEvening)
        assertEquals("2026-07-16", s.substring(0, 10))
        assert(s.endsWith("+12:00") || s.endsWith("+13:00")) { "expected NZ offset, got $s" }
    }
}
