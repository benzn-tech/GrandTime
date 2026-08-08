package com.benzn.grandtime.capture

import android.media.AudioDeviceInfo

/** A flattened AudioDeviceInfo, so the selection decision can be tested without a device. */
data class InputDevice(
    val id: Int,
    val type: Int,
    val address: String,
    val productName: String,
)

/**
 * Picks the built-in microphone whose address matches [choice], or null if this board does not
 * expose it.
 *
 * Returning null rather than falling back to the default mic is the point: a silent fallback
 * would make "recorded from the back mic" indistinguishable from "recorded from whatever the
 * platform felt like", which is exactly the comparison the probe exists to make.
 */
fun resolvePreferredMic(devices: List<InputDevice>, choice: MicChoice): InputDevice? =
    devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.address.equals(choice.address, ignoreCase = true)
    }

/** Minimal JSON emitters. org.json is not available in JVM unit tests and a new dependency is
 *  not allowed, so the probe's artefacts are built from these three functions. Callers pass
 *  already-rendered values, so numbers and booleans go in bare and strings go through
 *  [jsonString]. */
fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

fun jsonObject(fields: List<Pair<String, String>>): String =
    fields.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}:$v" }

fun jsonArray(items: List<String>): String = items.joinToString(",", "[", "]")
