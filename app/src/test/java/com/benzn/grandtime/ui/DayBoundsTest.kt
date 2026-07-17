package com.benzn.grandtime.ui

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DayBoundsTest {
    private val zone = ZoneId.of("Pacific/Auckland")

    @Test fun `mid-afternoon instant returns that day's local midnight`() {
        val now = ZonedDateTime.of(2026, 7, 18, 14, 30, 0, 0, zone).toInstant().toEpochMilli()
        val expected = LocalDate.of(2026, 7, 18).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, startOfDayMillis(now, zone))
    }

    @Test fun `time exactly at local midnight returns itself`() {
        val midnight = LocalDate.of(2026, 7, 18).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(midnight, startOfDayMillis(midnight, zone))
    }

    @Test fun `time just before midnight returns the previous local midnight`() {
        val justBefore = ZonedDateTime.of(2026, 7, 18, 23, 59, 59, 999_000_000, zone)
            .toInstant().toEpochMilli()
        val expectedPrevious = LocalDate.of(2026, 7, 18).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expectedPrevious, startOfDayMillis(justBefore, zone))
    }

    @Test fun `just after midnight still returns that same-day midnight, not the day before`() {
        val justAfter = ZonedDateTime.of(2026, 7, 18, 0, 0, 0, 1_000_000, zone)
            .toInstant().toEpochMilli()
        val expected = LocalDate.of(2026, 7, 18).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, startOfDayMillis(justAfter, zone))
    }
}
