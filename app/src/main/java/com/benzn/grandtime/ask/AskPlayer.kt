package com.benzn.grandtime.ask

import android.media.MediaPlayer
import java.io.File

/**
 * One-shot player for the returned TTS answer audio. Writes the bytes to a temp
 * file and plays via MediaPlayer (which decodes wav or mp3 — see backend TTS
 * container note). Not unit-tested (Android framework); device-verified Task 14.
 */
class AskPlayer(private val cacheDir: File) {
    private var player: MediaPlayer? = null

    fun play(wavBytes: ByteArray, onDone: () -> Unit = {}) {
        release()
        cacheDir.mkdirs()
        val file = File(cacheDir, "ask_answer.wav").apply { writeBytes(wavBytes) }
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                onDone()
                release()
                player = null
            }
            setOnErrorListener { _, _, _ ->
                onDone()
                true
            }
            prepare()
            start()
        }
    }

    fun release() {
        player?.runCatching { release() }
        player = null
    }
}
