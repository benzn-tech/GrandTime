package com.benzn.grandtime.hardware

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * What a buzz means on this device. The vocabulary is not new — [SHORT] and
 * [DOUBLE_SHORT] are what CaptureCore has always emitted, and an operator already
 * reads two buzzes as "refused". [LONG] is the only addition, for an operation that
 * has finished on its own some seconds after the key was released.
 *
 * A bare enum with no Android imports, so the pure decision cores can name a pattern
 * without taking a platform dependency.
 */
enum class VibePattern { SHORT, DOUBLE_SHORT, LONG }

/**
 * The waveform for a pattern. Pure, so the table itself is unit-testable.
 *
 * This is the shared table for the SHORT/DOUBLE_SHORT/LONG vocabulary used by AskCore,
 * SiteVoiceCore, and CaptureManager — not the only waveform literal in the app.
 * [com.benzn.grandtime.capture.MeetingPromptSound] still hand-rolls its own
 * `longArrayOf(0, 400, 250, 400)` "look at me" pattern outside this table (deliberately:
 * it is not part of this accepted/refused/finished vocabulary), so don't assume every
 * buzz on this device traces back to here.
 */
fun waveformFor(pattern: VibePattern): LongArray = when (pattern) {
    VibePattern.SHORT -> longArrayOf(0, 80)
    VibePattern.DOUBLE_SHORT -> longArrayOf(0, 60, 80, 60)
    // Duration, not a pulse count: three buzzes and two buzzes are not reliably
    // distinguishable through a jacket, but long and short are.
    VibePattern.LONG -> longArrayOf(0, 350)
}

/** Plays a [VibePattern]. Silently does nothing on a device with no vibrator. */
class Haptics(private val context: Context) {
    fun play(pattern: VibePattern) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(waveformFor(pattern), -1))
    }
}
