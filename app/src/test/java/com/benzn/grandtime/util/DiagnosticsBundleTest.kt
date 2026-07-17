package com.benzn.grandtime.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBundleTest {

    @Test
    fun `header contains fields on their own line in order`() {
        val header = diagnosticsHeader(
            appVersion = "1.2.3",
            deviceModel = "SDJW-F2SP",
            androidSdk = 33,
            whenIso = "2026-07-18T10:00:00+12:00",
        )
        val lines = header.lines()
        assertEquals("FieldSight diagnostics", lines[0])
        assertEquals("time: 2026-07-18T10:00:00+12:00", lines[1])
        assertEquals("app: 1.2.3", lines[2])
        assertEquals("device: SDJW-F2SP (SDK 33)", lines[3])
        assertEquals("----", lines[4])
    }

    @Test
    fun `header ends with the separator line`() {
        val header = diagnosticsHeader("1.0", "model", 30, "iso")
        assertTrue(header.trimEnd('\n').endsWith("----"))
    }

    @Test
    fun `header is deterministic for the same inputs`() {
        val a = diagnosticsHeader("1.0", "model", 30, "iso")
        val b = diagnosticsHeader("1.0", "model", 30, "iso")
        assertEquals(a, b)
    }
}
