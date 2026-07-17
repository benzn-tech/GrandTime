package com.benzn.grandtime.capture

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject

/** GPS 采集:LocationManager(GPS_PROVIDER ~1s)。持最新 fix(带到达墙钟时间)+ 累积轨迹。地址反查推后(P3 不做)。 */
class GpsTracker(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** 一次定位 + 到达墙钟时间(loc.time 可能滞后于系统时钟,回调到达那刻用 System.currentTimeMillis() 记)。 */
    private data class Fix(val lat: Double, val lon: Double, val atMillis: Long)

    @Volatile private var fix: Fix? = null
    private val track = JSONArray()

    /** 兼容既有调用方:最新定位(不判新鲜度)。 */
    val latestFix: Pair<Double, Double>? get() = fix?.let { it.lat to it.lon }

    /** 新鲜度门控:超过 maxAgeMs 未刷新视为过期(GPS 丢失/隧道场景不拿旧坐标冒充当前定位)。 */
    fun freshFix(maxAgeMs: Long = 30_000L): Pair<Double, Double>? {
        val f = fix ?: return null
        return if (System.currentTimeMillis() - f.atMillis <= maxAgeMs) f.lat to f.lon else null
    }

    /** Last known fix WITH its age (indoor fallback): lets the caller show a labelled stale fix
     * instead of a bare "Locating…" placeholder, without hiding how old the coords are. */
    data class AgedFix(val lat: Double, val lon: Double, val ageMs: Long)

    fun agedFix(nowMs: Long = System.currentTimeMillis()): AgedFix? =
        fix?.let { AgedFix(it.lat, it.lon, (nowMs - it.atMillis).coerceAtLeast(0)) }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            fix = Fix(loc.latitude, loc.longitude, System.currentTimeMillis())
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
        fix = null
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, context.mainLooper)
        }
    }

    /** 停止刷新 + 清最新 fix(Idle 拍照展示占位,不留上一段录像坐标的残影);track 保留供落库用。 */
    fun stop() {
        runCatching { lm.removeUpdates(listener) }
        fix = null
    }

    fun snapshotTrackJson(): String? = synchronized(track) {
        if (track.length() == 0) null else track.toString()
    }
}
