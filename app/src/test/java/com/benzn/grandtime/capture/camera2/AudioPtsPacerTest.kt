package com.benzn.grandtime.capture.camera2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two failure modes this class exists to prevent, in order of severity:
 *
 * 1. **Backwards timestamps.** MediaMuxer treats a backwards audio timestamp as a malformed
 *    track and stops writing it, and `writeSampleData` in SegmentRecorder is wrapped in
 *    runCatching — so the video would come out with no audio at all, silently. That is worse
 *    than the bug being fixed, and it is reachable the moment a silence frame is stamped ahead
 *    of the clock and a live frame follows it.
 * 2. **A sparse track.** Wall-clock timestamps with a fixed-size payload advance the timeline
 *    faster than the samples, so the audio track claims more time than it carries. Measured at
 *    22% and 44% of the handover window on a real recording.
 */
class AudioPtsPacerTest {
    private val sr = 44_100
    private val frame = 1024
    /** Whole-sample span of [n] frames -- the same integer form the pacer uses. Computing the
     *  expectation as `n * 23219` instead would bake in the very truncation this class removes:
     *  it is 94us adrift by frame 99, which is how the first draft of these tests failed. */
    private fun spanUs(n: Long) = n * frame * 1_000_000L / sr
    /** One frame, for tolerance comparisons only. */
    private val frameUs = spanUs(1)

    private fun pacer() = AudioPtsPacer(sampleRate = sr, samplesPerFrame = frame)

    // --- the sparse-track bug ---

    /** The bug, stated as a test: N frames of silence must claim exactly N frames of time.
     *  Under the old fixed-sleep design the span grew with the loop's real cost instead. */
    @Test fun payload_sum_equals_the_pts_span() {
        val p = pacer()
        var now = 1_000_000L
        val first = p.silence(now).ptsUs
        var last = first
        repeat(99) {
            now += 33_000  // a slow iteration: 23ms sleep + two 10ms codec timeouts
            last = p.silence(now).ptsUs
        }
        assertEquals("99 frames of payload must claim 99 frames of time",
            spanUs(99), last - first)
    }

    @Test fun a_slow_loop_does_not_stretch_the_timeline() {
        val p = pacer()
        var now = 0L
        val first = p.silence(now).ptsUs
        var last = first
        repeat(9) { now += 500_000; last = p.silence(now).ptsUs }  // absurdly slow
        assertEquals(spanUs(9), last - first)
    }

    // --- monotonicity: the reason the first design was rejected ---

    @Test fun pts_strictly_increases_across_silence_then_live_then_silence() {
        val p = pacer()
        val seen = mutableListOf<Long>()
        var now = 5_000_000L
        repeat(3) { seen.add(p.silence(now).ptsUs); now += 23_000 }
        seen.add(p.live(now)); p.onLiveAudio(); now += 23_000
        seen.add(p.live(now)); p.onLiveAudio(); now += 23_000
        repeat(3) { seen.add(p.silence(now).ptsUs); now += 23_000 }
        for (i in 1 until seen.size) {
            assertTrue("pts went backwards at $i: ${seen[i - 1]} -> ${seen[i]}",
                seen[i] > seen[i - 1])
        }
    }

    /**
     * The exact sequence that would have killed the track: the loop runs faster than real time,
     * so the cursor is ahead of the clock, and then the mic comes back. Without the clamp the
     * live frame is stamped with a wall clock EARLIER than the silence frame before it.
     */
    @Test fun a_live_frame_after_a_future_stamped_silence_frame_is_still_greater() {
        val p = pacer()
        var now = 1_000_000L
        var lastSilence = 0L
        repeat(5) { lastSilence = p.silence(now).ptsUs; now += 1_000 }  // clock barely moves
        val live = p.live(now)
        assertTrue("live pts $live must exceed the last silence pts $lastSilence",
            live > lastSilence)
    }

    /** Same hazard on a single frame: read() <= 0 drops one silence frame between two live
     *  reads, so the cursor is seeded and dropped every iteration. */
    @Test fun an_interleaved_silence_frame_stays_monotonic() {
        val p = pacer()
        var now = 2_000_000L
        val a = p.live(now); p.onLiveAudio()
        val b = p.silence(now).ptsUs          // same instant: the clock has not moved
        val c = p.live(now); p.onLiveAudio()
        assertTrue("$a -> $b", b > a)
        assertTrue("$b -> $c", c > b)
    }

    @Test fun eos_is_clamped_like_every_other_frame() {
        val p = pacer()
        var now = 0L
        repeat(4) { p.silence(now).ptsUs; now += 1_000 }
        val last = p.silence(now).ptsUs
        assertTrue(p.eos(now) > last)
    }

    @Test fun a_clock_that_jumps_backwards_cannot_move_pts_backwards() {
        val p = pacer()
        val a = p.live(9_000_000L); p.onLiveAudio()
        val b = p.live(1_000_000L)  // NTP step, or a caller passing the wrong clock
        assertTrue(b > a)
    }

    // --- pacing ---

    @Test fun no_sleep_is_requested_while_behind_the_clock() {
        val p = pacer()
        var now = 0L
        p.silence(now)
        now += 200_000  // the loop fell far behind
        assertEquals(0L, p.silence(now).sleepMs)
    }

    /** And when it is ahead, it waits — an unpaced fill floods the encoder with silence for the
     *  whole handover. */
    @Test fun being_ahead_of_the_clock_requests_a_sleep() {
        val p = pacer()
        val now = 0L
        p.silence(now)
        assertTrue("the next frame is one frame in the future", p.silence(now).sleepMs > 0)
    }

    @Test fun catching_up_never_stamps_ahead_of_the_clock() {
        val p = pacer()
        var now = 0L
        p.silence(now)
        now += 300_000
        // Frames emitted back-to-back to catch up must still land at or before the clock.
        repeat(10) { assertTrue(p.silence(now).ptsUs <= now) }
    }

    /** Integer sample math, not a truncated per-frame microsecond constant: 23219us is itself a
     *  truncation of 23219.95, and adding it 100k times drifts ~4 seconds. */
    @Test fun no_drift_over_a_hundred_thousand_frames() {
        val p = pacer()
        val frames = 100_000
        var now = 0L
        val first = p.silence(now).ptsUs
        var last = first
        repeat(frames - 1) { now += 25_000; last = p.silence(now).ptsUs }
        val expected = spanUs((frames - 1).toLong())
        assertEquals("integer sample math must not drift", expected, last - first)
    }

    @Test fun a_fill_that_restarts_reseeds_from_the_clock() {
        val p = pacer()
        p.silence(0L)
        p.onLiveAudio()
        val now = 10_000_000L
        // Re-seeded, so it tracks the clock again rather than continuing an old cursor.
        assertTrue(p.silence(now).ptsUs >= now - frameUs)
    }
}
