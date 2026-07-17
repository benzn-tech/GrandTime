package com.benzn.grandtime.capture

/** Indoor GPS fallback window: show a labelled stale fix within this age, else "Locating…". */
const val GPS_FALLBACK_WINDOW_MS = 10 * 60_000L // 10 min

/** Human age label for a stale fix: "~45s ago" under a minute, else "~5m ago". */
fun gpsAgeLabel(ageMs: Long): String {
    val s = ageMs / 1000
    return if (s < 60) "~${s}s ago" else "~${s / 60}m ago"
}

/** Coords to show on the watermark GPS line, plus an age label when the coords are a stale
 * fallback (null label = current fix, never mislabel a stale fix as live). */
data class GpsWatermark(val lat: Double?, val lon: Double?, val ageLabel: String?)

/** Fresh fix -> current coords (no label). Else last fix within the window -> coords + age label.
 *  Else -> no coords (caller shows the noFix placeholder). */
fun gpsWatermark(
    fresh: Pair<Double, Double>?,
    aged: GpsTracker.AgedFix?,
    windowMs: Long = GPS_FALLBACK_WINDOW_MS,
): GpsWatermark = when {
    fresh != null -> GpsWatermark(fresh.first, fresh.second, null)
    aged != null && aged.ageMs <= windowMs -> GpsWatermark(aged.lat, aged.lon, gpsAgeLabel(aged.ageMs))
    else -> GpsWatermark(null, null, null)
}
