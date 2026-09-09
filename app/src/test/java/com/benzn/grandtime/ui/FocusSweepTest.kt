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
        repeat(64) { noteAf(inactive = true) }   // past the grace period and the take-over run
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
        val s = FocusSweep(dwellMs = 1, graceFrames = 0)
        repeat(100) { s.noteAf(inactive = false) }
        assertFalse(s.hasTakenOver)
        // ...and the sweep must not move the lens, however many frames go by.
        assertFalse(s.onFrame(decoded = false, nowMs = 0))
        assertFalse(s.onFrame(decoded = false, nowMs = 10_000))
        assertEquals(0f, s.position, 0f)
    }

    @Test fun control_passes_over_only_after_a_sustained_run_of_inactive() {
        val s = FocusSweep(dwellMs = 1, graceFrames = 0)
        repeat(15) { assertFalse("frame $it", s.noteAf(inactive = true)) }
        assertFalse(s.hasTakenOver)
        assertTrue("the frame it takes over on", s.noteAf(inactive = true))
        assertTrue(s.hasTakenOver)
    }

    /**
     * In CONTINUOUS_PICTURE the state is INACTIVE until the HAL chooses to start a passive scan,
     * and MediaTek's waits for a scene change. Startup is therefore exactly when a healthy camera
     * looks dead, and it is also the only moment this rule ever gets to fire.
     */
    @Test fun the_first_second_does_not_count_against_the_camera() {
        val s = FocusSweep(dwellMs = 1, graceFrames = 16)
        repeat(16 + 15) { assertFalse(s.noteAf(inactive = true)) }
        assertFalse(s.hasTakenOver)
        assertTrue(s.noteAf(inactive = true))
    }

    /**
     * AF_MODE_OFF reports INACTIVE forever, so after the switch the state says nothing about the
     * camera -- it only reflects our own decision. Unlatched, results still in flight from before
     * the switch would hand control back and forth for a second at a time.
     */
    @Test fun once_the_lens_is_ours_the_state_stops_being_evidence() {
        val s = FocusSweep(dwellMs = 1, graceFrames = 0)
        repeat(16) { s.noteAf(inactive = true) }
        assertTrue(s.hasTakenOver)
        repeat(50) { assertFalse(s.noteAf(inactive = false)) }
        assertTrue("a late PASSIVE_SCAN must not hand the lens back", s.hasTakenOver)
    }

    /**
     * A refused code sends the operator to a different distance. A lens still pinned to the rung
     * that read the last one would never find it.
     */
    @Test fun the_hold_after_a_decode_expires() {
        val s = FocusSweep(dwellMs = 1, graceFrames = 0, unsettleAfterFrames = 5).apply {
            repeat(16) { noteAf(inactive = true) }
        }
        s.onFrame(decoded = false, nowMs = 0)
        s.onFrame(decoded = true, nowMs = 10)
        assertTrue(s.isSettled)
        repeat(4) { assertFalse(s.onFrame(decoded = false, nowMs = 100L + it)) }
        assertTrue("the fifth non-decoding frame releases it", s.onFrame(false, 200))
        assertFalse(s.isSettled)
    }

    @Test fun the_signal_is_reported_once_not_every_frame() {
        val s = FocusSweep(dwellMs = 1, graceFrames = 0)
        repeat(16) { s.noteAf(inactive = true) }
        repeat(50) { assertFalse(s.noteAf(inactive = true)) }
    }

    @Test fun one_good_state_undoes_the_whole_run() {
        // A camera between hunts reports INACTIVE too. Only a camera that NEVER acts should be
        // taken over, so evidence that it acted resets the count rather than nudging it.
        val s = FocusSweep(dwellMs = 1, graceFrames = 0)
        repeat(15) { s.noteAf(inactive = true) }
        s.noteAf(inactive = false)
        repeat(15) { assertFalse(s.noteAf(inactive = true)) }
        assertFalse(s.hasTakenOver)
    }
}
