package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaActionSound
import android.media.MediaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 系统内置录制/快门提示音(spec §2.7)+ 口播确认。懒加载,主线程调用安全。
 *
 * Why speech on top of the tone: the device is worn on a chest harness with the
 * screen off, and the start and stop tones are hard to tell apart. Confusing
 * them is the most expensive mistake this product allows — you believe you are
 * recording and you are not. The tone stays because it is instantaneous; the
 * words say WHICH tone it was.
 *
 * It also makes the recording overt to everyone in earshot, which is worth
 * something on its own when the thing being recorded is other people talking.
 *
 * The lines are looked up by NAME at runtime, like the meeting prompt: drop
 * `recording_started.wav` / `recording_stopped.wav` into res/raw and they speak;
 * leave them out and the tone plays alone, exactly as before.
 */
class CaptureSounds(private val context: Context? = null) {
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
        load(MediaActionSound.SHUTTER_CLICK)
    }

    private var player: MediaPlayer? = null

    /**
     * @param speak false for a PAUSE. The tone is right there — something
     * happened — but the words "recording stopped" are not: the session is
     * still open and a resume continues it. Saying it would teach the user that
     * pause ends the recording, which is the opposite of true.
     */
    fun startRecording(speak: Boolean = true) {
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        if (speak) say("recording_started")
    }

    /**
     * Announce the start and DO NOT RETURN until the announcement has finished.
     *
     * The caller starts the recorder after this, so the tone and the words are
     * over before the microphone is live. Otherwise the device narrates into its
     * own recording: the transcriber hears "recording started" in a voice that
     * belongs to nobody in the room, and diarisation gains a speaker who is not
     * a person. The backend already strips that text, but only after paying to
     * transcribe it and after it has had its chance to skew the speaker labels.
     *
     * The cost is the ~1.4s of room audio this now skips. That audio was
     * previously captured underneath the announcement, so it was never usable
     * anyway.
     *
     * ALWAYS returns, and that matters more than the silence it buys. If
     * playback fails, hangs, or the file is missing, this gives up and lets the
     * recording start: losing a whole session to save a second of noise is not a
     * trade worth making.
     */
    suspend fun startRecordingAndAwait(speak: Boolean = true) {
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        if (!speak) {
            delay(TONE_TAIL_MS)
            return
        }
        withTimeoutOrNull(ANNOUNCE_TIMEOUT_MS) { sayAndAwait("recording_started") }
    }

    private suspend fun sayAndAwait(name: String) {
        val ctx = context ?: return
        val id = ctx.resources.getIdentifier(name, "raw", ctx.packageName)
        if (id == 0) {
            // Not bundled: the tone is the whole signal, so only wait it out.
            delay(TONE_TAIL_MS)
            return
        }
        suspendCancellableCoroutine<Unit> { cont ->
            val ok = runCatching {
                releasePlayer()
                val p = MediaPlayer.create(ctx, id) ?: return@runCatching false
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                p.setOnCompletionListener {
                    releasePlayer()
                    if (cont.isActive) cont.resume(Unit)
                }
                p.setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                player = p
                p.start()
                true
            }.getOrDefault(false)
            if (!ok && cont.isActive) cont.resume(Unit)
            cont.invokeOnCancellation { releasePlayer() }
        }
    }

    fun stopRecording(speak: Boolean = true) {
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        if (speak) say("recording_stopped")
    }

    fun shutter() = sound.play(MediaActionSound.SHUTTER_CLICK)

    private fun say(name: String) {
        val ctx = context ?: return
        val id = ctx.resources.getIdentifier(name, "raw", ctx.packageName)
        if (id == 0) return                     // not bundled — the tone is the whole signal
        runCatching {
            releasePlayer()
            val p = MediaPlayer.create(ctx, id) ?: return
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setOnCompletionListener { releasePlayer() }
            player = p
            p.start()
        }
    }

    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    companion object {
        /** Upper bound on waiting for the announcement. Comfortably clears the
         *  1.4s line; anything longer than this is a stuck player, not speech. */
        const val ANNOUNCE_TIMEOUT_MS = 3_000L
        /** The system tone is short; long enough that it is over, short enough
         *  that a silent build still starts recording promptly. */
        const val TONE_TAIL_MS = 350L
    }

    fun release() {
        releasePlayer()
        sound.release()
    }
}
