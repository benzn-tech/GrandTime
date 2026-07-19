package com.benzn.grandtime.ask

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.benzn.grandtime.R

/**
 * Plays the bundled ASK cues (listening / thinking / error / received) from
 * res/raw assets committed in the APK — NOT downloaded (spec §9). Distinct from
 * [com.benzn.grandtime.capture.CaptureSounds], which plays fixed system tones.
 */
class AskSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val listening = pool.load(context, R.raw.ask_listening, 1)
    private val thinking = pool.load(context, R.raw.ask_thinking, 1)
    private val error = pool.load(context, R.raw.ask_error, 1)
    private val received = pool.load(context, R.raw.voice_received, 1)

    fun listening() { pool.play(listening, 1f, 1f, 1, 0, 1f) }
    fun thinking() { pool.play(thinking, 1f, 1f, 1, 0, 1f) }
    fun error() { pool.play(error, 1f, 1f, 1, 0, 1f) }
    fun received() { pool.play(received, 1f, 1f, 1, 0, 1f) }
    fun release() = pool.release()
}
