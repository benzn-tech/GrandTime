package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** Standalone recorder: WAV 16 kHz mono 16-bit PCM (the pipeline ingests .wav). Public
 *  interface unchanged (start/stop/isRecording) — also reused by SP-Ask's AskRecorder.
 *
 *  Two modes, selected by [start]'s optional params (all default to the original single-file
 *  behavior, so AskRecorder's call site — `start(file)` — is untouched):
 *  - **Single-file** (`segmentBytes<=0`, the default): streams to one temp .pcm; `stop()`
 *    assembles the one target .wav. Exactly the pre-existing behavior.
 *  - **Segmented** (`segmentBytes>0`): the SAME worker thread rolls into a new segment file
 *    every `segmentBytes` of PCM without ever stopping the underlying AudioRecord — no mic gap
 *    across a roll. Each new segment is pre-seeded with the last `overlapBytes` of the previous
 *    segment's PCM (via [PcmRingBuffer]) so audio spanning a boundary is present in both files.
 *    Every finalized segment (including the last one, from `stop()`) is surfaced exactly once via
 *    `onSegment`, called ON the worker thread — callers must keep it fast and thread-safe.
 */
class AudioRecorder(private val context: Context) {
    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var captureFailed = false
    private var worker: Thread? = null
    private var pcmTmp: File? = null
    private var target: File? = null

    val isRecording: Boolean get() = running

    @android.annotation.SuppressLint("MissingPermission") // caller (preflight) ensures RECORD_AUDIO
    fun start(
        file: File,
        segmentBytes: Long = 0L,
        overlapBytes: Long = 0L,
        clockMs: () -> Long = { System.currentTimeMillis() },
        nextFile: (() -> File)? = null,
        onSegment: ((AudioSegment) -> Unit)? = null,
    ): Boolean = try {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(minBuf, sr * 2) // >= 1s
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
        // Assign before the init check so a failed AudioRecord is still released by cleanup().
        record = rec
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        val tmp = File(file.parentFile, file.nameWithoutExtension + ".pcm")
        target = file; pcmTmp = tmp; running = true; captureFailed = false
        segmented = segmentBytes > 0
        rec.startRecording()
        worker = if (segmented) {
            thread(name = "audio-pcm") {
                runSegmentedWorker(rec, buf, tmp, file, segmentBytes, overlapBytes, clockMs, nextFile, onSegment)
            }
        } else {
            thread(name = "audio-pcm") { runSingleFileWorker(rec, buf, tmp) }
        }
        true
    } catch (e: Exception) {
        cleanup(); false
    }

    /** Original single-file worker loop — unchanged. */
    private fun runSingleFileWorker(rec: AudioRecord, buf: Int, tmp: File) {
        try {
            tmp.outputStream().buffered().use { out ->
                val b = ByteArray(buf)
                while (running) {
                    val n = rec.read(b, 0, b.size)
                    when {
                        n > 0 -> out.write(b, 0, n) // data
                        n == 0 -> Unit // no data yet, keep polling
                        else -> { captureFailed = true; running = false } // negative = AudioRecord error code
                    }
                }
            }
        } catch (e: Throwable) {
            // Storage full / removed mid-write / OEM read() throwing an unchecked error:
            // fail closed instead of silently truncating. Superset of IOException by design —
            // scoped to this worker loop only, not a blanket catch-all elsewhere.
            captureFailed = true
            running = false
        }
    }

    /** Segmented worker loop: same read() loop as single-file, but rolls into a new segment file
     *  (without stopping [rec]) once the current segment's PCM byte count reaches [segmentBytes].
     *  Runs entirely on this worker thread — the current output stream, ring buffer, target file,
     *  and byte counters are all local to this call, so no cross-thread mutable state is touched
     *  except [running]/[captureFailed] (already @Volatile) and the caller-supplied callbacks. */
    private fun runSegmentedWorker(
        rec: AudioRecord,
        bufSize: Int,
        firstTmp: File,
        firstTarget: File,
        segmentBytes: Long,
        overlapBytes: Long,
        clockMs: () -> Long,
        nextFile: (() -> File)?,
        onSegment: ((AudioSegment) -> Unit)?,
    ) {
        val ring = PcmRingBuffer(overlapBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var curTmp = firstTmp
        var curTarget = firstTarget
        var index = 1
        var segStart = clockMs()
        var bytesInSegment = 0L
        var out = curTmp.outputStream().buffered()
        try {
            val b = ByteArray(bufSize)
            while (running) {
                val n = rec.read(b, 0, b.size)
                when {
                    n > 0 -> {
                        out.write(b, 0, n)
                        ring.append(b, n)
                        bytesInSegment += n
                        // Only roll when a roll target is actually supplied — otherwise (misuse,
                        // or plain single-segment use of the segmented API) keep accumulating into
                        // the one file rather than finalizing without anywhere to continue writing.
                        if (nextFile != null && bytesInSegment >= segmentBytes) {
                            out.flush(); out.close()
                            val finishedTarget = curTarget
                            val finishedIndex = index
                            val finishedStart = segStart
                            val ok = AudioAssembly.finish(curTmp, finishedTarget, captureFailed = false)
                            if (ok) {
                                onSegment?.invoke(
                                    AudioSegment(finishedTarget, finishedStart, clockMs(), finishedIndex)
                                )
                            }
                            val next = nextFile()
                            val nextTmp = File(next.parentFile, next.nameWithoutExtension + ".pcm")
                            val overlap = ring.snapshot()
                            out = nextTmp.outputStream().buffered()
                            if (overlap.isNotEmpty()) out.write(overlap)
                            curTmp = nextTmp
                            curTarget = next
                            index++
                            segStart = clockMs()
                            bytesInSegment = overlap.size.toLong()
                        }
                    }
                    n == 0 -> Unit
                    else -> { captureFailed = true; running = false }
                }
            }
        } catch (e: Throwable) {
            captureFailed = true
            running = false
        } finally {
            runCatching { out.close() }
        }
        // Finalize the last (in-flight) segment so stop() only has to release the AudioRecord —
        // pcmTmp/target reflect the CURRENT (possibly rolled) segment for stop()'s own finalize call.
        pcmTmp = curTmp
        target = curTarget
        lastSegmentStart = segStart
        lastSegmentIndex = index
        segmentOnFinish = onSegment
        segmentClock = clockMs
    }

    // Set by runSegmentedWorker just before it returns, so stop() can finalize + emit the final
    // segment with the right start-time/index/callback. Only ever written by the worker thread
    // and only ever read by stop() after worker.join(), so no additional synchronization needed.
    private var lastSegmentStart: Long = 0
    private var lastSegmentIndex: Int = 0
    private var segmentOnFinish: ((AudioSegment) -> Unit)? = null
    private var segmentClock: (() -> Long)? = null
    private var segmented: Boolean = false

    /** Stops capture and writes the WAV (header + PCM) to the target file.
     *  Single-file mode: returns false (and deletes the temp PCM) if capture failed mid-recording.
     *  Segmented mode: finalizes + emits the LAST in-flight segment via the onSegment callback
     *  supplied to [start] (every segment, including this one, is emitted exactly once); the
     *  return value reflects whether that final segment assembled cleanly. */
    fun stop(): Boolean = try {
        running = false
        // 2000ms was too tight in a pathological case: the worker's read() blocks for up to
        // ~1s, so a slow final iteration could let join() return before the worker wrote the
        // final-segment hand-off fields (pcmTmp/target/lastSegmentStart/...), silently dropping
        // the last segment. 3000ms stays safely above that ~1s bound.
        worker?.join(3000)
        record?.apply { stop(); release() }
        record = null
        val tmp = pcmTmp; val out = target
        val ok = if (tmp != null && out != null) {
            AudioAssembly.finish(tmp, out, captureFailed)
        } else {
            false
        }
        if (segmented && ok) {
            segmentOnFinish?.invoke(
                AudioSegment(out!!, lastSegmentStart, (segmentClock ?: { System.currentTimeMillis() })(), lastSegmentIndex)
            )
        }
        pcmTmp = null; target = null; captureFailed = false
        segmented = false; segmentOnFinish = null; segmentClock = null
        ok
    } catch (e: Exception) {
        cleanup(); false
    }

    private fun cleanup() {
        running = false
        runCatching { worker?.join(1000) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { pcmTmp?.delete() }
        pcmTmp = null; target = null; captureFailed = false
        segmented = false; segmentOnFinish = null; segmentClock = null
    }
}

/** Pure file-assembly decision, factored out of [AudioRecorder] so it is JVM-testable
 *  without a real AudioRecord: given the temp PCM file and whether capture failed,
 *  either build the target WAV (happy path) or fail closed and delete the temp. */
internal object AudioAssembly {
    fun finish(tmp: File, target: File, captureFailed: Boolean): Boolean {
        val ok = if (!captureFailed && tmp.exists()) {
            val pcmLen = tmp.length().toInt()
            target.outputStream().buffered().use { o ->
                o.write(WavHeader.riffWav(pcmLen))
                tmp.inputStream().buffered().use { it.copyTo(o) }
            }
            target.length() > 44
        } else {
            false
        }
        tmp.delete()
        return ok
    }
}
