package com.benzn.grandtime.capture

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Latest fix + the current recording's track, without any Android types so it can be unit-tested.
 *
 * The fix outlives a recording on purpose: GPS stays warm between recordings (see [GpsTracker]),
 * and a stale fix is never passed off as current — [freshFix] gates on age and the watermark
 * labels anything older via [agedFix].
 */
class GpsFixState {
    /** A fix + the wall-clock moment it became known (loc.time can lag the system clock). */
    private data class Fix(val lat: Double, val lon: Double, val atMillis: Long)

    data class TrackPoint(val t: Long, val lat: Double, val lon: Double)

    @Volatile private var fix: Fix? = null
    @Volatile private var tracking = false
    private val track = ArrayList<TrackPoint>()

    fun onFix(lat: Double, lon: Double, gpsTime: Long, nowMillis: Long) {
        fix = Fix(lat, lon, nowMillis)
        if (tracking) synchronized(track) { track.add(TrackPoint(gpsTime, lat, lon)) }
    }

    /** The system's last known fix at warm-up: shown with its real age, never tracked, and never
     *  allowed to replace a fix this process received itself. */
    fun seed(lat: Double, lon: Double, fixElapsedNanos: Long, nowElapsedNanos: Long, nowMillis: Long) {
        if (fix != null) return
        val ageMs = ((nowElapsedNanos - fixElapsedNanos) / 1_000_000L).coerceAtLeast(0)
        fix = Fix(lat, lon, nowMillis - ageMs)
    }

    /** Start of a recording: empty track, keep the warm fix. */
    fun beginTrack() {
        synchronized(track) { track.clear() }
        tracking = true
    }

    /** End of a recording: stop tracking; the track stays for the last segment's DB row. */
    fun endTrack() { tracking = false }

    fun trackPoints(): List<TrackPoint> = synchronized(track) { track.toList() }

    /** Freshness gate: older than maxAgeMs counts as lost (tunnel / indoors), so old coords never pose as current. */
    fun freshFix(maxAgeMs: Long = 30_000L, nowMillis: Long = System.currentTimeMillis()): Pair<Double, Double>? {
        val f = fix ?: return null
        return if (nowMillis - f.atMillis <= maxAgeMs) f.lat to f.lon else null
    }

    fun agedFix(nowMillis: Long = System.currentTimeMillis()): GpsTracker.AgedFix? =
        fix?.let { GpsTracker.AgedFix(it.lat, it.lon, (nowMillis - it.atMillis).coerceAtLeast(0)) }
}

/**
 * GPS via LocationManager (GPS_PROVIDER, ~1 s). This ROM has no network provider and the GNSS is
 * weak (cold TTFF measured at 173 s), so the receiver is kept WARM for the life of the capture
 * service instead of being switched on at record start: a recording then starts with a current
 * fix instead of a multi-minute search. The track is only accumulated between [beginTrack] and
 * [endTrack].
 */
class GpsTracker(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val state = GpsFixState()
    @Volatile private var listening = false
    /** elapsedRealtime of warm-up until the first live fix lands; 0 after. For the TTFF log line. */
    @Volatile private var warmedAt = 0L

    /** Last known fix WITH its age (indoor fallback): lets the caller show a labelled stale fix
     * instead of a bare "Locating…" placeholder, without hiding how old the coords are. */
    data class AgedFix(val lat: Double, val lon: Double, val ageMs: Long)

    fun freshFix(maxAgeMs: Long = 30_000L): Pair<Double, Double>? = state.freshFix(maxAgeMs)

    fun agedFix(nowMs: Long = System.currentTimeMillis()): AgedFix? = state.agedFix(nowMs)

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            state.onFix(loc.latitude, loc.longitude, loc.time, System.currentTimeMillis())
            if (warmedAt != 0L) {
                Log.i(TAG, "first fix ${(SystemClock.elapsedRealtime() - warmedAt) / 1000}s after warm-up")
                warmedAt = 0L
            }
        }
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    /** Idempotent. Caller ensures ACCESS_FINE_LOCATION; without it this logs and stays off,
     *  and a later call (after the grant) turns it on. */
    @SuppressLint("MissingPermission")
    fun warm() {
        if (listening) return
        runCatching {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) {
                state.seed(last.latitude, last.longitude, last.elapsedRealtimeNanos,
                    SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
            }
            warmedAt = SystemClock.elapsedRealtime()
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, context.mainLooper)
            listening = true
            Log.i(TAG, "warm-up: last known fix " + (last?.let {
                "${(SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000L}s old"
            } ?: "none"))
        }.onFailure { Log.w(TAG, "GPS warm-up failed", it) }
    }

    /** Start of a video recording: warm (if not already) and start a fresh track. */
    fun beginTrack() {
        warm()
        state.beginTrack()
    }

    /** End/pause of a video recording. GPS stays warm; the track stays for persisting. */
    fun endTrack() = state.endTrack()

    /** Service teardown only. */
    fun shutdown() {
        runCatching { lm.removeUpdates(listener) }
        listening = false
    }

    fun snapshotTrackJson(): String? {
        val pts = state.trackPoints()
        if (pts.isEmpty()) return null
        val arr = JSONArray()
        for (p in pts) arr.put(JSONObject().put("t", p.t).put("lat", p.lat).put("lon", p.lon))
        return arr.toString()
    }

    private companion object { const val TAG = "GpsTracker" }
}
