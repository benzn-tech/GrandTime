package com.benzn.grandtime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera on this terminal will not focus itself. Measured: CONTROL_AF_STATE stayed INACTIVE
 * and LENS_FOCUS_DISTANCE stayed at 0.0 (infinity) through four explicit AF triggers, on a lens
 * that advertises MACRO and a 5cm minimum. So the lens is placed by hand, and because
 * focusDistanceCalibration is UNCALIBRATED the dioptre numbers cannot be turned into a distance —
 * only stepped through until something decodes.
 */
class FocusSweepTest {

    /** Most tests want the sweep already in charge; taking over is its own test below. */
    private fun sweep(dwell: Long = 700) = FocusSweep(dwellMs = dwell).apply {
        repeat(20) { noteAf(inactive = true) }
    }

    @Test fun it_starts_at_infinity_where_the_lens_already_is() {
        // A code that reads today must keep reading. The sweep only costs something in the case
        // that was already failing.
        assertEquals(0f, sweep().position, 0f)
    }

    @Test fun nothing_moves_until_the_dwell_has_passed() {
        val s = sweep(dwell = 700)
        assertFalse(s.onFrame(decoded = false, nowMs = 1_000))   // first frame starts the clock
        assertFalse(s.onFrame(decoded = false, nowMs = 1_500))
        assertEquals(0f, s.position, 0f)
        assertTrue(s.onFrame(decoded = false, nowMs = 1_800))
        assertEquals(2f, s.position, 0f)
    }

    @Test fun it_walks_the_whole_ladder_and_comes_back_round() {
        val s = sweep(dwell = 100)
        s.onFrame(false, 0)
        val seen = mutableListOf(s.position)
        var t = 100L
        repeat(FocusSweep.DEFAULT_LADDER.size) {
            s.onFrame(false, t); t += 100
            seen += s.position
        }
        assertEquals(FocusSweep.DEFAULT_LADDER, seen.dropLast(1))
        assertEquals("wraps rather than sticking at the near limit", 0f, seen.last(), 0f)
    }

    @Test fun a_decode_stops_the_sweep_where_it_worked() {
        val s = sweep(dwell = 100)
        s.onFrame(false, 0)
        s.onFrame(false, 200)
        val found = s.position
        assertFalse(s.onFrame(decoded = true, nowMs = 300))
        assertTrue(s.isSettled)
        // Later frames must not walk away from the position that just read a code — the operator
        // may hold up a second code from the same distance.
        assertFalse(s.onFrame(decoded = false, nowMs = 5_000))
        assertEquals(found, s.position, 0f)
    }

    @Test fun reset_starts_a_new_session_from_infinity() {
        val s = sweep(dwell = 100)
        s.onFrame(false, 0); s.onFrame(false, 200); s.onFrame(true, 300)
        s.reset()
        assertFalse(s.isSettled)
        assertEquals(0f, s.position, 0f)
    }

    @Test fun the_ladder_stays_inside_what_this_lens_can_do() {
        // minimumFocusDistance measured at 20 dioptres. Asking for more is undefined behaviour
        // on a HAL that already ignores half the focus API.
        assertTrue(FocusSweep.DEFAULT_LADDER.all { it in 0f..20f })
        assertEquals("infinity first", 0f, FocusSweep.DEFAULT_LADDER.first(), 0f)
        assertEquals(FocusSweep.DEFAULT_LADDER.sorted(), FocusSweep.DEFAULT_LADDER)
    }


    // ------------------------------------------------------------------
    // Taking over is measured, not assumed. This is a fleet of twenty
    // terminals; only one of them has been shown to ignore autofocus.
    // ------------------------------------------------------------------

    @Test fun a_camera_that_focuses_itself_is_left_alone() {
        val s = FocusSweep(dwellMs = 1)
        repeat(100) { s.noteAf(inactive = false) }
        assertFalse(s.hasTakenOver)
        // ...and the sweep must not move the lens, however many frames go by.
        assertFalse(s.onFrame(decoded = false, nowMs = 0))
        assertFalse(s.onFrame(decoded = false, nowMs = 10_000))
        assertEquals(0f, s.position, 0f)
    }

    @Test fun control_passes_over_only_after_a_sustained_run_of_inactive() {
        val s = FocusSweep(dwellMs = 1)
        repeat(15) { assertFalse("frame $it", s.noteAf(inactive = true)) }
        assertFalse(s.hasTakenOver)
        assertTrue("the frame it takes over on", s.noteAf(inactive = true))
        assertTrue(s.hasTakenOver)
    }

    @Test fun the_signal_is_reported_once_not_every_frame() {
        val s = FocusSweep(dwellMs = 1)
        repeat(16) { s.noteAf(inactive = true) }
        repeat(50) { assertFalse(s.noteAf(inactive = true)) }
    }

    @Test fun one_good_state_undoes_the_whole_run() {
        // A camera between hunts reports INACTIVE too. Only a camera that NEVER acts should be
        // taken over, so evidence that it acted resets the count rather than nudging it.
        val s = FocusSweep(dwellMs = 1)
        repeat(15) { s.noteAf(inactive = true) }
        s.noteAf(inactive = false)
        repeat(15) { assertFalse(s.noteAf(inactive = true)) }
        assertFalse(s.hasTakenOver)
    }
}
