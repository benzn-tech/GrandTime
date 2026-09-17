package com.benzn.grandtime.ask

import com.benzn.grandtime.auth.HttpResult
import com.benzn.grandtime.net.HttpFns
import com.benzn.grandtime.net.RealHttp
import org.json.JSONObject

/**
 * Synchronous client for the hands-free voice ask endpoint. Mirrors
 * [com.benzn.grandtime.net.RecordingsApiClient]'s HttpFns DI (real OkHttp +
 * injectable fake). baseUrl already ends in `/prod/api`, so this POSTs to
 * `$baseUrl/ask/voice` = `/api/ask/voice`. NOT the WorkManager upload queue.
 *
 * Request/response shapes are the backend Contract (see the plan's Contract
 * section + backend Task 5): request {audio, format, mode:"voice", tz?};
 * response {transcript, answerText, audioBase64, audioFormat} or {error, transcript?}.
 *
 * `tz` is the device's IANA zone id (e.g. "Pacific/Auckland"). The backend
 * resolves relative dates like "yesterday" against it (query_slots.resolve_today).
 * It is best-effort: an unusable value simply omits the key, which the backend
 * treats as an unfiltered search (today's behaviour without this change).
 */
class AskApiClient(
    private val baseUrl: String,
    private val http: HttpFns = RealHttp(),
    private val zoneId: () -> String = { java.time.ZoneId.systemDefault().id },
) {
    sealed interface AskResult {
        data class Ok(
            val transcript: String,
            val answerText: String,
            val audioBase64: String,
            val audioFormat: String,
        ) : AskResult
        data class StageError(val message: String, val transcript: String?) : AskResult
        data object AuthExpired : AskResult
        data class Error(val message: String) : AskResult
    }

    fun ask(idToken: String, audioBase64: String, format: String = "wav"): AskResult {
        val body = JSONObject()
            .put("audio", audioBase64)
            .put("format", format)
            .put("mode", "voice")
        // The caller's own zone, not a date: the backend resolves "yesterday"
        // against it (query_slots.resolve_today). Without this every spoken
        // relative date searches all of time. An unusable value costs nothing --
        // resolve_today returns None and the search is unfiltered, which is
        // exactly today's behaviour.
        runCatching { zoneId() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { body.put("tz", it) }
        val result = runCatching { http.postJson("$baseUrl/ask/voice", idToken, body.toString()) }
            .getOrElse { return AskResult.Error("network") }
        return parse(result)
    }

    companion object {
        fun parse(r: HttpResult): AskResult {
            if (r.code == 401) return AskResult.AuthExpired
            return runCatching {
                if (r.code !in 200..299) return@runCatching AskResult.Error("HTTP ${r.code}: ${r.body}")
                val json = JSONObject(r.body)
                // Server-side stage failure comes back 200 with an `error` field.
                if (json.has("error")) {
                    return@runCatching AskResult.StageError(
                        json.optString("error"),
                        if (json.has("transcript")) json.optString("transcript") else null,
                    )
                }
                val audio = json.optString("audioBase64")
                if (audio.isBlank()) return@runCatching AskResult.Error("missing audioBase64")
                AskResult.Ok(
                    transcript = json.optString("transcript"),
                    answerText = json.optString("answerText"),
                    audioBase64 = audio,
                    audioFormat = json.optString("audioFormat", "wav"),
                )
            }.getOrElse { AskResult.Error("malformed response") }
        }
    }
}
