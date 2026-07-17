package com.benzn.grandtime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test fun `zero millis formats as 0-00`() {
        assertEquals("0:00", formatClock(0))
    }

    @Test fun `5 seconds formats as 0-05`() {
        assertEquals("0:05", formatClock(5_000))
    }

    @Test fun `65 seconds formats as 1-05`() {
        assertEquals("1:05", formatClock(65_000))
    }

    @Test fun `past an hour formats as h-mm-ss`() {
        assertEquals("1:02:05", formatClock(3_725_000))
    }

    @Test fun `negative millis formats as 0-00`() {
        assertEquals("0:00", formatClock(-1_000))
    }
}
