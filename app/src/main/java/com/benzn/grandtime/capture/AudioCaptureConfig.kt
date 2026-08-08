package com.benzn.grandtime.capture

import android.media.MediaRecorder

/** Which physical built-in microphone to ask for. Both of this board's mics surface as
 *  AudioDeviceInfo.TYPE_BUILTIN_MIC, so they can only be told apart by address — measured on
 *  unit F2S202503103059: "bottom" points down, "back" points at the wearer. */
enum class MicChoice(val address: String) {
    FRONT("bottom"),
    BACK("back"),
}

/**
 * How to open a microphone. Both capture sites read a default from here instead of hardcoding
 * their own values, so the shipping configuration is one constant rather than two call sites.
 *
 * [bufferFloorBytes] is a floor, not a size: the opener uses
 * `max(AudioRecord.getMinBufferSize(...), bufferFloorBytes)`, which is what both sites already
 * did. Buffer size sets read granularity, which drives audio segment-roll timing and the video
 * loop's read cadence, so it belongs to the config rather than to AudioRecord.Builder's default.
 *
 * [enableNs] / [enableAgc] attach app-created AudioEffects to the session. They are NOT how
 * VOICE_COMMUNICATION gets its processing — this board's audio_effects.xml binds NS/AGC/AEC to
 * that source automatically, and those auto-attached effects are not enumerable from the app.
 */
data class AudioCaptureConfig(
    val source: Int,
    val sampleRate: Int,
    val bufferFloorBytes: Int,
    val preferredMic: MicChoice? = null,
    val enableNs: Boolean = false,
    val enableAgc: Boolean = false,
) {
    companion object {
        /** Standalone WAV recording (and SP-Ask, which reuses AudioRecorder). */
        val DEFAULT_STANDALONE = AudioCaptureConfig(
            source = MediaRecorder.AudioSource.MIC,
            sampleRate = 16000,
            bufferFloorBytes = 32000,
        )

        /** The video segment's AAC audio track. */
        val DEFAULT_VIDEO = AudioCaptureConfig(
            source = MediaRecorder.AudioSource.MIC,
            sampleRate = 44100,
            bufferFloorBytes = 8192,
        )
    }
}
