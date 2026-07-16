package com.benzn.grandtime.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskCoreTest {
    private fun core() = AskCore()

    @Test fun down_when_idle_and_not_recording_starts_listening() {
        val c = core()
        val cmds = c.onPttDown(videoRecording = false)
        assertEquals(AskState.Listening, c.state)
        assertTrue(cmds.contains(AskCommand.PlayListeningCue))
        assertTrue(cmds.contains(AskCommand.StartRecording))
        assertTrue(cmds.contains(AskCommand.ArmCapTimer))
    }

    @Test fun down_during_video_recording_is_busy_and_stays_idle() {
        val c = core()
        val cmds = c.onPttDown(videoRecording = true)
        assertEquals(AskState.Idle, c.state)
        assertEquals(listOf(AskCommand.PlayBusyCue), cmds)
    }

    @Test fun up_while_listening_sends_and_goes_thinking() {
        val c = core().apply { onPttDown(false) }
        val cmds = c.onPttUp()
        assertEquals(AskState.Thinking, c.state)
        assertTrue(cmds.contains(AskCommand.CancelCapTimer))
        assertTrue(cmds.contains(AskCommand.StopRecording))
        assertTrue(cmds.contains(AskCommand.PlayThinkingCue))
        assertTrue(cmds.contains(AskCommand.SendClip))
    }

    @Test fun cap_reached_while_listening_auto_sends() {
        val c = core().apply { onPttDown(false) }
        val cmds = c.onCapReached()
        assertEquals(AskState.Thinking, c.state)
        assertTrue(cmds.contains(AskCommand.StopRecording))
        assertTrue(cmds.contains(AskCommand.SendClip))
    }

    @Test fun up_when_not_listening_is_noop() {
        val c = core()
        assertEquals(emptyList<AskCommand>(), c.onPttUp())
        assertEquals(AskState.Idle, c.state)
    }

    @Test fun answer_while_thinking_plays_and_goes_playing() {
        val c = core().apply { onPttDown(false); onPttUp() }
        val cmds = c.onAnswer("UklGRg==")
        assertEquals(AskState.Playing, c.state)
        assertEquals(listOf(AskCommand.PlayAnswer("UklGRg==")), cmds)
    }

    @Test fun error_returns_to_idle_and_plays_error_cue() {
        val c = core().apply { onPttDown(false); onPttUp() }
        val cmds = c.onError()
        assertEquals(AskState.Idle, c.state)
        assertTrue(cmds.contains(AskCommand.PlayErrorCue))
    }

    @Test fun playback_done_returns_to_idle() {
        val c = core().apply { onPttDown(false); onPttUp(); onAnswer("x") }
        c.onPlaybackDone()
        assertEquals(AskState.Idle, c.state)
    }

    @Test fun reentrant_down_while_listening_is_ignored() {
        val c = core().apply { onPttDown(false) }
        assertEquals(emptyList<AskCommand>(), c.onPttDown(false))
        assertEquals(AskState.Listening, c.state)
    }

    // Serialization invariant AskManager relies on: once the first event moves
    // out of Listening -> Thinking, the second (near-simultaneous) event no-ops
    // and does NOT emit a second SendClip. On AskManager's single-thread
    // dispatcher, onPttUp/onCapReached run atomically, so exactly one wins.

    @Test fun cap_after_pttUp_does_not_second_send() {
        val c = core().apply { onPttDown(false); onPttUp() } // now Thinking
        val cmds = c.onCapReached()
        assertEquals(emptyList<AskCommand>(), cmds)
        assertTrue(!cmds.contains(AskCommand.SendClip))
        assertEquals(AskState.Thinking, c.state)
    }

    @Test fun pttUp_after_cap_does_not_second_send() {
        val c = core().apply { onPttDown(false); onCapReached() } // now Thinking
        val cmds = c.onPttUp()
        assertEquals(emptyList<AskCommand>(), cmds)
        assertTrue(!cmds.contains(AskCommand.SendClip))
        assertEquals(AskState.Thinking, c.state)
    }
}
