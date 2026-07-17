package com.benzn.grandtime.capture

import java.io.File

/** One finalized rolling-audio segment, surfaced by [AudioRecorder]'s segmented mode via
 *  `onSegment` — one call per completed segment, including the final one from `stop()`. */
data class AudioSegment(val file: File, val startedAtMs: Long, val endedAtMs: Long, val index: Int)

/** PCM byte budget for one rolling audio segment, matching the video segment-length setting
 *  (spec: minutes * 60s * sampleRate * bytesPerSample). 16 kHz mono 16-bit by default, matching
 *  [AudioRecorder]'s fixed capture format. */
fun segmentBytesFor(minutes: Int, sampleRate: Int = 16000, bytesPerSample: Int = 2): Long =
    minutes.toLong() * 60 * sampleRate * bytesPerSample

/** PCM byte length of the overlap tail carried into the next segment, capped so a
 *  misconfigured/huge `seconds` value can't blow up the ring buffer memory footprint. */
fun overlapBytesFor(seconds: Int, sampleRate: Int = 16000, bytesPerSample: Int = 2): Long {
    val cap = MAX_OVERLAP_SECONDS.toLong() * sampleRate * bytesPerSample
    val requested = seconds.toLong() * sampleRate * bytesPerSample
    return minOf(requested, cap)
}

private const val MAX_OVERLAP_SECONDS = 10

/** Fixed-capacity byte ring buffer retaining only the most recently appended `capacity` bytes,
 *  in order — used to carry the last ~2s of PCM from one audio segment into the next so a
 *  sentence crossing a segment boundary appears whole in both. Not thread-safe by design: it is
 *  only ever touched from AudioRecorder's single worker thread. */
class PcmRingBuffer(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var writePos = 0
    private var filled = 0

    /** Appends the first [len] bytes of [bytes]. If [len] exceeds [capacity], only the trailing
     *  `capacity` bytes of this call are kept (older bytes within the call are discarded, same as
     *  if they'd been appended and then immediately evicted). */
    fun append(bytes: ByteArray, len: Int) {
        if (capacity == 0 || len <= 0) return
        var offset = 0
        var remaining = len
        if (remaining > capacity) {
            offset = remaining - capacity
            remaining = capacity
        }
        val written = remaining
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - writePos)
            System.arraycopy(bytes, offset, buf, writePos, chunk)
            writePos = (writePos + chunk) % capacity
            offset += chunk
            remaining -= chunk
        }
        filled = minOf(capacity, filled + written)
    }

    /** Returns up to the last [capacity] bytes appended, oldest-first. */
    fun snapshot(): ByteArray {
        if (filled == 0) return ByteArray(0)
        val out = ByteArray(filled)
        if (filled < capacity) {
            System.arraycopy(buf, 0, out, 0, filled)
        } else {
            val firstPart = capacity - writePos
            System.arraycopy(buf, writePos, out, 0, firstPart)
            System.arraycopy(buf, 0, out, firstPart, writePos)
        }
        return out
    }
}
