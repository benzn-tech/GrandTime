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
    D("D", "Read the enrolment passage. Stand still, device worn normally."),
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

/**
 * Block D: does this board give two independent microphones, or one duplicated?
 *
 * This is the question that decides whether a 4-6 mic array is worth buying. Beamforming gain
 * comes from the phase relationship BETWEEN channels; if the platform satisfies a stereo request
 * by copying one mic into both, that relationship does not exist and no array on this board can
 * work. Nothing in the earlier ten-configuration probe touched it -- those all measured a single
 * channel, selecting WHICH mic rather than using both at once.
 *
 * The takes are ordered so the cheap decisive answer comes first, and each later one only earns
 * its place if the earlier answer was ambiguous:
 *
 *  1. mono control, so channel behaviour is compared against a known-good take from the same
 *     minute rather than against a recording made under different conditions.
 *  2. the question itself, on the routing production uses.
 *  3/4. stereo with each physical mic explicitly requested. If the HAL duplicates, these will be
 *     identical to each other AND to take 2 -- and if it does not, requesting one mic while
 *     opening two channels is exactly the contradiction most likely to expose what it really did.
 *  5. 44.1 kHz. Inter-channel delay for ~10 cm of spacing is about 0.3 ms: under 5 samples at
 *     16 kHz, which is too coarse to measure a direction, but 13 at 44.1 kHz. If 16 kHz stereo is
 *     refused this is also the fallback that says whether stereo works at all.
 *  6. mono repeat, so drift over the block cannot be read as a channel effect.
 *
 * A configuration this board refuses is recorded as a result, not a crash (see ProbeRunner).
 */
val DUAL_TAKES: List<ProbeTake> = listOf(
    ProbeTake(1, "mono_16k", AudioCaptureConfig.DEFAULT_STANDALONE),
    ProbeTake(2, "stereo_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(channelCount = 2)),
    ProbeTake(3, "stereo_16k_front", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        channelCount = 2, preferredMic = MicChoice.FRONT)),
    ProbeTake(4, "stereo_16k_back", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        channelCount = 2, preferredMic = MicChoice.BACK)),
    ProbeTake(5, "stereo_44k", AudioCaptureConfig.DEFAULT_VIDEO.copy(channelCount = 2)),
    ProbeTake(6, "mono_16k_repeat", AudioCaptureConfig.DEFAULT_STANDALONE),
)

/** Which configurations a block runs. Only D differs; S/F/N keep the original ten. */
fun takesFor(block: ProbeBlock): List<ProbeTake> =
    if (block == ProbeBlock.D) DUAL_TAKES else PROBE_TAKES
