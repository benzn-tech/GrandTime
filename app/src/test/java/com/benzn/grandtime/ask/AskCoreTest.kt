package com.benzn.grandtime.ask

import com.benzn.grandtime.hardware.VibePattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskCoreTest {
    private fun core() = AskCore()

    @Test fun down_when_idle_and_not_recording_starts_listening() {
        val c = core()
        val cmds = c.onPttDown(videoRecording = false)
        assertEquals(AskState.Listening, c.state)
        assertTrue(cmds.contains(AskCommand.PlayListeningCue))
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.SHORT)))
        assertTrue(cmds.contains(AskCommand.StartRecording))
        assertTrue(cmds.contains(AskCommand.ArmCapTimer))
    }

    // Pins the exact emission order for the accepted onPttDown path — nothing else in this
    // file nails down the full list, so an implementation emitting an extra SHORT (or
    // reordering the cue/vibrate/start-recording sequence) would still pass every other test.
    @Test fun down_when_idle_and_not_recording_emits_exact_command_list() {
        val c = core()
        val cmds = c.onPttDown(videoRecording = false, siteVoiceActive = false)
        assertEquals(
            listOf(
                AskCommand.PlayListeningCue,
                AskCommand.StartRecording,
                AskCommand.Vibrate(VibePattern.SHORT),
                AskCommand.ArmCapTimer,
            ),
            cmds,
        )
    }

    // The accept buzz must not be able to fire for a talk that never started. The executor
    // short-circuits on a failed recorder.start(), so ordering the buzz after StartRecording is
    // the whole mechanism — a buzz emitted first would reach the operator before the failure.
    @Test fun accept_buzz_comes_after_start_recording() {
        val cmds = core().onPttDown(videoRecording = false)
        assertTrue(cmds.indexOf(AskCommand.StartRecording)
            < cmds.indexOf(AskCommand.Vibrate(VibePattern.SHORT)))
    }

    // AskPlayer has exactly three completion paths (complete / async error / setup throw). A
    // MediaPlayer that stalls without erroring hits none of them, and nothing in ask/ has a
    // timeout — so the FSM sits in Playing forever, AppState.askActive stays true, and BOTH
    // voice features are dead until the service restarts. These pin the escape hatch.
    @Test fun playback_timeout_from_playing_ends_the_ask() {
        val c = core()
        c.onPttDown(videoRecording = false); c.onPttUp(); c.onAnswer("QQ==")
        assertEquals(AskState.Playing, c.state)
        val cmds = c.onPlaybackTimeout()
        assertEquals(AskState.Idle, c.state)
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)))
    }

    /** A stuck playback is a failure, so it must feel like one. The LONG buzz means "finished,
     *  microphone back" — using it here would report success for an answer nobody heard. */
    @Test fun playback_timeout_buzzes_refusal_not_completion() {
        val c = core()
        c.onPttDown(videoRecording = false); c.onPttUp(); c.onAnswer("QQ==")
        val cmds = c.onPlaybackTimeout()
        assertFalse(cmds.contains(AskCommand.Vibrate(VibePattern.LONG)))
    }

    /** The timer is armed when playback starts and cancelled on the callback, but a cancel can
     *  always lose a race with an already-dispatched fire. A late timeout must do NOTHING —
     *  otherwise a perfectly good answer is followed by an error buzz, and (once Phase B lands)
     *  it would release a microphone borrow belonging to the NEXT exchange. */
    @Test fun a_timeout_arriving_after_a_normal_finish_does_nothing() {
        val c = core()
        c.onPttDown(videoRecording = false); c.onPttUp(); c.onAnswer("QQ==")
        c.onPlaybackDone()
        assertEquals(AskState.Idle, c.state)
        assertEquals(emptyList<AskCommand>(), c.onPlaybackTimeout())
    }

    @Test fun a_timeout_while_listening_does_nothing() {
        val c = core()
        c.onPttDown(videoRecording = false)
        assertEquals(emptyList<AskCommand>(), c.onPlaybackTimeout())
        assertEquals(AskState.Listening, c.state)
    }

    @Test fun a_timeout_while_thinking_does_nothing() {
        val c = core()
        c.onPttDown(videoRecording = false); c.onPttUp()
        assertEquals(AskState.Thinking, c.state)
        assertEquals(emptyList<AskCommand>(), c.onPlaybackTimeout())
    }

    /** After the timeout the operator must be able to ask again — the whole point is that the
     *  feature is not dead until the service restarts. */
    @Test fun a_new_ask_works_after_a_playback_timeout() {
        val c = core()
        c.onPttDown(videoRecording = false); c.onPttUp(); c.onAnswer("QQ==")
        c.onPlaybackTimeout()
        assertTrue(c.onPttDown(videoRecording = false).contains(AskCommand.StartRecording))
        assertEquals(AskState.Listening, c.state)
    }

    // --- Phase B: borrow the mic from the video instead of refusing ---
    //
    // Site-voice has borrowed the mic during video since #91; Ask refused. Same device, two
    // hold-to-talk keys, one borrows and one refuses, purely because nobody wired Ask up.
    // The borrow is HELD THROUGH PLAYBACK: the mic is released before the API call, so
    // returning it at StopRecording would put the agent's spoken answer on the evidence video.

    @Test fun down_during_video_borrows_the_mic_instead_of_refusing() {
        val c = core()
        val cmds = c.onPttDown(videoRecording = true)
        assertEquals(AskState.Listening, c.state)
        assertTrue(cmds.contains(AskCommand.AcquireMicFromCapture))
        assertFalse("refusing is the old behaviour", cmds.contains(AskCommand.PlayBusyCue))
    }

    /** The capture mic has to be freed before Ask opens its own — SiteVoiceCoreTest pins the
     *  same ordering for its path. */
    @Test fun acquire_precedes_start_recording() {
        val cmds = core().onPttDown(videoRecording = true)
        assertTrue(cmds.indexOf(AskCommand.AcquireMicFromCapture)
            < cmds.indexOf(AskCommand.StartRecording))
    }

    @Test fun down_with_no_video_does_not_acquire() {
        assertFalse(core().onPttDown(videoRecording = false)
            .contains(AskCommand.AcquireMicFromCapture))
    }

    /** Site-voice is a real conflict between two voice features, not a resource to borrow.
     *  This refusal stays. */
    @Test fun down_during_site_voice_still_refuses() {
        val c = core()
        val cmds = c.onPttDown(videoRecording = false, siteVoiceActive = true)
        assertEquals(AskState.Idle, c.state)
        assertEquals(
            listOf(AskCommand.PlayBusyCue, AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)),
            cmds,
        )
    }

    @Test fun site_voice_refusal_wins_even_during_video() {
        val cmds = core().onPttDown(videoRecording = true, siteVoiceActive = true)
        assertFalse(cmds.contains(AskCommand.AcquireMicFromCapture))
        assertTrue(cmds.contains(AskCommand.PlayBusyCue))
    }

    /** The whole point of holding: the mic is released at the top of sendClip(), so returning
     *  it here lets the video re-acquire and record the agent answering. */
    @Test fun up_does_not_release_the_borrow() {
        val c = core().apply { onPttDown(videoRecording = true) }
        assertFalse(c.onPttUp().contains(AskCommand.ReleaseMicToCapture))
    }

    @Test fun cap_reached_does_not_release_the_borrow_either() {
        val c = core().apply { onPttDown(videoRecording = true) }
        assertFalse(c.onCapReached().contains(AskCommand.ReleaseMicToCapture))
    }

    /** The LONG buzz means "finished, microphone back". Buzzing before the release states
     *  something that is not true yet. */
    @Test fun playback_done_releases_before_the_long_buzz() {
        val c = core()
        c.onPttDown(videoRecording = true); c.onPttUp(); c.onAnswer("QQ==")
        val cmds = c.onPlaybackDone()
        assertTrue(cmds.contains(AskCommand.ReleaseMicToCapture))
        assertTrue(cmds.indexOf(AskCommand.ReleaseMicToCapture)
            < cmds.indexOf(AskCommand.Vibrate(VibePattern.LONG)))
    }

    /** Every terminal path must return the mic. A missed release leaves the video's audio
     *  track silent for the rest of the recording — worse than the refusal it replaced. */
    @Test fun error_releases_the_borrow() {
        val c = core().apply { onPttDown(videoRecording = true) }
        assertTrue(c.onError().contains(AskCommand.ReleaseMicToCapture))
    }

    @Test fun playback_timeout_releases_the_borrow() {
        val c = core()
        c.onPttDown(videoRecording = true); c.onPttUp(); c.onAnswer("QQ==")
        assertTrue(c.onPlaybackTimeout().contains(AskCommand.ReleaseMicToCapture))
    }

    /** Nothing was borrowed, so nothing may be returned: CaptureManager.end() releases
     *  WHOEVER holds the handover, so a spurious release could return Site-voice's borrow. */
    @Test fun error_without_a_borrow_emits_no_release() {
        val c = core().apply { onPttDown(videoRecording = false) }
        assertFalse(c.onError().contains(AskCommand.ReleaseMicToCapture))
    }

    @Test fun a_borrow_is_released_exactly_once() {
        val c = core()
        c.onPttDown(videoRecording = true); c.onPttUp(); c.onAnswer("QQ==")
        assertTrue(c.onPlaybackDone().contains(AskCommand.ReleaseMicToCapture))
        assertFalse("the borrow already ended", c.onError().contains(AskCommand.ReleaseMicToCapture))
    }

    /** A second exchange that borrows nothing must not inherit the first one's release. */
    @Test fun a_later_ask_without_video_does_not_release() {
        val c = core()
        c.onPttDown(videoRecording = true); c.onPttUp(); c.onAnswer("QQ=="); c.onPlaybackDone()
        c.onPttDown(videoRecording = false)
        assertFalse(c.onError().contains(AskCommand.ReleaseMicToCapture))
    }

    @Test fun re_entrant_down_while_playing_does_not_acquire_again() {
        val c = core()
        c.onPttDown(videoRecording = true); c.onPttUp(); c.onAnswer("QQ==")
        assertEquals(emptyList<AskCommand>(), c.onPttDown(videoRecording = true))
    }

    /** onDiscreteAsk delegates to onPttDown, but nothing pinned that it carries the borrow. */
    @Test fun a_discrete_tap_during_video_acquires_too() {
        assertTrue(core().onDiscreteAsk(videoRecording = true)
            .contains(AskCommand.AcquireMicFromCapture))
    }

    @Test fun down_during_site_voice_is_busy_and_stays_idle() {
        val c = core()
        val cmds = c.onPttDown(videoRecording = false, siteVoiceActive = true)
        assertEquals(AskState.Idle, c.state)
        assertEquals(
            listOf(AskCommand.PlayBusyCue, AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)),
            cmds
        )
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
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)))
    }

    @Test fun playback_done_returns_to_idle() {
        val c = core().apply { onPttDown(false); onPttUp(); onAnswer("x") }
        val cmds = c.onPlaybackDone()
        assertEquals(AskState.Idle, c.state)
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.LONG)))
    }

    // A failed answer playback must route to onError()'s DOUBLE_SHORT + error cue, never to
    // onPlaybackDone()'s LONG "success" buzz — that routing decision (AskManager picking one
    // of these two based on whether MediaPlayer actually finished) is what AskPlayerTest's
    // ok=false coverage exercises; this pins what onError() emits while Playing so the two
    // outcomes stay distinguishable at the core level too.
    @Test fun playback_error_routes_to_onError_not_the_long_buzz() {
        val c = core().apply { onPttDown(false); onPttUp(); onAnswer("x") } // now Playing
        val cmds = c.onError()
        assertEquals(AskState.Idle, c.state)
        assertEquals(
            listOf(AskCommand.CancelCapTimer, AskCommand.PlayErrorCue, AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)),
            cmds,
        )
        assertFalse(cmds.contains(AskCommand.Vibrate(VibePattern.LONG)))
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

    @Test
    fun `activation buzzes once`() {
        val core = AskCore()
        val cmds = core.onPttDown(videoRecording = false, siteVoiceActive = false)
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.SHORT)))
    }

    // Two buzzes already means "refused" everywhere else on this device. Site-voice is now the
    // only refusal on this path -- video is borrowed from, not refused (Phase B).
    @Test
    fun `refusal buzzes twice`() {
        val core = AskCore()
        val cmds = core.onPttDown(videoRecording = false, siteVoiceActive = true)
        assertTrue(cmds.contains(AskCommand.PlayBusyCue))
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)))
    }

    @Test
    fun `finishing buzzes long`() {
        val core = AskCore()
        core.onPttDown(videoRecording = false, siteVoiceActive = false)
        core.onPttUp()
        core.onAnswer("aGk=")
        val cmds = core.onPlaybackDone()
        assertEquals(listOf(AskCommand.Vibrate(VibePattern.LONG)), cmds)
    }

    // The long buzz says "this is over". A playback-done that arrives when nothing
    // was playing must not fire one.
    @Test
    fun `playback done while idle buzzes nothing`() {
        val core = AskCore()
        assertEquals(emptyList<AskCommand>(), core.onPlaybackDone())
    }

    @Test
    fun `error buzzes twice, like every other failure`() {
        val core = AskCore()
        core.onPttDown(videoRecording = false, siteVoiceActive = false)
        val cmds = core.onError()
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)))
    }

    // The keymap-routed tap has no HoldToTalkGate in front of it, so it is a
    // separate path into the same decision — it must feel the same.
    @Test
    fun `discrete tap buzzes once on activation`() {
        val core = AskCore()
        val cmds = core.onDiscreteAsk(videoRecording = false, siteVoiceActive = false)
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.SHORT)))
    }
}
