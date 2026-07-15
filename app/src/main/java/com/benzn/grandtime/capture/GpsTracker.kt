package com.benzn.grandtime.capture

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject

/** GPS 采集:LocationManager(GPS_PROVIDER ~1s)。持最新 fix + 累积轨迹。地址反查推后(P3 不做)。 */
class GpsTracker(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile var latestFix: Pair<Double, Double>? = null
        private set
    private val track = JSONArray()

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            latestFix = loc.latitude to loc.longitude
            synchronized(track) {
                track.put(JSONObject().put("t", loc.time).put("lat", loc.latitude).put("lon", loc.longitude))
            }
        }
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission") // 调用方确保 ACCESS_FINE_LOCATION(未授权则 start no-op)
    fun start() {
        synchronized(track) { for (i in track.length() - 1 downTo 0) track.remove(i) }
        latestFix = null
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, context.mainLooper)
        }
    }

    fun stop() { runCatching { lm.removeUpdates(listener) } }

    fun snapshotTrackJson(): String? = synchronized(track) {
        if (track.length() == 0) null else track.toString()
    }
}
