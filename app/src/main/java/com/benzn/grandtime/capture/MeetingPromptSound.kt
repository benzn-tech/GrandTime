package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Speaks the "has the meeting ended?" prompt.
 *
 * The voice line is resolved by NAME at runtime (`res/raw/meeting_prompt`)
 * rather than through the generated `R.raw` constant. That is deliberate: the
 * recording is a spoken Chinese line the operator has to produce, and a missing
 * `R.raw` reference would not compile — which would mean either blocking this
 * feature on an audio file, or committing a placeholder that ships to devices
 * and gets forgotten. Resolving by name lets the file be dropped in later with
 * no code change, and lets the build stay honest about not having it yet.
 *
 * Until it exists, the device vibrates instead. The prompt exists because the
 * device is back on a chest harness with the screen off, so the cue only has to
 * be noticeable, not intelligible — vibration degrades gracefully in a way
 * silence does not.
 */
class MeetingPromptSound(private val context: Context) {

    /** 0 when no voice line is bundled. */
    private val voiceResId: Int = context.resources.getIdentifier(
        "meeting_prompt", "raw", context.packageName,
    )

    val hasVoiceLine: Boolean get() = voiceResId != 0

    private var player: MediaPlayer? = null

    /** @return true if the spoken line played; false if it fell back to vibration. */
    fun play(): Boolean {
        if (voiceResId == 0) {
            vibrate()
            return false
        }
        // Released and recreated per prompt: these fire minutes apart at most,
        // and holding a MediaPlayer open across a whole shift for that is worse
        // than the allocation.
        release()
        val p = MediaPlayer.create(context, voiceResId) ?: run { vibrate(); return false }
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        p.setOnCompletionListener { release() }
        player = p
        p.start()
        return true
    }

    private fun vibrate() {
        // Same accessor as CaptureManager's haptics — the VIBRATOR_SERVICE
        // constant is deprecated.
        val v = context.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        // Two long pulses — distinct from the short single buzz used for capture
        // feedback, so it reads as "look at me" rather than "that worked".
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1))
    }

    fun release() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }
}
