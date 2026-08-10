package com.benzn.grandtime.devprobe

import android.media.MediaRecorder
import com.benzn.grandtime.capture.AudioCaptureConfig
import com.benzn.grandtime.capture.MicChoice

/** One acoustic condition, recorded once per take. The speaker's state changes only between
 *  blocks, which is what keeps friction measurable separately from speech. */
enum class ProbeBlock(val label: String, val instruction: String, val seconds: Int = 10) {
    S("S", "Speaker ON. Stand still."),
    F("F", "Speaker OFF. March in place, rub the case and clothing."),
    N("N", "Speaker OFF. Say: one two three four five six seven eight nine ten."),
    D("D", "Read the enrolment passage. Stand still, device worn normally.", 10),

    /**
     * One long stereo take, so a distance ladder can be walked without a pause.
     *
     * The six short takes of block D answer whether two channels exist. This
     * answers what they are worth: the same 0 / 0.5 / 2 / 4 / 6 m walk already
     * recorded in mono this morning, now with both microphones, so the pair can
     * be compared against a measurement that exists rather than against a guess.
     *
     * It must be ONE take. Splitting the ladder across takes puts a gap and a
     * fresh AudioRecord between distances, and the previous mono attempt showed
     * what that costs: it was paused to change rooms, so its 2 m -> 4 m step
     * mixed distance with a room change and had to be rerun.
     *
     * 16 kHz only. The 44.1 kHz stereo take in block D came back with the
     * channels correlating 0.160 and 14.8 dB apart, nothing like the three
     * 16 kHz takes -- the same "reports healthy, does not do the work" shape
     * this board showed for NS/AGC at 44.1 kHz.
     */
    E("E", "Ladder, ONE take. Worn -> 0.5 -> 2 -> 4 -> 6 m. Say the distance, " +
        "then SILENT 4 s, then the four sentences. The silence is what splits " +
        "the blocks - without it the distances cannot be told apart.", 150),

    /**
     * Worn, with a second person at distance. One take, three questions.
     *
     * Block E answered "how much is a two-mic sum worth" and the answer was
     * almost nothing. It also threw up something bigger: the two channels
     * differed by 7.1 dB and 7.4 dB of SNR at 2 m and 6 m. That is more than
     * the whole delay-and-sum gain and close to a six-microphone array -- from
     * hardware already in hand.
     *
     * It cannot be acted on yet, for two reasons this block removes:
     *
     *  1. Nobody knows which channel is which microphone. Block D showed the
     *     level difference FLIPPING SIGN between a FRONT and a BACK request, so
     *     the channel-to-mic mapping is not fixed and cannot be assumed. The
     *     take therefore opens with the wearer tapping beside each microphone
     *     in turn: whichever channel spikes IS that microphone. A physical cue,
     *     not an inference.
     *  2. Block E was recorded with the device PLACED on a surface for every
     *     distance except 0 m. Placed, one mic faces the room and the other may
     *     face whatever it is resting on -- nothing like the worn geometry that
     *     ships. Note that the only worn row in block E showed a 0.5 dB
     *     difference, and 4 m showed 1.0 dB against 2 m and 6 m's 7 dB, which
     *     is the pattern of a placement artefact rather than a property of the
     *     microphones.
     *
     * A friction sample is in the script on purpose: clothing rubbing the case
     * is this device's dominant noise (speech -38.0 dBFS against friction
     * -34.6 dBFS), so "does one microphone hear less of it" is the question
     * behind the SNR difference.
     */
    W("W", "WORN throughout, with a second person. Tap beside each mic first, " +
        "then friction, then have them read at 1 m / 3 m / 6 m. Follow the " +
        "script; leave 4 s of silence between every section.", 190),
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
/** The distance ladder: a single stereo take, long enough to walk it. */
val LADDER_TAKES: List<ProbeTake> = listOf(
    ProbeTake(1, "stereo_ladder_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(channelCount = 2)),
)

/** Worn, second talker at distance. Stereo so both mics are captured at once:
 *  two separate mono takes would compare different moments, and the thing being
 *  measured is smaller than the difference between two moments. */
val WORN_FAR_TAKES: List<ProbeTake> = listOf(
    ProbeTake(1, "stereo_worn_far_16k",
        AudioCaptureConfig.DEFAULT_STANDALONE.copy(channelCount = 2)),
)

/** Which configurations a block runs. Only D and E differ; S/F/N keep the original ten. */
fun takesFor(block: ProbeBlock): List<ProbeTake> = when (block) {
    ProbeBlock.D -> DUAL_TAKES
    ProbeBlock.E -> LADDER_TAKES
    ProbeBlock.W -> WORN_FAR_TAKES
    else -> PROBE_TAKES
}
