package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The start cue must finish before the microphone is live, and it must never be able to stop the
 * recording from starting.
 *
 * Source-level, because MediaPlayer and Camera2 do not exist on the JVM. What is pinned is the
 * ORDER and the escape hatch — the two things an edit could undo with no test failing and no symptom
 * until someone reads a transcript with a speaker who was never in the room.
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
    fun `an audio resume announces before the recorder restarts`() {
        // The old resume played "recording started" AFTER audio.start, so every resumed session
        // began with the device's own voice in it.
        val b = body("resumeAudio")
        val announce = b.indexOf("startRecordingAndAwait")
        val start = b.indexOf("audio.start(")
        assertTrue("no awaited announcement in the audio resume path", announce >= 0)
        assertTrue("the resume cue must come BEFORE audio.start", announce < start)
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
    fun `a video rollover never announces, only segment 1 and a resume do`() {
        // A rollover happens mid-meeting. Pausing the camera every segment to play a cue would
        // drop ~1.5s of real conversation, repeatedly. A resume re-opens a camera the pause
        // released; a rollover keeps it open, which is what tells them apart.
        val b = body("startVideoSegment").substringBefore("pipeline.startSegment(")
        var from = 0
        var calls = 0
        while (true) {
            val at = b.indexOf("startRecordingAndAwait", from)
            if (at < 0) break
            calls++
            val line = b.lastIndexOf("if (", at)
            val guard = b.substring(maxOf(line, 0), at)
            assertTrue(
                "every video announcement must be guarded by segment 1 or a closed camera: $guard",
                guard.contains("segmentIndex == 1") || guard.contains("cameraWasClosed"),
            )
            from = at + 1
        }
        assertEquals("expected the start and the resume announcements", 2, calls)
    }

    @Test
    fun `the wait can always give up`() {
        // The whole point of the escape hatch: losing a session to save a second of noise is not a
        // trade worth making. A hung or missing player must not hold the recorder.
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
    fun `the timeout comfortably clears the longest start cue`() {
        // video_started.mp3 is 1.57s. A timeout near that would cut the cue off and put its tail
        // back into the recording — the defect, quieter.
        val m = Regex("ANNOUNCE_TIMEOUT_MS = ([0-9_]+)L").find(sounds)
        assertTrue("timeout constant not found", m != null)
        val ms = m!!.groupValues[1].replace("_", "").toLong()
        assertTrue("timeout $ms ms is too close to the 1.57s cue", ms >= 2_500)
    }

    @Test
    fun `the non-awaiting start is not used to announce a recording`() {
        // A fire-and-forget start in a capture path would reintroduce the overlap on that path
        // alone, which is the hardest version to notice.
        for (fn in listOf("startAudio", "resumeAudio", "startVideoSegment")) {
            assertEquals("$fn must not use a non-awaiting start", false, body(fn).contains("sounds.startRecording("))
        }
    }
}
