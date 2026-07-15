package com.benzn.grandtime.capture

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class WatermarkContentTest {
    private val utc = ZoneId.of("UTC")

    @Test fun `full content = 4 lines user time latlon address`() {
        val c = Watermark.build("Ben Lin", 0L, -36.85005, 174.76001, "12 Queen St", utc)
        assertEquals(
            listOf("Ben Lin", "1970-01-01 00:00:00", "-36.8501, 174.7600", "12 Queen St"),
            c.lines,
        )
    }

    @Test fun `no fix shows locating placeholder and omits address`() {
        val c = Watermark.build("Ben Lin", 0L, null, null, null, utc)
        assertEquals(listOf("Ben Lin", "1970-01-01 00:00:00", "Locating…"), c.lines)
    }

    @Test fun `null user omitted, address omitted when null but fix present`() {
        val c = Watermark.build(null, 0L, 1.0, 2.0, null, utc)
        assertEquals(listOf("1970-01-01 00:00:00", "1.0000, 2.0000"), c.lines)
    }

    @Test fun `custom no-fix placeholder is shown verbatim`() {
        val c = Watermark.build(null, 0L, null, null, null, utc, noFixText = "No location permission")
        assertEquals(listOf("1970-01-01 00:00:00", "No location permission"), c.lines)
    }
}
