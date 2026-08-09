package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The announcement must finish before the microphone is live, and it must never
 * be able to stop the recording from starting.
 *
 * Source-level, because MediaPlayer and Camera2 do not exist on the JVM. What is
 * pinned is the ORDER and the escape hatch — the two things an edit could undo
 * with no test failing and no symptom until someone reads a transcript with a
 * speaker who was never in the room.
 */
class AnnounceBeforeRecordTest {

    private val manager = File(
        "src/main/java/com/benzn/grandtime/capture/CaptureManager.kt"
    ).readText()
    private val sounds = File(
        "src/main/java/com/benzn/grandtime/capture/CaptureSounds.kt"
    ).readText()

    private fun body(fn: String): String {
        val start = manager.indexOf("private suspend fun $fn")
        assertTrue("$fn not found", start >= 0)
        return manager.substring(start, minOf(start + 3000, manager.length))
    }

    @Test
    fun `audio announces before the recorder starts`() {
        val b = body("startAudio")
        val announce = b.indexOf("startRecordingAndAwait")
        val start = b.indexOf("audio.start(")
        assertTrue("no awaited announcement in the audio path", announce >= 0)
        assertTrue(
            "the announcement must come BEFORE audio.start, or it is recorded",
            announce < start,
        )
    }

    @Test
    fun `video announces before the camera starts`() {
        val b = body("startVideoSegment")
        val announce = b.indexOf("startRecordingAndAwait")
        val start = b.indexOf("pipeline.startSegment(")
        assertTrue("no awaited announcement in the video path", announce >= 0)
        assertTrue(
            "the announcement must come BEFORE pipeline.startSegment",
            announce < start,
        )
    }

    @Test
    fun `only the first video segment announces`() {
        // A rollover happens mid-meeting. Pausing the camera every segment to
        // talk would drop ~1.4s of real conversation, repeatedly.
        val b = body("startVideoSegment")
        val announce = b.indexOf("startRecordingAndAwait")
        val guard = b.lastIndexOf("segmentIndex == 1", announce)
        assertTrue("the announcement must be guarded to segment 1", guard in 0 until announce)
    }

    @Test
    fun `the wait can always give up`() {
        // The whole point of the escape hatch: losing a session to save a second
        // of noise is not a trade worth making. A hung or missing player must
        // not hold the recorder.
        assertTrue("no timeout around the announcement", sounds.contains("withTimeoutOrNull"))
        assertTrue("no timeout constant", sounds.contains("ANNOUNCE_TIMEOUT_MS"))
    }

    @Test
    fun `a failed or missing player still lets recording start`() {
        // create() returning null, an onError, or a throw must all resume.
        assertTrue("must handle a null player", sounds.contains("?: return@runCatching false"))
        assertTrue("must handle a playback error", sounds.contains("setOnErrorListener"))
        assertTrue("must resume when setup failed", sounds.contains("if (!ok && cont.isActive)"))
    }

    @Test
    fun `the timeout comfortably clears the spoken line`() {
        // recording_started.wav is 1.38s. A timeout near that would cut the line
        // off and put its tail back into the recording — the defect, quieter.
        val m = Regex("ANNOUNCE_TIMEOUT_MS = ([0-9_]+)L").find(sounds)
        assertTrue("timeout constant not found", m != null)
        val ms = m!!.groupValues[1].replace("_", "").toLong()
        assertTrue("timeout $ms ms is too close to the 1.38s line", ms >= 2_500)
    }

    @Test
    fun `the non-awaiting start is no longer used to announce a recording`() {
        // Leaving the old call in a capture path would reintroduce the overlap
        // on that path alone, which is the hardest version to notice.
        val audio = body("startAudio")
        assertEquals(false, audio.contains("sounds.startRecording()"))
    }
}
