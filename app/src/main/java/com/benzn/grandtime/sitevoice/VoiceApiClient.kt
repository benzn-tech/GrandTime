package com.benzn.grandtime.sitevoice

import com.benzn.grandtime.auth.HttpResult
import com.benzn.grandtime.net.HttpFns
import com.benzn.grandtime.net.RealHttp
import com.benzn.grandtime.net.RealSitesHttp
import com.benzn.grandtime.net.SitesHttpFns
import java.io.File
import java.net.URLEncoder
import org.json.JSONObject

data class VoiceUploadReq(
    val siteId: String,
    val fileName: String,
    val contentType: String,
    val startedAt: String,
    val durationS: Int,
    val sizeBytes: Long,
)

/**
 * REST client for the dedicated Site-voice endpoints. Deliberately NOT RecordingsApiClient — voice
 * is off-the-record (its own voice/ prefix + voice_messages table, never recordings). Mirrors the
 * RecordingsApiClient/SitesApiClient testability seam: POST/PUT via [HttpFns], GET via [SitesHttpFns];
 * pure parse fns are TDD-covered, the OkHttp path is device-verified. baseUrl already ends in
 * `/prod/api`, so these hit the `/api/org/voice` and `/api/org/sites/{id}/voice` endpoints.
 */
class VoiceApiClient(
    private val baseUrl: String,
    private val http: HttpFns = RealHttp(),
    private val getHttp: SitesHttpFns = RealSitesHttp(),
) {
    sealed interface UploadUrlResult {
        data class Ok(val uploadUrl: String, val s3Key: String) : UploadUrlResult
        data object AuthExpired : UploadUrlResult
        data class Error(val message: String) : UploadUrlResult
    }

    data class BackfillItem(val s3Key: String, val senderUserId: String, val createdAt: String, val durationS: Int?)

    fun uploadUrl(idToken: String, req: VoiceUploadReq): UploadUrlResult {
        val body = JSONObject()
            .put("siteId", req.siteId)
            .put("fileName", req.fileName)
            .put("contentType", req.contentType)
            .put("startedAt", req.startedAt)
            .put("durationS", req.durationS)
            .put("sizeBytes", req.sizeBytes)
        val result = runCatching { http.postJson("$baseUrl/org/voice/upload-url", idToken, body.toString()) }
            .getOrElse { return UploadUrlResult.Error("network") }
        return parseUploadUrl(result)
    }

    fun putFile(uploadUrl: String, contentType: String, file: File): Boolean {
        val code = runCatching { http.putFile(uploadUrl, contentType, file) }.getOrElse { return false }
        return code in 200..299
    }

    fun downloadUrl(idToken: String, s3Key: String): String? {
        val q = URLEncoder.encode(s3Key, "UTF-8")
        val result = runCatching { getHttp.getJson("$baseUrl/org/voice/asset-url?key=$q", idToken) }
            .getOrElse { return null }
        return parseDownloadUrl(result)
    }

    fun backfill(idToken: String, siteId: String, sinceIso: String?): List<BackfillItem> {
        val url = if (sinceIso != null) {
            "$baseUrl/org/sites/$siteId/voice?since=${URLEncoder.encode(sinceIso, "UTF-8")}"
        } else {
            "$baseUrl/org/sites/$siteId/voice"
        }
        val result = runCatching { getHttp.getJson(url, idToken) }.getOrElse { return emptyList() }
        return parseBackfill(result)
    }

    companion object {
        fun parseUploadUrl(r: HttpResult): UploadUrlResult {
            if (r.code == 401) return UploadUrlResult.AuthExpired
            return runCatching {
                if (r.code !in 200..299) return@runCatching UploadUrlResult.Error("HTTP ${r.code}: ${r.body}")
                val json = JSONObject(r.body)
                val uploadUrl = json.optString("uploadUrl")
                val s3Key = json.optString("s3Key")
                if (uploadUrl.isBlank() || s3Key.isBlank())
                    return@runCatching UploadUrlResult.Error("missing uploadUrl/s3Key")
                UploadUrlResult.Ok(uploadUrl = uploadUrl, s3Key = s3Key)
            }.getOrElse { UploadUrlResult.Error("malformed response") }
        }

        fun parseDownloadUrl(r: HttpResult): String? {
            if (r.code !in 200..299) return null
            return runCatching { JSONObject(r.body).optString("url").takeIf { it.isNotBlank() } }.getOrNull()
        }

        fun parseBackfill(r: HttpResult): List<BackfillItem> {
            if (r.code !in 200..299) return emptyList()
            return runCatching {
                val arr = JSONObject(r.body).optJSONArray("items") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val key = o.optString("s3Key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    BackfillItem(
                        s3Key = key,
                        senderUserId = o.optString("senderUserId"),
                        createdAt = o.optString("createdAt"),
                        // absent -> null; JSON null (backend's list_site_voice emits durationS:null
                        // for unknown-duration rows) -> null; present int -> the int. o.has() alone
                        // is TRUE for a JSON null, so it must be paired with !o.isNull() (same fix
                        // as WsMessages.parseInbound's durationS).
                        durationS = if (o.has("durationS") && !o.isNull("durationS")) o.optInt("durationS") else null,
                    )
                }
            }.getOrElse { emptyList() }
        }
    }
}
