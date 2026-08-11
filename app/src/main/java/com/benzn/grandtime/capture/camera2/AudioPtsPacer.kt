package com.benzn.grandtime.capture.camera2

/** One paced silence frame: how long to wait, and the timestamp to use once that wait is over. */
data class Pacing(val ptsUs: Long, val sleepMs: Long)

/**
 * Decides the presentation timestamp of every audio frame the segment recorder queues, and how
 * long to wait before emitting a frame of silence.
 *
 * ## Why this is not just "sleep one frame duration"
 *
 * The recorder stamps buffers with the wall clock so audio shares the video's time base, but a
 * silence frame is a fixed 1024 samples — 23.22 ms of payload. The loop around it also pays
 * `dequeueInputBuffer(10_000)` and `dequeueOutputBuffer(10_000)`, so a real iteration costs far
 * more than the payload it carries. **The timeline advanced at wall-clock rate while the samples
 * advanced at a fixed rate, and the difference was a hole in the track**: measured at 22% and
 * 44% of the handover window on a real recording, which is A/V desync in evidence footage.
 *
 * So silence is paced against a cursor: the pts comes from a seed plus a frame count, and the
 * caller only sleeps while that cursor is *ahead* of the clock. Fall behind and frames are
 * emitted back-to-back until the payload covers the time it claims.
 *
 * ## The invariant that matters more than any of that
 *
 * **Timestamps never go backwards.** `MediaMuxer` treats a backwards audio timestamp as a
 * malformed track and stops writing it — and the write is wrapped in `runCatching`, so the
 * video would come out with no audio at all, silently. That is strictly worse than the sparse
 * track this class fixes, and it is one frame away: a silence frame stamped ahead of the clock
 * followed by a live frame stamped from the clock.
 *
 * Two mechanisms hold it: [silence] never returns a pts later than the clock it was given
 * (that is what [Pacing.sleepMs] is for — **sleep first, then stamp**), and every timestamp,
 * including EOS, is clamped above the last one issued.
 *
 * Not thread-safe: the recorder drives it from one audio thread.
 */
class AudioPtsPacer(
    private val sampleRate: Int,
    private val samplesPerFrame: Int,
) {
    private var seedUs = 0L
    private var frames = 0L
    private var filling = false
    private var lastPtsUs = Long.MIN_VALUE

    /** Timestamp for a frame of real captured audio. */
    fun live(nowUs: Long): Long = clamp(nowUs)

    /** Timestamp for the end-of-stream buffer. Clamped like everything else — it is stamped with
     *  a bare wall clock otherwise, which is enough to break the track on its own. */
    fun eos(nowUs: Long): Long = clamp(nowUs)

    /**
     * The next frame of silence: wait [Pacing.sleepMs], then queue with [Pacing.ptsUs].
     *
     * Emitting before sleeping would stamp the frame in the future and hand the next live frame
     * a timestamp it cannot beat.
     */
    fun silence(nowUs: Long): Pacing {
        if (!filling) {
            // Seed where real audio stopped, not at some arbitrary origin.
            filling = true
            seedUs = nowUs
            frames = 0
        }
        val target = seedUs + framesToUs(frames)
        frames++
        val aheadUs = target - nowUs
        // Whole milliseconds, rounded up: sleeping a fraction short would leave the frame
        // stamped in the future, which is the failure this class exists to prevent.
        val sleepMs = if (aheadUs > 0) (aheadUs + 999) / 1000 else 0
        return Pacing(ptsUs = clamp(target), sleepMs = sleepMs)
    }

    /** A real read succeeded, so the current fill is over and the next one re-seeds. */
    fun onLiveAudio() { filling = false }

    /**
     * Whole-sample arithmetic. Never accumulate a per-frame microsecond constant: 1024 samples
     * at 44.1 kHz is 23219.95 us, and adding the truncated 23219 a hundred thousand times drifts
     * by seconds — the same class of mistake as the bug being fixed.
     */
    private fun framesToUs(n: Long): Long = n * samplesPerFrame * 1_000_000L / sampleRate

    private fun clamp(candidateUs: Long): Long {
        val pts = if (lastPtsUs == Long.MIN_VALUE) candidateUs else maxOf(candidateUs, lastPtsUs + 1)
        lastPtsUs = pts
        return pts
    }
}
