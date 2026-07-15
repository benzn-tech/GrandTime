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
        OK_HTTP.newCall(req).execute().use { resp ->
            return resp.code
        }
    }

    companion object {
        private val OK_HTTP = OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
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
            .getOrElse { return UploadUrlResult.Error("network") }
        return parseUploadUrl(result)
    }

    fun putFile(uploadUrl: String, contentType: String, file: File): Boolean {
        val code = runCatching { http.putFile(uploadUrl, contentType, file) }.getOrElse { return false }
        return code in 200..299
    }

    fun complete(idToken: String, recordingId: String, sizeBytes: Long?, gpsTrack: String? = null): Boolean {
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
        }.getOrElse { return false }
        return result.code in 200..299
    }

    companion object {
        fun parseUploadUrl(r: HttpResult): UploadUrlResult {
            if (r.code == 401) return UploadUrlResult.AuthExpired
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
