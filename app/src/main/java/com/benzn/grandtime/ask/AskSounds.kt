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
    private val thinkingTone = pool.load(context, R.raw.ask_thinking, 1)
    private val error = pool.load(context, R.raw.ask_error, 1)
    private val received = pool.load(context, R.raw.voice_received, 1)

    private var askStart: MediaPlayer? = null
    private var searching: MediaPlayer? = null

    fun listening() { pool.play(listening, 1f, 1f, 1, 0, 1f) }

    /**
     * Says out loud that the question landed, over the seconds the backend needs.
     *
     * A tone already said "heard you"; it could not say what was happening. The wait after the key
     * is released is 8-10 s of silence today (STT, then retrieval, then the model, then speech),
     * and silence is indistinguishable from the device having missed the press -- which is the
     * moment an operator asks it again and doubles their own wait.
     *
     * `ask_searching` is James at speed 1.10 through eleven_v3_conversational: the SAME voice,
     * model and speed the answer itself arrives in, so this reads as the product starting to
     * answer rather than as a second person interrupting.
     *
     * NOT awaited, unlike [askStartAndAwait] -- nothing downstream waits on it, the microphone is
     * already closed, and awaiting would add its own 2 s to the wait it exists to cover. The tone
     * remains the fallback: a missing or unplayable asset must still tell the operator something.
     */
    fun thinking() {
        val spoke = runCatching {
            releaseSearching()
            val p = MediaPlayer.create(appContext, R.raw.ask_searching) ?: return@runCatching false
            p.setOnCompletionListener { releaseSearching() }
            p.setOnErrorListener { _, _, _ -> releaseSearching(); true }
            searching = p
            p.start()
            true
        }.getOrDefault(false)
        if (!spoke) pool.play(thinkingTone, 1f, 1f, 1, 0, 1f)
    }

    /**
     * Cut the line short. The answer plays through [AskPlayer], a different player on a different
     * stream, so an answer that arrives while this is still speaking would play OVER it -- one
     * voice talking across itself. Every path that produces sound after [thinking] calls this
     * first, and calling it when nothing is playing is a no-op.
     */
    fun stopThinking() { releaseSearching() }
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

    private fun releaseSearching() {
        searching?.runCatching { stop() }
        searching?.release()
        searching = null
    }

    fun release() {
        releaseAskStart()
        releaseSearching()
        pool.release()
    }

    companion object {
        /** ask_agent is 1.33 s; this is a stuck-player guard, not a length limit. */
        const val ASK_START_TIMEOUT_MS = 3_000L
    }
}
