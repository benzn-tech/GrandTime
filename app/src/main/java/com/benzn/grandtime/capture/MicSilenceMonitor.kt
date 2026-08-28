package com.benzn.grandtime.capture

import java.util.Locale

/** What one capture session's microphone health looks like from the device side. */
data class MicSilenceSnapshot(
    /** Total seconds of exactly-zero samples seen this session. */
    val silentSecondsS: Int,
    /** Longest CONTIGUOUS zero run, spanning buffers, chunks and pause/resume. */
    val longestSilentRunS: Int,
    /** How many qualifying runs overlapped an Ask / Site-voice microphone borrow. */
    val silentRunsWithMicBorrowed: Int,
    /** Largest sample magnitude seen this session (0..32768). */
    val peakAmplitude: Int,
    /** Seconds of audio actually read this session. Without it a snapshot cannot be read:
     *  "0 silent seconds" means the same thing for a healthy hour and for a session that
     *  never captured a buffer. */
    val recordedSecondsS: Int,
)

/**
 * Counts digital silence in the main capture stream.
 *
 * Why this exists: when the ROM refuses the capture, `AudioRecord.read()` does not return an
 * error code — it returns a POSITIVE length and a buffer full of zeros. `AudioRecorder`'s
 * `n > 0 -> write` treats that as good data, so the app produces a well-formed WAV of digital
 * silence and no layer reports a problem. Server-side it lands as `no_speech_dropped`, which is
 * indistinguishable from "nobody was talking". This makes the fault visible.
 *
 * Three deliberate choices, each of which the measured incident would defeat otherwise:
 *
 * - **Longest contiguous run**, not a per-buffer or per-chunk boolean. The one measured fault
 *   chunk is 97.6% zeros with 0.72s of live audio at the end — a boolean test calls it "not
 *   silent" and the detector misses the onset chunk of every fault.
 * - **Peak amplitude too.** Exact zero is only the one fault class observed; a microphone dying
 *   to a DC offset or to LSB dither reads as a quiet room to a zero test. Peak costs nothing.
 * - **Counting only.** Nothing here stops or fails the recording. Flipping the recorder's
 *   `captureFailed` would end the capture, turning "we lost a minute" into "we lost the meeting".
 *
 * Runs during an Ask / Site-voice microphone borrow are **annotated, not suppressed** — if
 * concurrent capture really is safe on this ROM, the annotated count simply stays zero, which
 * settles a contradiction the repo currently states both ways.
 *
 * Threading: [onPcm] is called only from the AudioRecorder worker thread; the counters are
 * `@Volatile` so [snapshot] can be read from anywhere (the uplink worker) without a lock.
 */
class MicSilenceMonitor(
    private val sampleRate: Int = 16_000,
    /** Dedicated mic-held flag — NOT `AppState.askActive`, which stays true through the whole
     *  thinking + playback window while the microphone was released at the top of `sendClip()`.
     *  Reading it here would blind the detector for tens of seconds. */
    private val micBorrowed: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
    /**
     * Loudest sample in the window just ended, as 0f..1f, about ten times a second.
     *
     * The recording screen's meter is driven from here rather than from its own animation because
     * this loop is already looking at every sample, and because a meter that moves without the
     * samples moving would hide exactly the fault this class exists to catch. Called on the
     * AudioRecorder worker thread — keep the callback cheap and thread-safe.
     */
    private val onLevel: (Float) -> Unit = {},
) {
    /** ~100ms of audio: fast enough to look live, slow enough to not flood the UI thread. */
    private val levelIntervalSamples: Long = sampleRate / 10L
    /** A run must reach this long to be logged or annotated. Gaps between words are normal. */
    private val thresholdSamples: Long = sampleRate.toLong()

    @Volatile private var totalSamples = 0L
    @Volatile private var totalZeroSamples = 0L
    @Volatile private var longestRunSamples = 0L
    @Volatile private var borrowedRuns = 0
    @Volatile private var peak = 0

    // Worker-thread only.
    private var runSamples = 0L
    private var runBorrowed = false
    private var runLogged = false
    private var runAnnotated = false
    private var runStartChunk = 0
    private var runStartOffsetSamples = 0L
    private var currentChunk = Int.MIN_VALUE
    private var chunkSamples = 0L
    private var levelPeak = 0
    private var levelSamples = 0L

    /**
     * Feed one `AudioRecord.read()` result. [n] is the byte count read() returned — only the
     * first [n] bytes are valid; the rest of the buffer is whatever the previous read left.
     */
    fun onPcm(buf: ByteArray, n: Int, chunkIndex: Int) {
        if (chunkIndex != currentChunk) {
            currentChunk = chunkIndex
            chunkSamples = 0L
        }
        // A 16-bit sample is two bytes. A read ending mid-sample must not shift every
        // subsequent pairing by one byte, which would turn live audio into zeros.
        val samples = minOf(n, buf.size) / 2
        val borrowed = micBorrowed()
        for (i in 0 until samples) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt()
            val value = (hi shl 8) or lo  // hi keeps its sign, so this sign-extends
            val magnitude = if (value < 0) -value else value
            // The meter's window. Deliberately a peak over the window and not an average: a level
            // that is zero here is DIGITALLY zero, which is the fault signature, and averaging
            // would smear the onset of one back into the room noise before it.
            if (magnitude > levelPeak) levelPeak = magnitude
            if (++levelSamples >= levelIntervalSamples) {
                onLevel(levelPeak.toFloat() / MAX_MAGNITUDE)
                levelPeak = 0
                levelSamples = 0L
            }
            if (value == 0) {
                if (runSamples == 0L) {
                    runStartChunk = currentChunk
                    runStartOffsetSamples = chunkSamples
                }
                runSamples++
                totalZeroSamples++
                if (borrowed) runBorrowed = true
                if (runSamples > longestRunSamples) longestRunSamples = runSamples
                if (runSamples >= thresholdSamples) {
                    if (!runLogged) {
                        runLogged = true
                        log(
                            String.format(
                                Locale.US,
                                "mic silence: chunk %d, +%.1fs into it, run reached %.1fs",
                                runStartChunk,
                                runStartOffsetSamples.toDouble() / sampleRate,
                                runSamples.toDouble() / sampleRate,
                            )
                        )
                    }
                    // Checked on every sample, not only at the crossing: a borrow that begins
                    // partway through an already-qualifying run still means the microphone was
                    // handed over during those zeros, and that is what the annotation records.
                    if (runBorrowed && !runAnnotated) {
                        runAnnotated = true
                        borrowedRuns++
                    }
                }
            } else {
                runSamples = 0L
                runBorrowed = false
                runLogged = false
                runAnnotated = false
                if (magnitude > peak) peak = magnitude
            }
            chunkSamples++
            totalSamples++
        }
    }

    fun snapshot() = MicSilenceSnapshot(
        silentSecondsS = (totalZeroSamples / sampleRate).toInt(),
        longestSilentRunS = (longestRunSamples / sampleRate).toInt(),
        silentRunsWithMicBorrowed = borrowedRuns,
        peakAmplitude = peak,
        recordedSecondsS = (totalSamples / sampleRate).toInt(),
    )

    private companion object {
        /** Full scale for signed 16-bit PCM. */
        const val MAX_MAGNITUDE = 32_768f
    }
}
