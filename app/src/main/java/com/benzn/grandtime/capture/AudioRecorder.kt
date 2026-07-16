package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.concurrent.thread

/** Standalone recorder: WAV 16 kHz mono 16-bit PCM (the pipeline ingests .wav). Public
 *  interface unchanged (start/stop/isRecording) — also reused by SP-Ask's AskRecorder. */
class AudioRecorder(private val context: Context) {
    private var record: AudioRecord? = null
    @Volatile private var running = false
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
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        val tmp = File(file.parentFile, file.nameWithoutExtension + ".pcm")
        record = rec; target = file; pcmTmp = tmp; running = true
        rec.startRecording()
        worker = thread(name = "audio-pcm") {
            tmp.outputStream().buffered().use { out ->
                val b = ByteArray(buf)
                while (running) {
                    val n = rec.read(b, 0, b.size)
                    if (n > 0) out.write(b, 0, n)
                }
            }
        }
        true
    } catch (e: Exception) {
        cleanup(); false
    }

    /** Stops capture and writes the WAV (header + PCM) to the target file. */
    fun stop(): Boolean = try {
        running = false
        worker?.join(2000)
        record?.apply { stop(); release() }
        record = null
        val tmp = pcmTmp; val out = target
        var ok = false
        if (tmp != null && out != null && tmp.exists()) {
            val pcmLen = tmp.length().toInt()
            out.outputStream().buffered().use { o ->
                o.write(WavHeader.riffWav(pcmLen))
                tmp.inputStream().buffered().use { it.copyTo(o) }
            }
            ok = out.length() > 44
        }
        tmp?.delete()
        pcmTmp = null; target = null
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
        pcmTmp = null; target = null
    }
}
