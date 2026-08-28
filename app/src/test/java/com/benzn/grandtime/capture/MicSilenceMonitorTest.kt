package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fault this exists to catch: AudioRecord.read() returns a POSITIVE length and a buffer
 * full of zeros when the capture is refused, so the recorder writes a well-formed WAV full of
 * digital silence and nothing anywhere reports a problem. These tests describe what "seeing
 * it" means, using the shape of the one real incident that has been measured.
 */
class MicSilenceMonitorTest {
    private val sr = 16000

    /** [samples] 16-bit little-endian samples, all of [value]. */
    private fun pcm(samples: Int, value: Short = 0): ByteArray {
        val b = ByteArray(samples * 2)
        for (i in 0 until samples) {
            b[i * 2] = (value.toInt() and 0xFF).toByte()
            b[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun monitor(micBorrowed: () -> Boolean = { false }, log: (String) -> Unit = {}) =
        MicSilenceMonitor(sampleRate = sr, micBorrowed = micBorrowed, log = log)

    @Test fun clean_audio_reports_no_silence() {
        val m = monitor()
        repeat(5) { m.onPcm(pcm(sr, 8000), sr * 2, chunkIndex = 1) }
        val s = m.snapshot()
        assertEquals(0, s.longestSilentRunS)
        assertEquals(0, s.silentSecondsS)
        assertEquals(8000, s.peakAmplitude)
    }

    /** A zero run does not respect buffer boundaries; the fault ran for 29.3s across many
     *  reads. Measuring per buffer would report 1s runs and read as a quiet room. */
    @Test fun zero_run_spans_buffers() {
        val m = monitor()
        repeat(10) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        assertEquals(10, m.snapshot().longestSilentRunS)
    }

    /** And it does not respect chunk boundaries either — the run continues across a segment
     *  roll, which is where the real fault started (97.6% of one chunk, then the next). */
    @Test fun zero_run_spans_chunk_rolls() {
        val m = monitor()
        repeat(4) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        repeat(4) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 2) }
        assertEquals(8, m.snapshot().longestSilentRunS)
    }

    /** The onset chunk of the real fault was 97.6% zeros with 0.72s of live audio at the end.
     *  A whole-buffer or whole-chunk boolean returns "not silent" for it and the detector
     *  misses the beginning of every fault. */
    @Test fun live_tail_after_a_long_run_does_not_erase_the_run() {
        val m = monitor()
        repeat(29) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 3) }
        m.onPcm(pcm(sr, 12000), sr * 2, chunkIndex = 3)
        val s = m.snapshot()
        assertEquals(29, s.longestSilentRunS)
        assertEquals(12000, s.peakAmplitude)
    }

    @Test fun longest_run_is_the_longest_not_the_last() {
        val m = monitor()
        repeat(9) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        m.onPcm(pcm(sr, 500), sr * 2, chunkIndex = 1)
        repeat(3) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        val s = m.snapshot()
        assertEquals(9, s.longestSilentRunS)
        assertEquals(12, s.silentSecondsS)  // total zeros, both runs
    }

    /** A run interrupted by a single non-zero sample is two runs. Real capture noise never
     *  produces exact zeros for a full second, so this is the honest split. */
    @Test fun one_live_sample_breaks_the_run() {
        val m = monitor()
        m.onPcm(pcm(sr), sr * 2, chunkIndex = 1)
        val mixed = pcm(sr)
        mixed[0] = 1
        m.onPcm(mixed, mixed.size, chunkIndex = 1)
        // 1s, then 1 live sample, then just under 1s more — neither side reaches 2s.
        assertEquals(1, m.snapshot().longestSilentRunS)
    }

    /** Ask and Site-voice legitimately take the microphone. Runs during a borrow are counted
     *  and ANNOTATED, never suppressed: if concurrent capture is really safe on this ROM the
     *  annotated count stays zero, which is how the contradiction in the spec gets settled. */
    @Test fun runs_during_a_mic_borrow_are_counted_and_annotated() {
        var borrowed = false
        val m = monitor(micBorrowed = { borrowed })
        borrowed = true
        repeat(3) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        borrowed = false
        m.onPcm(pcm(sr, 900), sr * 2, chunkIndex = 1)
        val s = m.snapshot()
        assertEquals(3, s.longestSilentRunS)
        assertEquals(1, s.silentRunsWithMicBorrowed)
    }

    @Test fun a_run_with_no_borrow_is_not_annotated() {
        val m = monitor(micBorrowed = { false })
        repeat(3) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        m.onPcm(pcm(sr, 900), sr * 2, chunkIndex = 1)
        assertEquals(0, m.snapshot().silentRunsWithMicBorrowed)
    }

    /** A borrow that starts partway through a run still marks it — the microphone was handed
     *  over during those zeros, which is the whole point of the annotation. */
    @Test fun borrow_beginning_mid_run_annotates_that_run() {
        var borrowed = false
        val m = monitor(micBorrowed = { borrowed })
        m.onPcm(pcm(sr), sr * 2, chunkIndex = 1)
        borrowed = true
        m.onPcm(pcm(sr), sr * 2, chunkIndex = 1)
        borrowed = false
        m.onPcm(pcm(sr, 900), sr * 2, chunkIndex = 1)
        assertEquals(1, m.snapshot().silentRunsWithMicBorrowed)
    }

    /** Short gaps are normal — a pause between words, the buffer boundary of a real room.
     *  Only runs at or past the threshold are worth an annotation slot. */
    @Test fun runs_below_the_threshold_are_not_annotated() {
        var borrowed = true
        val m = monitor(micBorrowed = { borrowed })
        m.onPcm(pcm(sr / 4), sr / 2, chunkIndex = 1)  // 0.25s of zeros
        borrowed = false
        m.onPcm(pcm(sr, 900), sr * 2, chunkIndex = 1)
        assertEquals(0, m.snapshot().silentRunsWithMicBorrowed)
    }

    /** The uplink is hours-scale and last-write-wins; the local line is the only timely
     *  artifact, so it has to say WHERE — chunk index and offset within that chunk. */
    @Test fun crossing_the_threshold_logs_once_with_chunk_and_offset() {
        val lines = mutableListOf<String>()
        val m = monitor(log = { lines.add(it) })
        m.onPcm(pcm(sr / 2, 700), sr, chunkIndex = 7)   // 0.5s of live audio first
        repeat(4) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 7) }
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("chunk 7"))
        assertTrue(lines[0], lines[0].contains("0.5"))  // offset into the chunk, seconds
    }

    @Test fun a_second_run_logs_again() {
        val lines = mutableListOf<String>()
        val m = monitor(log = { lines.add(it) })
        repeat(2) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        m.onPcm(pcm(sr, 700), sr * 2, chunkIndex = 1)
        repeat(2) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        assertEquals(2, lines.size)
    }

    /** The offset is into the CHUNK, not the session: that is what makes the line usable
     *  against a file on the device or in the bucket. */
    @Test fun offset_is_relative_to_the_current_chunk() {
        val lines = mutableListOf<String>()
        val m = monitor(log = { lines.add(it) })
        repeat(10) { m.onPcm(pcm(sr, 700), sr * 2, chunkIndex = 1) }
        m.onPcm(pcm(sr / 2, 700), sr, chunkIndex = 2)
        repeat(2) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 2) }
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("chunk 2"))
        assertTrue(lines[0], lines[0].contains("0.5"))
    }

    /** Peak matters because exact zero is only the ONE fault class we have measured. A mic
     *  dying to a DC offset or LSB dither reads as a quiet room to a zero test. */
    @Test fun peak_tracks_the_largest_magnitude_including_negative() {
        val m = monitor()
        m.onPcm(pcm(sr, -20000), sr * 2, chunkIndex = 1)
        assertEquals(20000, m.snapshot().peakAmplitude)
    }

    /** Short.MIN_VALUE negated overflows a Short. Left unguarded this reports a NEGATIVE
     *  peak, which would read as "quieter than silence" everywhere downstream. */
    @Test fun peak_survives_the_most_negative_sample() {
        val m = monitor()
        m.onPcm(pcm(4, Short.MIN_VALUE), 8, chunkIndex = 1)
        assertTrue(m.snapshot().peakAmplitude > 0)
    }

    /** Only the first n bytes of the buffer are valid; the tail is whatever the previous read
     *  left there. Counting it would invent zeros that AudioRecord never delivered. */
    @Test fun only_the_first_n_bytes_are_read() {
        val m = monitor()
        val b = pcm(sr, 900)
        java.util.Arrays.fill(b, sr, b.size, 0.toByte())  // second half is stale
        m.onPcm(b, sr, chunkIndex = 1)                    // ...and not handed to us
        assertEquals(0, m.snapshot().silentSecondsS)
    }

    /** A 16-bit sample is two bytes; a read that ends mid-sample must not shift every
     *  subsequent pairing by one byte and turn live audio into zeros. */
    @Test fun an_odd_length_read_does_not_desync_the_samples() {
        val m = monitor()
        m.onPcm(pcm(sr, 900), sr * 2 - 1, chunkIndex = 1)
        m.onPcm(pcm(sr, 900), sr * 2, chunkIndex = 1)
        assertEquals(0, m.snapshot().silentSecondsS)
        assertEquals(900, m.snapshot().peakAmplitude)
    }

    /** Pause/resume stops and restarts the AudioRecorder inside one session. The counters
     *  live above it precisely so a pause cannot erase a fault that already happened. */
    @Test fun counters_survive_across_a_pause_and_resume() {
        val m = monitor()
        repeat(6) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        // (pause: AudioRecorder stops; the monitor is untouched and reused on resume)
        m.onPcm(pcm(sr, 900), sr * 2, chunkIndex = 4)
        assertEquals(6, m.snapshot().longestSilentRunS)
    }

    /** An in-flight run is visible before it ends. A fault that is still happening is exactly
     *  the one worth reporting, and waiting for live audio to close the run would mean a
     *  permanently dead microphone reports nothing at all. */
    @Test fun an_unfinished_run_is_already_in_the_snapshot() {
        val m = monitor()
        repeat(7) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        assertEquals(7, m.snapshot().longestSilentRunS)
        assertEquals(7, m.snapshot().silentSecondsS)
    }

    // ---- the live level that drives the recording screen's meter -------------
    //
    // The meter exists to answer "is this still recording?" in a two-second glance. It is fed
    // from HERE, off the real samples, rather than animated on its own -- because the fault this
    // whole class is about is a capture that looks healthy from every other angle. A meter that
    // moved while the samples were zeros would hide it behind a reassuring animation.

    private fun levels(block: (MicSilenceMonitor) -> Unit): List<Float> {
        val out = mutableListOf<Float>()
        block(MicSilenceMonitor(sampleRate = sr, onLevel = { out.add(it) }))
        return out
    }

    @Test fun the_level_reports_about_ten_times_a_second() {
        // One second of audio, so the meter updates often enough to look alive without
        // flooding the UI with a callback per buffer.
        val out = levels { it.onPcm(pcm(sr, 8000), sr * 2, chunkIndex = 1) }
        assertEquals(10, out.size)
    }

    @Test fun the_level_tracks_the_loudest_sample_in_its_window() {
        val out = levels { it.onPcm(pcm(sr, 16384), sr * 2, chunkIndex = 1) }
        assertEquals(0.5f, out.first(), 0.01f)
    }

    @Test fun digital_silence_reports_exactly_zero() {
        // The whole point. Zero here is what puts "No sound reaching the microphone" on screen,
        // so it must be reachable and must not be smeared into a small positive number.
        val out = levels { it.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it == 0f })
    }

    @Test fun a_quiet_room_is_not_silence() {
        // A peak, not an average, for exactly this: room tone is small but nonzero, and a
        // warning that fired during a pause in the conversation would train people to ignore it.
        val out = levels { it.onPcm(pcm(sr, 40), sr * 2, chunkIndex = 1) }
        assertTrue(out.all { it > 0f })
    }

    @Test fun the_level_does_not_disturb_the_silence_counters() {
        // The counters are the shipped fault detector; the meter is a passenger on the same loop.
        val m = MicSilenceMonitor(sampleRate = sr, onLevel = {})
        repeat(3) { m.onPcm(pcm(sr), sr * 2, chunkIndex = 1) }
        m.onPcm(pcm(sr, 900), sr * 2, chunkIndex = 2)
        val s = m.snapshot()
        assertEquals(3, s.longestSilentRunS)
        assertEquals(3, s.silentSecondsS)
        assertEquals(900, s.peakAmplitude)
        assertEquals(4, s.recordedSecondsS)
    }
}
