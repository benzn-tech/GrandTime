package com.benzn.grandtime.capture

import com.benzn.grandtime.keymap.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session that runs out of disk ends at a segment boundary and says why, instead of writing
 * the partition to zero and taking the app's databases -- and its ability to start -- with it.
 */
class CaptureCoreStorageTest {

    private fun core() = CaptureCore(clock = { 1000L }, newId = { "s" })

    @Test
    fun `a segment boundary with room rolls over as before`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(StopReason.ROLLOVER)), c.onSegmentTimerFired(canContinue = true))
    }

    @Test
    fun `the default is the old behaviour, so existing callers are unchanged`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(StopReason.ROLLOVER)), c.onSegmentTimerFired())
    }

    @Test
    fun `a segment boundary without room ends the session instead of rolling`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(
            listOf<CaptureCommand>(CaptureCommand.StopVideo(StopReason.STORAGE_FULL)),
            c.onSegmentTimerFired(canContinue = false),
        )
    }

    @Test
    fun `a storage stop finishes idle, with the refused vibration and the reason in words`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val cmds = c.onVideoFinalized(StopReason.STORAGE_FULL)
        assertEquals(CaptureState.Idle, c.state)
        assertTrue(cmds.contains(CaptureCommand.Vibrate(2)))
        assertTrue(cmds.contains(CaptureCommand.Notify(STORAGE_FULL_TEXT)))
        assertTrue("never the words of a deliberate stop", cmds.none { it == CaptureCommand.Notify("Standing by") })
    }

    @Test
    fun `a storage stop starts no new segment`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertTrue(c.onVideoFinalized(StopReason.STORAGE_FULL).none { it is CaptureCommand.StartVideoSegment })
    }

    @Test
    fun `audio out of room ends now and says why`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_AUDIO)
        val cmds = c.onStorageExhausted()
        assertEquals(CaptureState.Idle, c.state)
        assertTrue(cmds.contains(CaptureCommand.EndAudio("s")))
        assertTrue(cmds.contains(CaptureCommand.Notify(STORAGE_FULL_TEXT)))
    }

    @Test
    fun `video out of room asks the pipeline to stop and leaves the state to the finalize`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(StopReason.STORAGE_FULL)), c.onStorageExhausted())
        assertTrue(c.state is CaptureState.RecordingVideo)
    }

    @Test
    fun `nothing happens when idle or paused - nothing is being written`() {
        val idle = core()
        assertTrue(idle.onStorageExhausted().isEmpty())

        val paused = core()
        paused.onAction(KeyAction.START_STOP_AUDIO)
        paused.onAction(KeyAction.START_STOP_AUDIO)
        assertTrue(paused.state is CaptureState.PausedAudio)
        assertTrue(paused.onStorageExhausted().isEmpty())
        assertTrue(paused.state is CaptureState.PausedAudio)
    }

    @Test
    fun `a second exhaustion after the audio ended does not end it twice`() {
        // The final segment that EndAudio flushes reports in again; it must find nothing to stop.
        val c = core()
        c.onAction(KeyAction.START_STOP_AUDIO)
        c.onStorageExhausted()
        assertTrue(c.onStorageExhausted().isEmpty())
    }
}
