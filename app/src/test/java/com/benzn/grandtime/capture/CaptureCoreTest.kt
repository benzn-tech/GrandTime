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
    fun `video key while recording stops without roll then finalize returns to idle`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val stopCmds = c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(rollToNext = false)), stopCmds)
        val doneCmds = c.onVideoFinalized(rollToNext = false)
        assertEquals(CaptureState.Idle, c.state)
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
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(rollToNext = true)), c.onSegmentTimerFired())
        val rollCmds = c.onVideoFinalized(rollToNext = true)
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
}
