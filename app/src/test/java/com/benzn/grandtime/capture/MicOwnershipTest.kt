package com.benzn.grandtime.capture

import com.benzn.grandtime.ask.AskRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The mic-held flag exists so [MicSilenceMonitor] can tell "the microphone was legitimately
 * lent to Ask / Site-voice" from "the microphone stopped producing audio". A flag that sticks
 * high blinds the detector; a flag that sticks low mis-attributes a borrow as a fault. Both
 * failure modes are cheap to introduce and invisible on a device, so they are pinned here.
 */
class MicOwnershipTest {

    private class Hold : MicHold {
        var count = 0
        override fun acquire() { count++ }
        override fun release() { count-- }
        override val heldByVoiceFeature: Boolean get() = count > 0
    }

    private class FakeRecorder(val startResult: Boolean = true) : AskRecorder.Recorder {
        private var recording = false
        override val isRecording: Boolean get() = recording
        override fun start(file: File): Boolean {
            file.parentFile?.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
            recording = startResult
            return startResult
        }
        override fun stop(): Boolean { recording = false; return true }
    }

    private fun cache() = File("build/tmp/mic-own-${UUID.randomUUID()}").apply { mkdirs() }

    @Before fun reset() = MicOwnership.resetForTest()

    @Test fun the_mic_is_held_between_start_and_stop() {
        val hold = Hold()
        val rec = AskRecorder(cache(), FakeRecorder(), hold)
        assertFalse(hold.heldByVoiceFeature)
        rec.start()
        assertTrue("held while a clip is recording", hold.heldByVoiceFeature)
        rec.stop()
        assertFalse("released as soon as the clip stops — NOT when playback ends", hold.heldByVoiceFeature)
    }

    @Test fun discard_releases_the_mic_too() {
        val hold = Hold()
        val rec = AskRecorder(cache(), FakeRecorder(), hold)
        rec.start()
        rec.discard()
        assertFalse(hold.heldByVoiceFeature)
    }

    /** A failed start never took the microphone, so it must not report holding it — otherwise
     *  every subsequent zero run gets excused as a borrow that never happened. */
    @Test fun a_failed_start_does_not_hold_the_mic() {
        val hold = Hold()
        val rec = AskRecorder(cache(), FakeRecorder(startResult = false), hold)
        assertFalse(rec.start())
        assertEquals(0, hold.count)
    }

    /** stop() after discard(), or two stops in a row, must not decrement twice — that would
     *  drive the count negative and swallow the next genuine acquire. */
    @Test fun releasing_twice_only_counts_once() {
        val hold = Hold()
        val rec = AskRecorder(cache(), FakeRecorder(), hold)
        rec.start()
        rec.discard()
        rec.stop()
        assertEquals(0, hold.count)
    }

    @Test fun the_shared_counter_floors_at_zero() {
        MicOwnership.release()
        MicOwnership.release()
        MicOwnership.acquire()
        assertTrue("a stray release must not swallow the next real acquire",
            MicOwnership.heldByVoiceFeature)
        MicOwnership.release()
        assertFalse(MicOwnership.heldByVoiceFeature)
    }

    @Test fun two_holders_both_have_to_let_go() {
        MicOwnership.acquire()
        MicOwnership.acquire()
        MicOwnership.release()
        assertTrue(MicOwnership.heldByVoiceFeature)
        MicOwnership.release()
        assertFalse(MicOwnership.heldByVoiceFeature)
    }

    /** The whole point of the dedicated flag: it tracks the PHYSICAL hold, so it is already
     *  down while Ask is still thinking and playing back. AppState.askActive is not. */
    @Test fun the_hold_ends_before_the_thinking_and_playback_window() {
        val hold = Hold()
        val rec = AskRecorder(cache(), FakeRecorder(), hold)
        rec.start()
        rec.stop()               // sendClip() reads the clip AFTER this point
        assertFalse("the API call and the answer playback are not a mic hold",
            hold.heldByVoiceFeature)
    }
}
