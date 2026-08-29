package com.benzn.grandtime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The meter has one job: let someone tell "it is hearing the room" from "it is hearing nothing"
 * in the two seconds the screen is awake. On a LINEAR amplitude scale it cannot, because this
 * hardware records quiet — the measured fleet median is -36..-33 dBFS, an amplitude of about
 * 0.02, which on a 48dp meter is under a dp above the 4dp floor. These tests pin the distance
 * between real speech and digital silence, in the units the screen actually draws.
 */
class MicMeterScaleTest {

    /** What the bar is drawn at, in dp, for a given peak — the 4dp floor plus 44dp of travel. */
    private fun barDp(peak: Float): Float = 4f + meterFraction(peak) * 44f

    @Test
    fun `digital silence is exactly the floor`() {
        // Not "almost the floor". A refused capture on this ROM returns a positive length and a
        // buffer of zeros, and that has to be the one reading the meter cannot fake.
        assertEquals(0f, meterFraction(0f), 0f)
        assertEquals(4f, barDp(0f), 0f)
    }

    @Test
    fun `full scale is the top`() {
        assertEquals(1f, meterFraction(1f), 1e-6f)
    }

    @Test
    fun `this fleet's typical speech is nowhere near the floor`() {
        // -36 dBFS and -33 dBFS: the measured median recording loudness on these devices.
        val quiet = meterFraction(0.0158f)   // -36 dBFS
        val loud = meterFraction(0.0224f)    // -33 dBFS
        assertTrue("-36 dBFS should reach at least a third of the meter, was $quiet", quiet > 0.33f)
        assertTrue("-33 dBFS should reach at least a third of the meter, was $loud", loud > 0.38f)
        // The whole point, stated in the units on screen: a talking person and a dead microphone
        // must not be a rounding error apart. The linear scale this replaced put them 0.7dp apart.
        assertTrue(
            "speech must be visibly taller than silence, was ${barDp(quiet) - barDp(0f)}dp",
            barDp(quiet) - barDp(0f) > 10f,
        )
    }

    @Test
    fun `the scale is monotonic`() {
        var previous = -1f
        for (peak in listOf(0f, 0.001f, 0.01f, 0.02f, 0.05f, 0.1f, 0.3f, 0.6f, 1f)) {
            val v = meterFraction(peak)
            assertTrue("meterFraction must not decrease at $peak", v >= previous)
            previous = v
        }
    }

    @Test
    fun `anything below the floor of the scale reads as the floor`() {
        // -80 dBFS is not silence, but it is not something a person is going to see either, and
        // the warning line — which keys off exact zero, not off this — is what covers real faults.
        assertEquals(0f, meterFraction(0.0001f), 1e-6f)
    }

    @Test
    fun `the meter actually draws through this scale`() {
        // Testing the function alone would stay green with the call site reverted to the raw
        // amplitude, which is precisely the defect. Comments stripped so prose cannot satisfy it.
        val screen = File("src/main/java/com/benzn/grandtime/ui/RecordingScreen.kt").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString(LF) { it.substringBefore("//") }
        val bar = screen.indexOf("val target =")
        assertTrue("the bar's target not found", bar >= 0)
        assertTrue(
            "the bar height must go through meterFraction, not the raw amplitude",
            screen.substring(bar, bar + 80).contains("meterFraction("),
        )
    }

    @Test
    fun `out of range input is clamped, not propagated`() {
        assertEquals(1f, meterFraction(4f), 1e-6f)
        assertEquals(0f, meterFraction(-1f), 0f)
    }
}

private const val LF = "\n"
