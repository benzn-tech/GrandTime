package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import org.json.JSONArray
import org.json.JSONObject

/** What the device knows about its own backlog. */
data class DeviceVitals(
    /**
     * Age of the oldest still-unsent recording, or null when nothing is waiting.
     *
     * Age rather than count, because count is diluted by every new recording and jumps
     * around, while age rises monotonically and is comparable across devices. One number
     * answers "is this device healthy".
     */
    val oldestPendingAgeS: Long?,
    val pending: Int,
    val frozen: Int,
    val dead: Int,
    val fingerprints: List<String>,
    /**
     * Microphone health, cumulative since install (see [com.benzn.grandtime.capture.MicHealth]).
     *
     * Cumulative on purpose: the server stores vitals as a last-write-wins UPDATE, so a
     * per-session number would be erased by the next clean report. These only ever grow.
     */
    val silentSecondsS: Int = 0,
    val longestSilentRunS: Int = 0,
    val silentRunsWithMicBorrowed: Int = 0,
    /** Null until this device has recorded at least one session. */
    val lowestSessionPeak: Int? = null,
)

data class DeviceStatusResponse(val serverBuild: String?, val thaw: List<String>)

/**
 * The device ledger's backlog channel: vitals up, thaw decisions down, one round trip.
 *
 * Best-effort by construction. A probe that fails changes nothing — it must never be able
 * to fail an upload or surface to the user, because it is telemetry riding alongside the
 * work rather than part of it. Every failure path here returns null and says no more.
 *
 * The request carries the device headers (they ride [RealHttp.postJson]) so the ledger
 * heartbeat is refreshed by the same call.
 */
class DeviceStatusClient(
    private val baseUrl: String,
    private val http: HttpFns = RealHttp(),
) {
    fun report(idToken: String, vitals: DeviceVitals): DeviceStatusResponse? = runCatching {
        val r = http.postJson("$baseUrl/org/device/status", idToken, requestBody(vitals))
        if (r.code !in 200..299) null else parse(r.body)
    }.getOrNull()

    companion object {
        fun requestBody(v: DeviceVitals): String = JSONObject().apply {
            // JSONObject.NULL, not omission: the server distinguishes "nothing waiting"
            // from "this device did not say", and only one of those is good news.
            put("oldestPendingAgeS", v.oldestPendingAgeS ?: JSONObject.NULL)
            put("pending", v.pending)
            put("frozen", v.frozen)
            put("dead", v.dead)
            put("fingerprints", JSONArray(v.fingerprints))
            put("silentSecondsS", v.silentSecondsS)
            put("longestSilentRunS", v.longestSilentRunS)
            put("silentRunsWithMicBorrowed", v.silentRunsWithMicBorrowed)
            put("lowestSessionPeak", v.lowestSessionPeak ?: JSONObject.NULL)
        }.toString()

        fun parse(body: String?): DeviceStatusResponse? = runCatching {
            val json = JSONObject(body ?: return null)
            val arr = json.optJSONArray("thaw")
            DeviceStatusResponse(
                serverBuild = json.optString("serverBuild").takeIf { it.isNotBlank() },
                thaw = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) },
            )
        }.getOrNull()
    }
}
