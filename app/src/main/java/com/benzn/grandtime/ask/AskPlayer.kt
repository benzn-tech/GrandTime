package com.benzn.grandtime.ask

import android.media.MediaPlayer
import java.io.File

/**
 * One-shot player for the returned TTS answer audio. Writes the bytes to a temp
 * file and plays via MediaPlayer (which decodes wav or mp3 — see backend TTS
 * container note). Actual audio output is device-verified (Task 14); the
 * failure-path bookkeeping (release + temp-file delete + no-throw) is JVM-tested
 * via the injected [PlayerFactory] seam.
 *
 * The public constructor wraps the real [MediaPlayer]; the internal constructor injects
 * a [PlayerFactory] so the setup-failure teardown is testable without a real Android player.
 */
class AskPlayer internal constructor(
    private val cacheDir: File,
    private val playerFactory: PlayerFactory,
) {
    constructor(cacheDir: File) : this(cacheDir, RealPlayerFactory)

    private var player: Player? = null
    private var currentFile: File? = null

    /**
     * Play [wavBytes] once. Setup is synchronous ([Player.setDataSource]/[Player.prepare] can
     * throw on a malformed clip); on ANY setup failure this releases the constructed player,
     * deletes the temp file, and invokes [onDone] WITHOUT throwing — the caller (AskManager)
     * must not crash on a bad answer clip. [onDone] also fires on normal completion and on
     * async post-prepare playback errors, in each case after teardown.
     */
    fun play(wavBytes: ByteArray, onDone: () -> Unit = {}) {
        release()
        cacheDir.mkdirs()
        val file = File(cacheDir, TEMP_NAME).apply { writeBytes(wavBytes) }
        val p = playerFactory.create()
        try {
            p.setDataSource(file.absolutePath)
            p.setOnComplete { onDone(); teardown(p, file) }
            p.setOnError { onDone(); teardown(p, file) }
            p.prepare()
            p.start()
            player = p
            currentFile = file
        } catch (t: Throwable) {
            teardown(p, file)
            onDone()
        }
    }

    fun release() = teardown(player, currentFile)

    /** Release [p], delete [file], and clear the active refs if they still point at these. */
    private fun teardown(p: Player?, file: File?) {
        if (p != null) runCatching { p.release() }
        if (file != null) runCatching { file.delete() }
        if (player === p) player = null
        if (currentFile === file) currentFile = null
    }

    /** Minimal player seam over [MediaPlayer] so setup-failure teardown is JVM-testable. */
    interface Player {
        fun setDataSource(path: String)
        fun setOnComplete(callback: () -> Unit)
        fun setOnError(callback: () -> Unit)
        fun prepare()
        fun start()
        fun release()
    }

    interface PlayerFactory {
        fun create(): Player
    }

    private object RealPlayerFactory : PlayerFactory {
        override fun create(): Player = MediaPlayerAdapter()
    }

    private class MediaPlayerAdapter : Player {
        private val mp = MediaPlayer()
        override fun setDataSource(path: String) = mp.setDataSource(path)
        override fun setOnComplete(callback: () -> Unit) {
            mp.setOnCompletionListener { callback() }
        }
        override fun setOnError(callback: () -> Unit) {
            mp.setOnErrorListener { _, _, _ -> callback(); true }
        }
        override fun prepare() = mp.prepare()
        override fun start() = mp.start()
        override fun release() = mp.release()
    }

    companion object {
        const val TEMP_NAME = "ask_answer.wav"
    }
}
