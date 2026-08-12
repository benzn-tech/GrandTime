package com.benzn.grandtime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screen used to say "Scanning…" in three situations that need opposite actions from the
 * operator: nothing in frame (aim), a code too small or blurry to read (move closer), and a code
 * the server rejected (get a new one). The device owner worked through two wrong theories —
 * expired code, then broken autofocus — before finding it was simply too small. Both guesses were
 * invited by the message.
 */
class ScanHintTest {

    private fun hints(persistence: Int = 3) = ScanHints(locatedFramesToSpeak = persistence)

    @Test fun nothing_in_frame_keeps_the_aiming_prompt() {
        val h = hints()
        repeat(10) { h.onFrame(ScanFrame.NOTHING) }
        assertEquals(null, h.hint)
    }

    /** A located-but-unreadable code is the case the operator cannot diagnose from the old
     *  message, and it is the one that has a concrete remedy. */
    @Test fun a_code_that_cannot_be_read_says_so() {
        val h = hints()
        repeat(3) { h.onFrame(ScanFrame.LOCATED_UNREADABLE) }
        assertEquals(ScanHints.TOO_SMALL, h.hint)
    }

    /** ZXing's finder-pattern search accepts any three 1:1:3:1:1 blobs, so text and texture can
     *  produce a false "located". One frame must not be enough to change what the screen says. */
    @Test fun a_single_stray_detection_does_not_speak() {
        val h = hints()
        h.onFrame(ScanFrame.LOCATED_UNREADABLE)
        assertEquals(null, h.hint)
    }

    @Test fun the_run_has_to_be_consecutive() {
        val h = hints()
        h.onFrame(ScanFrame.LOCATED_UNREADABLE)
        h.onFrame(ScanFrame.LOCATED_UNREADABLE)
        h.onFrame(ScanFrame.NOTHING)             // breaks the run
        h.onFrame(ScanFrame.LOCATED_UNREADABLE)
        assertEquals(null, h.hint)
    }

    @Test fun aiming_away_clears_the_hint() {
        val h = hints()
        repeat(3) { h.onFrame(ScanFrame.LOCATED_UNREADABLE) }
        assertEquals(ScanHints.TOO_SMALL, h.hint)
        repeat(3) { h.onFrame(ScanFrame.NOTHING) }
        assertEquals(null, h.hint)
    }

    /** After ~15s of seeing nothing at all, saying "Scanning…" is a claim of progress that is not
     *  true. Name the remaining possibilities instead. */
    @Test fun a_long_spell_of_nothing_admits_defeat() {
        val h = hints()
        h.onFrame(ScanFrame.NOTHING, elapsedMs = 0)
        h.onFrame(ScanFrame.NOTHING, elapsedMs = 15_001)
        assertEquals(ScanHints.NOTHING_FOR_A_WHILE, h.hint)
    }

    /** …but a concrete, actionable hint outranks the vague timeout one. */
    @Test fun the_too_small_hint_outranks_the_timeout_hint() {
        val h = hints()
        // The clock starts at the first frame, so a lone frame stamped 20s cannot have been
        // waiting 20s -- seed the start before asserting on the timeout.
        h.onFrame(ScanFrame.NOTHING, elapsedMs = 0)
        h.onFrame(ScanFrame.NOTHING, elapsedMs = 20_000)
        assertEquals(ScanHints.NOTHING_FOR_A_WHILE, h.hint)
        repeat(3) { h.onFrame(ScanFrame.LOCATED_UNREADABLE, elapsedMs = 21_000) }
        assertEquals(ScanHints.TOO_SMALL, h.hint)
    }

    /** A decode restarts the clock: the operator is making progress, whatever happens next is the
     *  attempt's business, not the frame loop's. */
    @Test fun a_decode_resets_everything() {
        val h = hints()
        repeat(3) { h.onFrame(ScanFrame.LOCATED_UNREADABLE) }
        h.onFrame(ScanFrame.DECODED)
        assertEquals(null, h.hint)
    }

    @Test fun the_timeout_clock_restarts_after_a_decode() {
        val h = hints()
        h.onFrame(ScanFrame.NOTHING, elapsedMs = 0)
        h.onFrame(ScanFrame.DECODED, elapsedMs = 10_000)
        h.onFrame(ScanFrame.NOTHING, elapsedMs = 20_000)   // only 10s since the decode
        assertEquals(null, h.hint)
    }

    /** Every string this screen shows is English: the operators are NZ construction staff, and it
     *  is a project-wide constraint that user-visible copy is English. */
    @Test fun the_strings_are_english_and_short_enough_for_a_320dp_screen() {
        for (s in listOf(ScanHints.TOO_SMALL, ScanHints.NOTHING_FOR_A_WHILE)) {
            assertEquals("ASCII only: $s", true, s.all { it.code < 128 })
            // ~35-38 Latin chars fit one line at titleMedium on 320dp; two lines is the cap
            // before the hint starts covering the preview it is meant to help aim.
            assertEquals("<= 2 lines: $s", true, s.length <= 76)
        }
    }
}
