package com.benzn.grandtime.capture

import com.benzn.grandtime.keymap.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCoreTest {

    private var idCounter = 0
    private fun core() = CaptureCore(clock = { 1000L }, newId = { "id${idCounter++}" })

    @Test
    fun `idle video starts segment 1 and enters RecordingVideo`() {
        val c = core()
        val cmds = c.onAction(KeyAction.START_STOP_VIDEO)
        assertTrue(cmds.contains(CaptureCommand.StartVideoSegment("id0", 1)))
        assertEquals(CaptureState.RecordingVideo("id0", 1, 1000L), c.state)
    }

    @Test
    fun `video key while recording pauses then finalize enters PausedVideo`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val stopCmds = c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(StopReason.PAUSE)), stopCmds)
        val doneCmds = c.onVideoFinalized(StopReason.PAUSE)
        assertEquals(CaptureState.PausedVideo("id0", 1, 1000L, 1000L), c.state)
        assertTrue(doneCmds.any { it is CaptureCommand.Notify })
    }

    @Test
    fun `photo during video uses same session and does not change state`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val cmds = c.onAction(KeyAction.TAKE_PHOTO)
        assertTrue(cmds.contains(CaptureCommand.TakePhoto("id0")))
        assertTrue(c.state is CaptureState.RecordingVideo)
    }

    @Test
    fun `photo during audio uses same session`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_AUDIO)
        val cmds = c.onAction(KeyAction.TAKE_PHOTO)
        assertTrue(cmds.contains(CaptureCommand.TakePhoto("id0")))
        assertTrue(c.state is CaptureState.RecordingAudio)
    }

    @Test
    fun `audio key during video is rejected with double vibrate`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val cmds = c.onAction(KeyAction.START_STOP_AUDIO)
        assertTrue(cmds.contains(CaptureCommand.Vibrate(2)))
        assertTrue(cmds.none { it is CaptureCommand.StartAudio })
        assertTrue(c.state is CaptureState.RecordingVideo)
    }

    @Test
    fun `video key during audio is rejected`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_AUDIO)
        val cmds = c.onAction(KeyAction.START_STOP_VIDEO)
        assertTrue(cmds.contains(CaptureCommand.Vibrate(2)))
        assertTrue(c.state is CaptureState.RecordingAudio)
    }

    @Test
    fun `segment timer rolls to next segment with same session`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(StopReason.ROLLOVER)), c.onSegmentTimerFired())
        val rollCmds = c.onVideoFinalized(StopReason.ROLLOVER)
        assertTrue(rollCmds.contains(CaptureCommand.StartVideoSegment("id0", 2)))
        assertEquals(CaptureState.RecordingVideo("id0", 2, 1000L), c.state)
    }

    @Test
    fun `segment timer in idle is a no-op`() {
        assertTrue(core().onSegmentTimerFired().isEmpty())
    }

    @Test
    fun `standalone photos get fresh sessions`() {
        val c = core()
        val first = c.onAction(KeyAction.TAKE_PHOTO)
        val second = c.onAction(KeyAction.TAKE_PHOTO)
        assertTrue(first.contains(CaptureCommand.TakePhoto("id0")))
        assertTrue(second.contains(CaptureCommand.TakePhoto("id1")))
        assertEquals(CaptureState.Idle, c.state)
    }

    @Test
    fun `torch and volume pass through in any state`() {
        val c = core()
        assertTrue(c.onAction(KeyAction.TOGGLE_TORCH).contains(CaptureCommand.ToggleTorch))
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertTrue(c.onAction(KeyAction.TOGGLE_TORCH).contains(CaptureCommand.ToggleTorch))
        assertTrue(c.onAction(KeyAction.ADJUST_VOLUME).contains(CaptureCommand.CycleVolume))
    }

    @Test
    fun `audio stop then finalize returns to idle`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_AUDIO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopAudio), c.onAction(KeyAction.START_STOP_AUDIO))
        c.onAudioFinalized()
        assertEquals(CaptureState.Idle, c.state)
    }

    @Test
    fun `failure resets to idle with double vibrate and message`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val cmds = c.onFailure("Camera unavailable")
        assertEquals(CaptureState.Idle, c.state)
        assertTrue(cmds.contains(CaptureCommand.Vibrate(2)))
        assertTrue(cmds.contains(CaptureCommand.Notify("Camera unavailable")))
    }

    @Test
    fun `unhandled actions produce no commands`() {
        assertTrue(core().onAction(KeyAction.SEND_SOS).isEmpty())
        assertTrue(core().onAction(KeyAction.ASK_AGENT).isEmpty())
    }

    @Test
    fun short_press_while_recording_pauses_not_stops() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO) // Idle -> RecordingVideo(seg 1)
        val cmds = c.onAction(KeyAction.START_STOP_VIDEO) // Recording -> pause
        assertTrue(cmds.any { it is CaptureCommand.StopVideo && it.reason == StopReason.PAUSE })
        // state becomes PausedVideo only after finalize:
        c.onVideoFinalized(StopReason.PAUSE)
        val s = c.state
        assertTrue(s is CaptureState.PausedVideo && s.segmentIndex == 1)
    }

    @Test
    fun short_press_while_paused_resumes_same_session_next_segment() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO) // Recording seg1
        c.onAction(KeyAction.START_STOP_VIDEO) // -> pause cmd
        c.onVideoFinalized(StopReason.PAUSE) // PausedVideo seg1
        val sid = (c.state as CaptureState.PausedVideo).sessionId
        val cmds = c.onAction(KeyAction.START_STOP_VIDEO) // resume
        val start = cmds.filterIsInstance<CaptureCommand.StartVideoSegment>().single()
        assertEquals(sid, start.sessionId) // SAME session
        assertEquals(2, start.segmentIndex) // next segment
        assertTrue(c.state is CaptureState.RecordingVideo)
    }

    @Test
    fun long_press_while_recording_ends_with_end_reason() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val cmds = c.onAction(KeyAction.END_VIDEO)
        assertTrue(cmds.any { it is CaptureCommand.StopVideo && it.reason == StopReason.END })
        c.onVideoFinalized(StopReason.END)
        assertTrue(c.state is CaptureState.Idle)
    }

    @Test
    fun long_press_while_paused_ends_session_directly() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        c.onAction(KeyAction.START_STOP_VIDEO)
        c.onVideoFinalized(StopReason.PAUSE) // PausedVideo
        val sid = (c.state as CaptureState.PausedVideo).sessionId
        val cmds = c.onAction(KeyAction.END_VIDEO)
        assertTrue(cmds.any { it is CaptureCommand.EndPausedSession && it.sessionId == sid })
        assertTrue(c.state is CaptureState.Idle)
    }

    @Test
    fun segment_timer_rollover_still_advances_segment() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        c.onSegmentTimerFired() // StopVideo(ROLLOVER)
        c.onVideoFinalized(StopReason.ROLLOVER)
        val s = c.state
        assertTrue(s is CaptureState.RecordingVideo && s.segmentIndex == 2)
    }

    @Test
    fun startedAtMillis_preserved_through_pause_resume() {
        val c = core() // clock starts at T0
        c.onAction(KeyAction.START_STOP_VIDEO)
        val t0 = (c.state as CaptureState.RecordingVideo).startedAtMillis
        c.onAction(KeyAction.START_STOP_VIDEO)
        c.onVideoFinalized(StopReason.PAUSE)
        assertEquals(t0, (c.state as CaptureState.PausedVideo).startedAtMillis)
        c.onAction(KeyAction.START_STOP_VIDEO) // resume
        assertEquals(t0, (c.state as CaptureState.RecordingVideo).startedAtMillis)
    }

    @Test
    fun resume_shifts_start_forward_by_pause_duration_so_timer_excludes_pause() {
        var t = 1000L
        val c = CaptureCore(clock = { t }, newId = { "id" })
        c.onAction(KeyAction.START_STOP_VIDEO)       // start at 1000 → startedAt=1000
        t = 6000L                                     // recorded 5s
        c.onAction(KeyAction.START_STOP_VIDEO)        // pause cmd
        c.onVideoFinalized(StopReason.PAUSE)          // PausedVideo pausedAt=6000
        t = 16000L                                    // paused 10s
        c.onAction(KeyAction.START_STOP_VIDEO)        // resume at 16000
        // start shifts forward by the 10s pause: 1000 + (16000-6000) = 11000
        // so the timer at resume = t - startedAt = 16000 - 11000 = 5000ms = the 5s actually recorded.
        assertEquals(11000L, (c.state as CaptureState.RecordingVideo).startedAtMillis)
    }

    @Test
    fun two_pause_cycles_accumulate_so_timer_excludes_all_pauses() {
        var t = 1000L
        val c = CaptureCore(clock = { t }, newId = { "id" })
        c.onAction(KeyAction.START_STOP_VIDEO)                     // start t=1000
        t = 6000L                                                  // recorded 5s
        c.onAction(KeyAction.START_STOP_VIDEO); c.onVideoFinalized(StopReason.PAUSE) // pause1 @6000
        t = 16000L                                                 // paused 10s
        c.onAction(KeyAction.START_STOP_VIDEO)                     // resume1 @16000 → start = 1000+10000 = 11000
        t = 19000L                                                 // recorded 3s
        c.onAction(KeyAction.START_STOP_VIDEO); c.onVideoFinalized(StopReason.PAUSE) // pause2 @19000
        t = 39000L                                                 // paused 20s
        c.onAction(KeyAction.START_STOP_VIDEO)                     // resume2 @39000 → start = 11000+20000 = 31000
        // timer = t - start = 39000 - 31000 = 8000ms = the 5s + 3s actually recorded (both pauses excluded).
        assertEquals(31000L, (c.state as CaptureState.RecordingVideo).startedAtMillis)
    }

    @Test
    fun end_video_ignored_when_idle() {
        val c = core()
        assertTrue(c.onAction(KeyAction.END_VIDEO).isEmpty())
        assertTrue(c.state is CaptureState.Idle)
    }
}
