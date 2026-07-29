package com.benzn.grandtime.net

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONObject

/**
 * Best-effort voice-timeliness session lifecycle calls (spec §6). Fire-and-forget from the caller;
 * correctness never depends on these — the backend reconstructs sessions from the uploaded `_sid/_c`
 * chunks + close-by-inactivity. Mirrors [RecordingsApiClient]'s testability (inject [HttpFns]).
 *
 * Endpoints (backend `lambda_org_api.py`, camelCase body):
 *   POST {baseUrl}/org/sessions/{sid}/open   {startedAt, kind, siteId?}
 *   POST {baseUrl}/org/sessions/{sid}/close  {intent, endedAt}
 * Auth: raw idToken in the Authorization header (no "Bearer"), same as RecordingsApiClient.
 */
class SessionsApiClient(
    private val baseUrl: String,
    private val http: HttpFns = RealHttp(),
) {
    /** @return true iff the server returned 2xx; any failure/exception → false (best-effort). */
    fun open(idToken: String, sessionId: String, startedAtMillis: Long, kind: String, siteId: String?): Boolean {
        val body = JSONObject()
            .put("startedAt", iso(startedAtMillis))
            .put("kind", kind)
        siteId?.let { body.put("siteId", it) }
        return post("$baseUrl/org/sessions/$sessionId/open", idToken, body)
    }

    /** @return true iff the server returned 2xx; any failure/exception → false (best-effort). */
    fun close(idToken: String, sessionId: String, endedAtMillis: Long, intent: String): Boolean {
        val body = JSONObject()
            .put("intent", intent)
            .put("endedAt", iso(endedAtMillis))
        return post("$baseUrl/org/sessions/$sessionId/close", idToken, body)
    }

    private fun post(url: String, idToken: String, body: JSONObject): Boolean =
        runCatching { http.postJson(url, idToken, body.toString()).code in 200..299 }.getOrElse { false }

    // NZ-local ISO so the server derives the session date in NZ, matching the chunk keys (UploadWorker.iso8601).
    private fun iso(epochMillis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.of("Pacific/Auckland")).toString()
}
