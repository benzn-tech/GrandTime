package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule these pin: **a clean session must never be able to erase a dirty one.**
 *
 * `POST /api/org/device/status` writes vitals as a last-write-wins UPDATE on `devices` — a
 * gauge, not a log. Per-session numbers would be wiped by the next healthy report, and the
 * uplink would then read as positive evidence of health. Only monotone counters survive that.
 */
class MicHealthFoldTest {

    private fun session(
        silent: Int = 0, longest: Int = 0, borrowed: Int = 0, peak: Int = 9000, recorded: Int = 60,
    ) = MicSilenceSnapshot(silent, longest, borrowed, peak, recorded)

    @Test fun a_clean_session_cannot_lower_a_recorded_fault() {
        val dirty = MicHealthFold.fold(MicHealth(), session(silent = 40, longest = 29))
        val after = MicHealthFold.fold(dirty, session(silent = 0, longest = 0))
        assertEquals(40, after.silentSecondsS)
        assertEquals(29, after.longestSilentRunS)
    }

    @Test fun silent_seconds_accumulate_and_the_longest_run_is_a_max() {
        var h = MicHealth()
        h = MicHealthFold.fold(h, session(silent = 10, longest = 10))
        h = MicHealthFold.fold(h, session(silent = 5, longest = 3))
        assertEquals(15, h.silentSecondsS)
        assertEquals(10, h.longestSilentRunS)
    }

    @Test fun borrowed_run_annotations_accumulate() {
        var h = MicHealthFold.fold(MicHealth(), session(borrowed = 2))
        h = MicHealthFold.fold(h, session(borrowed = 1))
        assertEquals(3, h.silentRunsWithMicBorrowed)
    }

    /** Peak is the near-miss detector: a microphone dying to a DC offset never hits exact zero,
     *  so the zero counters stay clean while this collapses. The WORST session is the signal. */
    @Test fun lowest_session_peak_keeps_the_worst_session() {
        var h = MicHealthFold.fold(MicHealth(), session(peak = 9000))
        h = MicHealthFold.fold(h, session(peak = 12))
        h = MicHealthFold.fold(h, session(peak = 20000))
        assertEquals(12, h.lowestSessionPeak)
    }

    @Test fun lowest_session_peak_is_null_until_something_was_recorded() {
        assertNull(MicHealth().lowestSessionPeak)
        assertNull(MicHealthFold.fold(MicHealth(), session(recorded = 0)).lowestSessionPeak)
    }

    /** A session that never read a buffer says nothing about the microphone. Folding it would
     *  drive lowestSessionPeak to 0 and manufacture a dead-mic report out of an idle app. */
    @Test fun a_session_that_recorded_nothing_is_not_folded_at_all() {
        val before = MicHealthFold.fold(MicHealth(), session(silent = 7, longest = 7, peak = 100))
        val after = MicHealthFold.fold(before, session(silent = 3, peak = 0, recorded = 0))
        assertEquals(before, after)
    }

    @Test fun sessions_recorded_counts_only_sessions_that_captured_audio() {
        var h = MicHealthFold.fold(MicHealth(), session())
        h = MicHealthFold.fold(h, session(recorded = 0))
        h = MicHealthFold.fold(h, session())
        assertEquals(2, h.sessionsRecorded)
    }
}
