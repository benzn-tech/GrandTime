package com.benzn.grandtime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryAlertTest {

    private fun LowBatteryAlert.feed(vararg readings: Int, plugged: Boolean = false): List<Int> =
        readings.filter { onReading(it, plugged) }

    @Test
    fun `warns once at 20 and once more at 10 on the way down`() {
        assertEquals(listOf(20, 10), LowBatteryAlert().feed(25, 21, 20, 19, 15, 11, 10, 9, 5, 1))
    }

    @Test
    fun `a level that wobbles across the line does not warn again`() {
        // Under load the reported level moves by a percent either way. Each wobble back down would
        // otherwise replay the sound into the recording.
        assertEquals(listOf(20), LowBatteryAlert().feed(20, 21, 20, 21, 20, 19))
    }

    @Test
    fun `plugging in re-arms both warnings`() {
        val alert = LowBatteryAlert()
        assertEquals(listOf(20, 10), alert.feed(20, 10))
        assertFalse(alert.onReading(9, pluggedIn = true))
        assertEquals(listOf(20, 10), alert.feed(20, 10))
    }

    @Test
    fun `never warns while plugged in`() {
        assertEquals(emptyList<Int>(), LowBatteryAlert().feed(20, 10, 5, plugged = true))
    }

    @Test
    fun `starting below 20 warns once, not twice in a row`() {
        // Service started on a half-flat device: one warning for where it is now.
        assertEquals(listOf(15), LowBatteryAlert().feed(15, 14, 13))
    }

    @Test
    fun `starting below 10 warns once and does not add a 20 warning afterwards`() {
        val alert = LowBatteryAlert()
        assertEquals(listOf(8), alert.feed(8))
        assertFalse("rising back to 15 without charging is not a new warning", alert.onReading(15, false))
    }

    @Test
    fun `an unreadable level never warns`() {
        assertFalse(LowBatteryAlert().onReading(-1, pluggedIn = false))
    }

    @Test
    fun `above the warning line never warns`() {
        assertTrue(LowBatteryAlert().feed(100, 50, 21).isEmpty())
    }
}
