package com.benzn.grandtime.ask

import android.content.Context
import com.benzn.grandtime.capture.AudioRecorder
import com.benzn.grandtime.capture.MicHold
import com.benzn.grandtime.capture.MicOwnership
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
    /** Raised while this recorder physically holds the microphone, so the main capture's
     *  silence detector can ANNOTATE (never suppress) zero runs that overlap a voice clip.
     *  Owned here rather than in the two managers because this class is the single place
     *  where the microphone is actually taken and given back — a flag set at the call sites
     *  would have four places to get wrong, and a stuck flag blinds the detector. */
    private val micHold: MicHold = MicOwnership,
) {
    constructor(context: Context, cacheDir: File) : this(cacheDir, AudioRecorderAdapter(context))

    private var current: File? = null
    /** Whether THIS recorder currently holds the mic — makes release idempotent, so a
     *  stop() following a discard() cannot decrement someone else's hold. */
    private var holding = false

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
        if (ok) {
            holding = true
            micHold.acquire()
        } else {
            runCatching { file.delete() }
            current = null
        }
        return ok
    }

    /** Stop and return the finished clip (null on failure; temp file is deleted on every failure branch). */
    fun stop(): File? {
        val ok = recorder.stop()
        releaseMic()
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
        releaseMic()
        current?.let { f -> runCatching { f.delete() } }
        current = null
    }

    private fun releaseMic() {
        if (holding) {
            holding = false
            micHold.release()
        }
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
