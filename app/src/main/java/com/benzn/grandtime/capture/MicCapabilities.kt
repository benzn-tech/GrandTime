package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * What this unit's audio hardware reports about itself.
 *
 * Worth collecting per unit, not just once: this board ships in two BOM variants whose
 * microphones differ by 15 dB of sensitivity, so recording level may be a property of the
 * handset rather than of the software. `description` carries the fitted part identifier.
 */
object MicCapabilities {

    /** A lying HAL can return NaN/Infinity, which Kotlin renders as bare tokens that are not JSON.
     *  Emit null instead, so one bad float cannot make the whole snapshot unparseable. */
    private fun jsonNumber(f: Float): String = if (f.isFinite()) "$f" else "null"

    fun snapshotJson(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val inputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
            .getOrDefault(emptyList())
            .map { d ->
                jsonObject(listOf(
                    "id" to "${d.id}",
                    "type" to "${d.type}",
                    "address" to jsonString(d.address ?: ""),
                    "productName" to jsonString(d.productName?.toString() ?: ""),
                    "channelCounts" to jsonArray(d.channelCounts.map { "$it" }),
                    "sampleRates" to jsonArray(d.sampleRates.map { "$it" }),
                ))
            }

        val mics = runCatching { am.microphones }.getOrDefault(emptyList()).map { m ->
            val p = runCatching { m.position }.getOrNull()
            jsonObject(listOf(
                "id" to "${m.id}",
                "address" to jsonString(m.address ?: ""),
                "description" to jsonString(m.description ?: ""),
                "location" to "${m.location}",
                "directionality" to "${m.directionality}",
                "sensitivity" to jsonNumber(m.sensitivity),
                "maxSpl" to jsonNumber(m.maxSpl),
                "position" to jsonArray(
                    if (p == null) emptyList() else listOf(jsonNumber(p.x), jsonNumber(p.y), jsonNumber(p.z))
                ),
            ))
        }

        return jsonObject(listOf(
            "model" to jsonString(android.os.Build.MODEL),
            "device" to jsonString(android.os.Build.DEVICE),
            "serial" to jsonString(runCatching { android.os.Build.getSerial() }.getOrDefault("")),
            "nsAvailable" to "${runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)}",
            "agcAvailable" to "${runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)}",
            "aecAvailable" to "${runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)}",
            "inputDevices" to jsonArray(inputs),
            "microphones" to jsonArray(mics),
        ))
    }
}
