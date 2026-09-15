package com.benzn.grandtime.ask

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.benzn.grandtime.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Plays the bundled ASK cues from res/raw assets committed in the APK — NOT downloaded (spec §9).
 *
 * Two kinds, deliberately:
 *  - short cues (listening / thinking / error / received) through a SoundPool, fire-and-forget.
 *    Site-voice still uses [listening] for talk-start: talk begins the moment the key is down, and a
 *    longer cue would eat the first words.
 *  - the Ask start cue, `ask_agent` (1.3 s, 2026-09-15), through [askStartAndAwait]. It is awaited
 *    because the microphone opens right after it; played over an open microphone it went to speech
 *    recognition as the start of the question.
 */
class AskSounds(context: Context) {
    private val appContext = context.applicationContext

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

    private var askStart: MediaPlayer? = null

    fun listening() { pool.play(listening, 1f, 1f, 1, 0, 1f) }
    fun thinking() { pool.play(thinking, 1f, 1f, 1, 0, 1f) }
    fun error() { pool.play(error, 1f, 1f, 1, 0, 1f) }
    fun received() { pool.play(received, 1f, 1f, 1, 0, 1f) }

    /**
     * Play `ask_agent` and return when it has finished. ALWAYS returns: a failed, missing or stuck
     * player gives up after [ASK_START_TIMEOUT_MS] so the ask still starts listening.
     */
    suspend fun askStartAndAwait() {
        withTimeoutOrNull(ASK_START_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val ok = runCatching {
                    releaseAskStart()
                    val p = MediaPlayer.create(appContext, R.raw.ask_agent) ?: return@runCatching false
                    p.setOnCompletionListener {
                        releaseAskStart()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    p.setOnErrorListener { _, _, _ ->
                        releaseAskStart()
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    askStart = p
                    p.start()
                    true
                }.getOrDefault(false)
                if (!ok && cont.isActive) cont.resume(Unit)
                cont.invokeOnCancellation { releaseAskStart() }
            }
        }
    }

    private fun releaseAskStart() {
        askStart?.runCatching { stop() }
        askStart?.release()
        askStart = null
    }

    fun release() {
        releaseAskStart()
        pool.release()
    }

    companion object {
        /** ask_agent is 1.33 s; this is a stuck-player guard, not a length limit. */
        const val ASK_START_TIMEOUT_MS = 3_000L
    }
}
