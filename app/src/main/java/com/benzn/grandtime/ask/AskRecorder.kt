package com.benzn.grandtime.ask

import android.content.Context
import com.benzn.grandtime.capture.AudioRecorder
import java.io.File

/**
 * Records one short ASK clip by reusing [AudioRecorder] (MediaRecorder AAC/M4A).
 * Writes to a temp file in [cacheDir]; the caller reads the bytes and deletes.
 * The ~15s cap is enforced by AskManager (timer) + AskCore, not here.
 *
 * The public constructor wraps the real [AudioRecorder]; the internal constructor injects
 * a [Recorder] so the failure-path temp-file bookkeeping is JVM-testable without a real
 * Android MediaRecorder.
 */
class AskRecorder internal constructor(
    private val cacheDir: File,
    private val recorder: Recorder,
) {
    constructor(context: Context, cacheDir: File) : this(cacheDir, AudioRecorderAdapter(context))

    private var current: File? = null

    val isRecording: Boolean get() = recorder.isRecording

    /**
     * Begin recording to a fresh temp file. Returns false (and leaves no stray temp file)
     * if already recording or if the MediaRecorder failed to start.
     */
    fun start(): Boolean {
        if (isRecording) return false
        val file = clipFile(cacheDir, System.currentTimeMillis())
        cacheDir.mkdirs()
        current = file
        val ok = recorder.start(file)
        if (!ok) {
            runCatching { file.delete() }
            current = null
        }
        return ok
    }

    /** Stop and return the finished clip (null on failure; temp file is deleted on every failure branch). */
    fun stop(): File? {
        val ok = recorder.stop()
        val file = current
        current = null
        return if (ok && file != null && file.exists() && file.length() > 0) {
            file
        } else {
            runCatching { file?.delete() }
            null
        }
    }

    /** Abort: stop and delete any partial file. */
    fun discard() {
        recorder.stop()
        current?.let { f -> runCatching { f.delete() } }
        current = null
    }

    /** Minimal recorder seam over [AudioRecorder] so failure paths are JVM-testable. */
    interface Recorder {
        val isRecording: Boolean
        fun start(file: File): Boolean
        fun stop(): Boolean
    }

    private class AudioRecorderAdapter(context: Context) : Recorder {
        private val delegate = AudioRecorder(context)
        override val isRecording: Boolean get() = delegate.isRecording
        override fun start(file: File): Boolean = delegate.start(file)
        override fun stop(): Boolean = delegate.stop()
    }

    companion object {
        fun clipFile(cacheDir: File, nowMillis: Long): File =
            File(cacheDir, "ask_$nowMillis.m4a")
    }
}
