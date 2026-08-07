package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaActionSound
import android.media.MediaPlayer

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

    fun release() {
        releasePlayer()
        sound.release()
    }
}
