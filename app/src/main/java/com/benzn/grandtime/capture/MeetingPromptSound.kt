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

    /**
     * TWO lines, because there are two situations and they are not the same
     * question.
     *
     * `meeting_prompt` — this device stopped, and nobody has said whether the
     * meeting is over. It ASKS.
     *
     * `meeting_ended` — someone else already ended the meeting and this device
     * was stopped for them. It TELLS. Asking here would invite an answer to a
     * question that is already settled, and it would contradict the screen,
     * which is offering to start a fresh recording rather than asking about the
     * meeting at all.
     *
     * Both resolved by NAME so either can be dropped in later without a code
     * change, and a missing one degrades to vibration rather than silence.
     */
    private fun res(name: String): Int =
        context.resources.getIdentifier(name, "raw", context.packageName)

    private val askResId: Int = res("meeting_prompt")
    private val endedResId: Int = res("meeting_ended")

    val hasVoiceLine: Boolean get() = askResId != 0

    private var player: MediaPlayer? = null

    /** Ask whether the meeting has ended. @return true if the line played. */
    fun play(): Boolean = speak(askResId)

    /**
     * Say that the meeting was ended elsewhere. Falls back to the asking line
     * only if it is the only one bundled — a slightly wrong sentence beats
     * silence when the device has just stopped on its own.
     */
    fun playEnded(): Boolean = speak(if (endedResId != 0) endedResId else askResId)

    private fun speak(voiceResId: Int): Boolean {
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
