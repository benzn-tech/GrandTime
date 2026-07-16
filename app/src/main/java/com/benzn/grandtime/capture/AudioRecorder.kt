package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** Standalone recorder: WAV 16 kHz mono 16-bit PCM (the pipeline ingests .wav). Public
 *  interface unchanged (start/stop/isRecording) — also reused by SP-Ask's AskRecorder. */
class AudioRecorder(private val context: Context) {
    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var captureFailed = false
    private var worker: Thread? = null
    private var pcmTmp: File? = null
    private var target: File? = null

    val isRecording: Boolean get() = running

    @android.annotation.SuppressLint("MissingPermission") // caller (preflight) ensures RECORD_AUDIO
    fun start(file: File): Boolean = try {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(minBuf, sr * 2) // >= 1s
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
        // Assign before the init check so a failed AudioRecord is still released by cleanup().
        record = rec
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        val tmp = File(file.parentFile, file.nameWithoutExtension + ".pcm")
        target = file; pcmTmp = tmp; running = true; captureFailed = false
        rec.startRecording()
        worker = thread(name = "audio-pcm") {
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
        true
    } catch (e: Exception) {
        cleanup(); false
    }

    /** Stops capture and writes the WAV (header + PCM) to the target file.
     *  Returns false (and deletes the temp PCM) if the capture failed mid-recording. */
    fun stop(): Boolean = try {
        running = false
        worker?.join(2000)
        record?.apply { stop(); release() }
        record = null
        val tmp = pcmTmp; val out = target
        val ok = if (tmp != null && out != null) {
            AudioAssembly.finish(tmp, out, captureFailed)
        } else {
            false
        }
        pcmTmp = null; target = null; captureFailed = false
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
