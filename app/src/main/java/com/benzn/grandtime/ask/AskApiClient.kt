package com.benzn.grandtime.ask

import com.benzn.grandtime.auth.HttpResult
import com.benzn.grandtime.net.HttpFns
import com.benzn.grandtime.net.RealHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    /** Epoch millis until which `/ask/voice/start` is known not to exist. 0 = unknown/exists. */
    @Volatile private var noRouteUntil = 0L

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

    sealed interface StartResult {
        data class Started(
            val turnId: String,
            val transcript: String,
            val webIntent: Boolean,
            val restatementWav: ByteArray?,
        ) : StartResult
        /** The backend has no two-phase routes (404/403 without a JSON error body). Use [ask]. */
        data object NoRoute : StartResult
        data class StageError(val message: String, val transcript: String?) : StartResult
        data object AuthExpired : StartResult
        data class Error(val message: String) : StartResult
    }

    sealed interface TurnStatus {
        data object Pending : TurnStatus
        data object SearchingWeb : TurnStatus
        data object Cancelled : TurnStatus
        data class Failed(val message: String) : TurnStatus
        data object NotFound : TurnStatus
        data object AuthExpired : TurnStatus
        class Done(val transcript: String, val answerText: String, val wav: ByteArray) : TurnStatus
    }

    /**
     * Phase 1: transcribe, mint a turn, maybe restate. Cancellable: cancelling the caller cancels
     * the HTTP call. While a recent 404/403 says the backend lacks the route, answers [StartResult.NoRoute]
     * without a call; the memo expires after [NO_ROUTE_TTL_MS] so a deploy-window 404 is not pinned.
     */
    suspend fun start(idToken: String, clip: ByteArray, tz: String? = null): StartResult {
        if (now() < noRouteUntil) return StartResult.NoRoute
        val body = JSONObject()
            // Off the caller's thread: the caller is Main, and a 15 s clip is a few hundred KB.
            .put("audio", withContext(Dispatchers.Default) { java.util.Base64.getEncoder().encodeToString(clip) })
            .put("format", "wav")
        (tz ?: runCatching { zoneId() }.getOrNull())?.takeIf { it.isNotBlank() }
            ?.let { body.put("tz", it) }
        val r = try {
            http.postJsonSuspending("$baseUrl/ask/voice/start", idToken, body.toString())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return StartResult.Error("network")
        }
        val parsed = withContext(Dispatchers.Default) { parseStart(r) }
        if (parsed is StartResult.NoRoute) noRouteUntil = now() + NO_ROUTE_TTL_MS else noRouteUntil = 0L
        return parsed
    }

    /** Phase 2: long-poll (server holds up to 20 s). [since] is the last status the caller saw. */
    suspend fun result(idToken: String, turnId: String, since: String): TurnStatus {
        val r = try {
            http.getSuspending(
                "$baseUrl/ask/voice/result?turn=$turnId&since=$since", idToken, RESULT_READ_TIMEOUT_MS,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return TurnStatus.Failed("network")
        }
        return withContext(Dispatchers.Default) { parseResult(r) }
    }

    /** Best-effort, fire-and-forget. Never throws and nothing waits for it. */
    fun cancel(idToken: String, turnId: String) {
        runCatching {
            http.postJsonDetached(
                "$baseUrl/ask/voice/cancel", idToken, JSONObject().put("turn_id", turnId).toString(),
            )
        }
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
        const val NO_ROUTE_TTL_MS = 10 * 60_000L
        /** Not below the shared client's own 35 s read timeout; the server holds a poll for 20 s. */
        const val RESULT_READ_TIMEOUT_MS = 35_000L

        private fun jsonOrNull(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

        private fun decodeWav(b64: String): ByteArray? =
            runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()?.takeIf { it.isNotEmpty() }

        fun parseStart(r: HttpResult): StartResult {
            if (r.code == 401) return StartResult.AuthExpired
            val json = jsonOrNull(r.body)
            if (r.code == 404 || r.code == 403) {
                // A gateway with no such route answers 403 "Missing Authentication Token" or a bare 404;
                // a real handler answers with a JSON {error}.
                if (json == null || !json.has("error")) return StartResult.NoRoute
                return StartResult.Error("HTTP ${r.code}: ${r.body}")
            }
            if (r.code !in 200..299) return StartResult.Error("HTTP ${r.code}: ${r.body}")
            if (json == null) return StartResult.Error("malformed response")
            if (json.has("error")) {
                return StartResult.StageError(
                    json.optString("error"),
                    if (json.has("transcript")) json.optString("transcript") else null,
                )
            }
            val turn = json.optString("turn_id")
            if (turn.isBlank()) return StartResult.Error("missing turn_id")
            val restate = json.optJSONObject("restatement")
            return StartResult.Started(
                turnId = turn,
                transcript = json.optString("transcript"),
                webIntent = json.optBoolean("web_intent", false),
                restatementWav = restate?.optString("audioBase64")?.takeIf { it.isNotBlank() }?.let(::decodeWav),
            )
        }

        fun parseResult(r: HttpResult): TurnStatus {
            if (r.code == 401) return TurnStatus.AuthExpired
            if (r.code == 404) return TurnStatus.NotFound
            if (r.code !in 200..299) return TurnStatus.Failed("HTTP ${r.code}")
            val json = jsonOrNull(r.body) ?: return TurnStatus.Failed("malformed response")
            return when (json.optString("status")) {
                "pending" -> TurnStatus.Pending
                "searching_web" -> TurnStatus.SearchingWeb
                "cancelled" -> TurnStatus.Cancelled
                "error" -> TurnStatus.Failed(json.optString("error", "error"))
                "done" -> {
                    val wav = decodeWav(json.optString("audioBase64"))
                        ?: return TurnStatus.Failed("missing audioBase64")
                    TurnStatus.Done(json.optString("transcript"), json.optString("answerText"), wav)
                }
                else -> TurnStatus.Failed("unknown status")
            }
        }

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
