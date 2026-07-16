package com.benzn.grandtime.ask

import android.content.Context
import com.benzn.grandtime.capture.AudioRecorder
import java.io.File

/**
 * Records one short ASK clip by reusing [AudioRecorder] (MediaRecorder AAC/M4A).
 * Writes to a temp file in [cacheDir]; the caller reads the bytes and deletes.
 * The ~15s cap is enforced by AskManager (timer) + AskCore, not here.
 */
class AskRecorder(
    context: Context,
    private val cacheDir: File,
) {
    private val recorder = AudioRecorder(context)
    private var current: File? = null

    val isRecording: Boolean get() = recorder.isRecording

    /** Begin recording to a fresh temp file. Returns false if MediaRecorder failed to start. */
    fun start(): Boolean {
        val file = clipFile(cacheDir, System.currentTimeMillis())
        cacheDir.mkdirs()
        current = file
        val ok = recorder.start(file)
        if (!ok) current = null
        return ok
    }

    /** Stop and return the finished clip (null on failure). */
    fun stop(): File? {
        val ok = recorder.stop()
        val file = current
        current = null
        return if (ok && file != null && file.exists() && file.length() > 0) file else null
    }

    /** Abort: stop and delete any partial file. */
    fun discard() {
        recorder.stop()
        current?.delete()
        current = null
    }

    companion object {
        fun clipFile(cacheDir: File, nowMillis: Long): File =
            File(cacheDir, "ask_$nowMillis.m4a")
    }
}
