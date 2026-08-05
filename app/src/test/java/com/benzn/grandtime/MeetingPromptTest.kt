package com.benzn.grandtime

import com.benzn.grandtime.capture.GroupExit
import com.benzn.grandtime.capture.MeetingPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 20-second "has the meeting ended?" prompt.
 *
 * Every case here is a way of asking the wrong question. A prompt that fires
 * during live recording, or about a meeting that already ended, trains people
 * to dismiss it without reading — which costs more than never asking.
 */
class MeetingPromptTest {
    private val MIN = 60_000L
    private val group = GroupExit.PendingGroup("b".repeat(32), heldSinceMillis = 0)

    @Test
    fun asksWhenTheDeviceIsIdleAndStillInAMeeting() {
        assertTrue(MeetingPrompt.shouldAsk(group, isRecording = false, nowMillis = MIN))
    }

    @Test
    fun staysQuietWhenRecordingHasRestarted() {
        // They paused rather than finished. Speaking here would interrupt live
        // audio to ask whether the thing being recorded has stopped.
        assertFalse(MeetingPrompt.shouldAsk(group, isRecording = true, nowMillis = MIN))
    }

    @Test
    fun staysQuietWhenSomeoneAlreadyEndedTheMeeting() {
        assertFalse(MeetingPrompt.shouldAsk(null, isRecording = false, nowMillis = MIN))
    }

    @Test
    fun staysQuietWhenTheGroupHasLapsed() {
        // Asking about a group the device already dropped invites "yes", which
        // would end a meeting that is not this one.
        assertFalse(MeetingPrompt.shouldAsk(group, isRecording = false, nowMillis = 16 * MIN))
    }

    @Test
    fun soloRecordingIsNeverInterrupted() {
        // The overwhelmingly common case. It has no group, so it must never see
        // this prompt at all.
        assertFalse(MeetingPrompt.shouldAsk(null, isRecording = false, nowMillis = 0))
    }

    @Test
    fun theDelayOutlastsAMisTouchButNotTheWalkBackToTheTruck() {
        assertEquals(20_000L, MeetingPrompt.DELAY_MILLIS)
    }
}
