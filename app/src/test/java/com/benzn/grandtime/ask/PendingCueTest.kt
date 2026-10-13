package com.benzn.grandtime.ask

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The filler waits half a second after the key comes up. The race that creates: an answer or an
 * error that arrives INSIDE that half second must keep the line from ever starting, or the device
 * says "checking your records now" after it has already answered. Driven on virtual time, so these
 * assert what happens at each instant rather than that a word appears in a source file.
 */
class PendingCueTest {

    @Test
    fun `nothing plays until the delay has passed`() = runTest {
        var plays = 0
        val cue = PendingCue(this, 500) { plays++ }

        cue.schedule()
        advanceTimeBy(499); runCurrent()
        assertEquals("must not start early", 0, plays)

        advanceTimeBy(1); runCurrent()
        assertEquals("starts once the half second is up", 1, plays)
    }

    @Test
    fun `an answer inside the window means the line never starts`() = runTest {
        var plays = 0
        val cue = PendingCue(this, 500) { plays++ }

        cue.schedule()
        advanceTimeBy(300); runCurrent()
        cue.cancel()               // what stopFiller() does when the answer lands at 300 ms
        advanceUntilIdle()

        assertEquals("the filler must not follow the answer it was meant to precede", 0, plays)
    }

    @Test
    fun `a second release replaces the first rather than queueing two lines`() = runTest {
        var plays = 0
        val cue = PendingCue(this, 500) { plays++ }

        cue.schedule()
        advanceTimeBy(200); runCurrent()
        cue.schedule()
        advanceUntilIdle()

        assertEquals("one line, not two back to back", 1, plays)
    }

    @Test
    fun `cancelling with nothing pending is harmless`() = runTest {
        var plays = 0
        val cue = PendingCue(this, 500) { plays++ }

        cue.cancel()
        cue.schedule()
        advanceUntilIdle()

        assertEquals(1, plays)
    }
}
