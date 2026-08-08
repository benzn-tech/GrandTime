package com.benzn.grandtime.devprobe

import android.media.MediaRecorder
import com.benzn.grandtime.capture.AudioCaptureConfig
import com.benzn.grandtime.capture.MicChoice

/** One acoustic condition, recorded once per take. The speaker's state changes only between
 *  blocks, which is what keeps friction measurable separately from speech. */
enum class ProbeBlock(val label: String, val instruction: String) {
    S("S", "Speaker ON. Stand still."),
    F("F", "Speaker OFF. March in place, rub the case and clothing."),
    N("N", "Speaker OFF. Say: one two three four five six seven eight nine ten."),
}

data class ProbeTake(val index: Int, val name: String, val config: AudioCaptureConfig)

/**
 * The configurations under test, in run order.
 *
 * Takes 6, 9 and 10 are expected to be uninformative if everything is as predicted, and are
 * included for exactly that reason: 6 checks the gain-table reading that says CAMCORDER is
 * quieter than MIC, 9 turns "AOSP preprocessing may reject 44.1 kHz" into a measurement, and 10
 * repeats take 1 so scene drift over six minutes of marching cannot masquerade as a win.
 */
val PROBE_TAKES: List<ProbeTake> = listOf(
    ProbeTake(1, "mic_16k", AudioCaptureConfig.DEFAULT_STANDALONE),
    ProbeTake(2, "voicecomm_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        source = MediaRecorder.AudioSource.VOICE_COMMUNICATION)),
    ProbeTake(3, "mic_front_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        preferredMic = MicChoice.FRONT)),
    ProbeTake(4, "mic_back_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        preferredMic = MicChoice.BACK)),
    ProbeTake(5, "mic_nsagc_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        enableNs = true, enableAgc = true)),
    ProbeTake(6, "camcorder_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        source = MediaRecorder.AudioSource.CAMCORDER)),
    ProbeTake(7, "mic_44k", AudioCaptureConfig.DEFAULT_VIDEO),
    ProbeTake(8, "voicecomm_44k", AudioCaptureConfig.DEFAULT_VIDEO.copy(
        source = MediaRecorder.AudioSource.VOICE_COMMUNICATION)),
    ProbeTake(9, "mic_nsagc_44k", AudioCaptureConfig.DEFAULT_VIDEO.copy(
        enableNs = true, enableAgc = true)),
    ProbeTake(10, "mic_16k_repeat", AudioCaptureConfig.DEFAULT_STANDALONE),
)
