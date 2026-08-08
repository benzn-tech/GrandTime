package com.benzn.grandtime.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * A live microphone plus the record of what the platform actually granted.
 *
 * Two limits are deliberate and documented rather than papered over:
 *  - AudioRecord never reports a sample rate other than the one requested; it either initializes
 *    or fails, and AudioFlinger resamples invisibly if the HAL captured at a different rate. So
 *    [reportJson]'s rate is a request echo, and the real check is spectral, done offline.
 *  - Effects the audio policy auto-attaches to a source (this board binds NS/AGC/AEC to
 *    VOICE_COMMUNICATION) cannot be enumerated from an app. Only app-created effects report
 *    their enabled state here.
 */
class OpenedMic internal constructor(
    val record: AudioRecord,
    val bufferBytes: Int,
    private val config: AudioCaptureConfig,
    private val effects: List<AudioEffect>,
    private val requestedMicAddress: String?,
    private val preferredDeviceRequestAccepted: Boolean?,
) {
    /** Routing facts only become true after startRecording(), so this is safe to call any time
     *  but only meaningful afterwards. Never throws — a diagnostic must not break a recording. */
    fun reportJson(): String {
        val routed = runCatching { record.routedDevice }.getOrNull()
        val active = runCatching { record.activeMicrophones }.getOrNull().orEmpty()
        return jsonObject(listOf(
            "requestedSource" to "${config.source}",
            "requestedSampleRate" to "${config.sampleRate}",
            "grantedSampleRate" to "${runCatching { record.sampleRate }.getOrDefault(-1)}",
            "grantedChannelCount" to "${runCatching { record.channelCount }.getOrDefault(-1)}",
            "bufferBytes" to "$bufferBytes",
            "requestedMicAddress" to (requestedMicAddress?.let { jsonString(it) } ?: "null"),
            "preferredDeviceRequestAccepted" to (preferredDeviceRequestAccepted?.toString() ?: "null"),
            "routedDeviceType" to "${routed?.type ?: -1}",
            "routedDeviceAddress" to jsonString(routed?.address ?: ""),
            // Synthesized from the routed device on a generic HAL, so this does NOT reveal whether
            // one or two mics feed the stream. Diagnostics only.
            "activeMicrophoneAddresses" to jsonArray(active.map { jsonString(it.address ?: "") }),
            "requestedNs" to "${config.enableNs}",
            "requestedAgc" to "${config.enableAgc}",
            "appEffects" to jsonArray(effects.map {
                jsonObject(listOf(
                    "name" to jsonString(it.javaClass.simpleName),
                    "enabled" to "${runCatching { it.enabled }.getOrDefault(false)}",
                ))
            }),
            "nsAvailable" to "${runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)}",
            "agcAvailable" to "${runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)}",
            "aecAvailable" to "${runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)}",
        ))
    }

    /** Releases the app-created effects before the record, then the record. Idempotent-safe:
     *  every call is guarded, so a second invocation cannot throw. */
    fun stopAndRelease() {
        effects.forEach { runCatching { it.enabled = false }; runCatching { it.release() } }
        runCatching { record.stop() }
        runCatching { record.release() }
    }
}

/**
 * Opens a microphone per [config].
 *
 * Throws IllegalStateException when a requested physical mic cannot be resolved, rather than
 * quietly recording from the default one — an unnoticed fallback would invalidate exactly the
 * comparison this exists to support. Caller (preflight) must already hold RECORD_AUDIO.
 */
@SuppressLint("MissingPermission")
fun openMic(context: Context, config: AudioCaptureConfig): OpenedMic {
    val minBuf = AudioRecord.getMinBufferSize(
        config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    val bufferBytes = maxOf(minBuf, config.bufferFloorBytes)

    var chosen: InputDevice? = null
    if (config.preferredMic != null) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map {
            InputDevice(it.id, it.type, it.address ?: "", it.productName?.toString() ?: "")
        }
        chosen = resolvePreferredMic(devices, config.preferredMic)
            ?: throw IllegalStateException(
                "no built-in mic with address '${config.preferredMic.address}' on this device"
            )
    }

    val record = AudioRecord(
        config.source, config.sampleRate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes
    )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IllegalStateException("AudioRecord not initialized (source=${config.source}, rate=${config.sampleRate})")
    }

    var accepted: Boolean? = null
    val effects = ArrayList<AudioEffect>(2)
    try {
        if (chosen != null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val target = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == chosen.id }
            accepted = target != null && record.setPreferredDevice(target)
        }

        if (config.enableNs && NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()
                ?.also { effects.add(it); runCatching { it.enabled = true } }
        }
        if (config.enableAgc && AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(record.audioSessionId) }.getOrNull()
                ?.also { effects.add(it); runCatching { it.enabled = true } }
        }
    } catch (t: Throwable) {
        effects.forEach { runCatching { it.release() } }
        runCatching { record.release() }
        throw t
    }

    return OpenedMic(record, bufferBytes, config, effects, config.preferredMic?.address, accepted)
}
