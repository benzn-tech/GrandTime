package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Injectable HTTP shim so tests can fake network calls without hitting OkHttp/Android stubs.
 * Real path is [RealHttp] (OkHttp); tests inject a fake implementation.
 */
interface HttpFns {
    fun postJson(url: String, authToken: String, jsonBody: String): HttpResult
    fun putFile(url: String, contentType: String, file: File): Int
}

/** Real OkHttp-backed implementation of [HttpFns]. Not unit-tested (verified on-device). */
class RealHttp : HttpFns {
    override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
        val req = Request.Builder().url(url)
            .header("Authorization", authToken)
            .header("Content-Type", "application/json")
            .apply { attachDeviceHeaders(this) }
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        OK_HTTP.newCall(req).execute().use { resp ->
            return HttpResult(resp.code, resp.body?.string().orEmpty())
        }
    }

    override fun putFile(url: String, contentType: String, file: File): Int {
        val req = Request.Builder().url(url)
            .header("Content-Type", contentType)
            .put(file.asRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        UPLOAD_HTTP.newCall(req).execute().use { resp ->
            return resp.code
        }
    }

    companion object {
        // OkHttp's per-operation read/write timeouts default to 10s. The SP-Ask voice
        // endpoint does STT + RAG + realtime TTS server-side before responding (well over
        // 10s), so a 10s read timeout severed the call with a spurious network error; raise
        // read/write above APIGW's 29s ceiling.
        private val OK_HTTP = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * Body uploads to S3 get NO overall call deadline — only per-operation ones.
         *
         * `callTimeout` is a wall clock over the entire call, so it scales with FILE SIZE,
         * not with health: a 40 MB video on a site link needs minutes of perfectly healthy
         * transfer and would be cut off at 60s with the bytes already in flight. That is not
         * hypothetical — S3 keeps whatever it received, so the object lands while the client
         * reports failure, which is how uploads ended up present in S3 with their row never
         * completed. (The 60s cap was written when this client only spoke to API Gateway; the
         * later comment claiming writeTimeout "also protects large S3 uploads" had it
         * backwards — writeTimeout is the protection, callTimeout is the hazard.)
         *
         * Stalls are still caught: writeTimeout fires when no progress is made on a single
         * socket write for 35s, which is the condition we actually want to abort on.
         */
        private val UPLOAD_HTTP = OK_HTTP.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)   // 0 = no overall deadline
            .build()
    }
}

data class UploadUrlReq(
    val kind: String,
    val clientUuid: String,
    val siteId: String? = null,
    val fileName: String,
    val contentType: String,
    val startedAt: String,
    val endedAt: String? = null,
    val durationS: Long? = null,
    val sizeBytes: Long? = null,
    val resolution: String? = null,
    val codec: String? = null,
)

/**
 * HTTP client for the FieldSight org recordings endpoints (SP4a contract). Networking is
 * transcribed against OkHttp and verified on-device; the pure JSON parsing (parseUploadUrl) is
 * TDD-covered here. `http` mirrors CognitoClient's testability pattern — tests inject a fake.
 */
class RecordingsApiClient(
    private val baseUrl: String,
    private val http: HttpFns = RealHttp(),
) {
    sealed interface UploadUrlResult {
        data class Ok(val recordingId: String, val uploadUrl: String, val s3Key: String) : UploadUrlResult
        data object AuthExpired : UploadUrlResult
        /**
         * Server-side backpressure or a transient network failure — "try again later", NOT
         * "this upload is broken". Kept distinct from [Error] because the two deserve
         * different retry budgets: on 2026-08-03 the account-wide Lambda concurrency limit
         * turned a reconnecting device's backlog into 1547 throttles, API Gateway answered
         * 5XX, and the worker spent its whole 8-attempt allowance on a queue that was simply
         * full. Treating that as a permanent failure is how uploads got abandoned while the
         * server was merely busy.
         */
        data class Busy(val code: Int) : UploadUrlResult
        data class Error(val message: String) : UploadUrlResult
    }

    fun uploadUrl(idToken: String, req: UploadUrlReq): UploadUrlResult {
        val body = JSONObject()
            .put("kind", req.kind)
            .put("clientUuid", req.clientUuid)
            .put("fileName", req.fileName)
            .put("contentType", req.contentType)
            .put("startedAt", req.startedAt)
        req.siteId?.let { body.put("siteId", it) }
        req.endedAt?.let { body.put("endedAt", it) }
        req.durationS?.let { body.put("durationS", it) }
        req.sizeBytes?.let { body.put("sizeBytes", it) }
        req.resolution?.let { body.put("resolution", it) }
        req.codec?.let { body.put("codec", it) }

        val result = runCatching { http.postJson("$baseUrl/org/recordings/upload-url", idToken, body.toString()) }
            .getOrElse { return UploadUrlResult.Busy(0) }   // no response at all -> transient
        return parseUploadUrl(result)
    }

    fun putFile(uploadUrl: String, contentType: String, file: File): Boolean {
        val code = runCatching { http.putFile(uploadUrl, contentType, file) }.getOrElse { return false }
        return code in 200..299
    }

    /**
     * @return the HTTP status, or [NO_RESPONSE] when the call never produced one. Callers
     * need the code, not just a boolean: a `complete` lost to server backpressure must be
     * retried without spending the record's permanent-failure budget, whereas a 4xx means
     * this request will never succeed as-is.
     */
    fun completeStatus(idToken: String, recordingId: String, sizeBytes: Long?, gpsTrack: String? = null): Int {
        val body = JSONObject()
        sizeBytes?.let { body.put("sizeBytes", it) }
        // T13: gpsTrack 是 DB 里存的 JSON 数组字符串(GpsTracker.snapshotTrackJson),这里还原成
        // JSONArray 塞进 body,后端才能拿到真正的 list,而不是一个二次转义的字符串。
        // 脏数据防御:行里字符串万一不是合法 JSON(损坏),parse 失败就整体丢弃该字段,
        // 不能让 complete() 因此失败——降级为"不带轨迹地 complete"。
        gpsTrack?.let { track ->
            runCatching { org.json.JSONArray(track) }.getOrNull()?.let { body.put("gpsTrack", it) }
        }
        val result = runCatching {
            http.postJson("$baseUrl/org/recordings/$recordingId/complete", idToken, body.toString())
        }.getOrElse { return NO_RESPONSE }
        return result.code
    }

    companion object {
        /** No HTTP status was ever obtained (socket/DNS/timeout). Transient by definition. */
        const val NO_RESPONSE = 0

        /**
         * "The server is busy / we never got through", as opposed to "this request is wrong".
         * 429 is an explicit rate limit; 5xx behind API Gateway is what a throttled Lambda
         * surfaces as (the invocation never runs, so it isn't a real server error either).
         */
        fun isTransient(code: Int): Boolean = code == NO_RESPONSE || code == 429 || code in 500..599

        fun parseUploadUrl(r: HttpResult): UploadUrlResult {
            if (r.code == 401) return UploadUrlResult.AuthExpired
            if (isTransient(r.code)) return UploadUrlResult.Busy(r.code)
            return runCatching {
                if (r.code !in 200..299) return@runCatching UploadUrlResult.Error("HTTP ${r.code}: ${r.body}")
                val json = JSONObject(r.body)
                val recordingId = json.optString("recordingId")
                if (recordingId.isBlank()) return@runCatching UploadUrlResult.Error("missing recordingId")
                UploadUrlResult.Ok(
                    recordingId = recordingId,
                    uploadUrl = json.optString("uploadUrl"),
                    s3Key = json.optString("s3Key"),
                )
            }.getOrElse { UploadUrlResult.Error("malformed response") }
        }
    }
}
