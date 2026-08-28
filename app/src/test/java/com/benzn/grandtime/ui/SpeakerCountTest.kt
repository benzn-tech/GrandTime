package com.benzn.grandtime.ui

import com.benzn.grandtime.core.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The speaker count arrives on the chunk-upload response, which is the only channel back to a
 * body-worn device. Two things have to hold for the number under the timer to be trustworthy:
 * it belongs to the session on screen, and "the backend has not said" never renders as a zero.
 */
class SpeakerCountTest {

    private fun reported(sessionId: String, count: Int) = AppState.SessionSpeakers(sessionId, count)

    @Test fun `the current session's count is shown`() {
        assertEquals(3, speakersForSession(reported("s1", 3), "s1"))
    }

    @Test fun `the previous session's count is not`() {
        // The case this tagging exists for: chunks from the recording that just ended keep
        // uploading while the next one runs. Showing that count under the new timer would be a
        // confident wrong answer.
        assertNull(speakersForSession(reported("s1", 3), "s2"))
    }

    @Test fun `nothing reported yet is nothing shown`() {
        assertNull(speakersForSession(null, "s1"))
    }

    @Test fun `an idle screen shows no count even if one is held`() {
        assertNull(speakersForSession(reported("s1", 3), null))
    }

    @Test fun `absent renders as nothing at all`() {
        assertEquals("", speakerSuffix(null))
    }

    @Test fun `a confirmed zero does render`() {
        // Not the same as absent: the backend processed the batch and heard nobody, and a
        // microphone that is running and confirming nobody is exactly what the operator wants
        // to find out from a two-second glance.
        assertEquals(" · 0 speakers", speakerSuffix(0))
    }

    @Test fun `one speaker is singular`() {
        assertEquals(" · 1 speaker", speakerSuffix(1))
    }

    @Test fun `more than one is plural`() {
        assertEquals(" · 4 speakers", speakerSuffix(4))
    }
}
