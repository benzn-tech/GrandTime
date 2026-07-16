package com.benzn.grandtime.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.db.CaptureRecord
import com.benzn.grandtime.net.RecordingsApiClient
import com.benzn.grandtime.net.UploadUrlReq
import java.io.File
import java.time.Instant

/**
 * SP4b upload pipeline: freshIdToken -> upload-url -> PUT to S3 -> complete -> mark uploaded.
 * Enqueued by [WorkManagerUploadEnqueuer] right after a capture_records row is finalized.
 * Not JVM-unit-testable (real WorkManager/CoroutineWorker + Android context); verified on-device (T9).
 */
class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getString("recordId") ?: return Result.failure()
        val app = applicationContext as GrandTimeApp
        val dao = CaptureDb.get(applicationContext).captureRecords()
        return try {
            // #2: OneTimeWorkRequest has no built-in attempt cap — without this, a permanent
            // (non-401) uploadUrl error would retry forever. Cap at 8 (~exponential backoff
            // 30s..5h) and give up for good past that.
            if (runAttemptCount >= 8) {
                dao.markUploadStatus(recordId, "failed")
                return Result.failure()
            }

            val record = dao.getById(recordId) ?: return Result.success()
            if (record.uploadStatus == "uploaded") return Result.success()

            // #1: a fresh/headless worker process (reboot / process-death wakeup) starts with
            // AppState.loginState defaulted to LoggedOut. silentLogin() restores the accurate
            // state from the persisted session BEFORE we read the token, so a transient network
            // failure in freshIdToken() below is never misread as "session dead" (which would
            // otherwise permanently drop this upload). It also refreshes idTokenCache every
            // attempt, so a stale rejected token can't be reused on retry (#3).
            app.authManager.silentLogin()
            val idToken = app.authManager.freshIdToken()
                ?: return if (AppState.loginState.value is LoginState.LoggedOut) {
                    // Session is truly dead (silentLogin/freshIdToken already logged us out) —
                    // user must re-login before this can ever succeed; don't retry forever.
                    dao.markUploadStatus(recordId, "failed")
                    Result.failure()
                } else {
                    // Transient (network) failure while refreshing — worth another attempt.
                    Result.retry()
                }

            dao.markUploadStatus(recordId, "uploading")

            val client = RecordingsApiClient(BuildConfig.ORG_API_BASE_URL)
            val contentType = contentTypeFor(record)
            val req = UploadUrlReq(
                kind = uploadKind(record.kind),
                clientUuid = record.id,
                siteId = record.siteId,
                fileName = record.fileName,
                contentType = contentType,
                startedAt = iso8601(record.startedAt),
                endedAt = record.endedAt?.let { iso8601(it) },
                durationS = record.durationMs?.let { it / 1000L },
                sizeBytes = record.sizeBytes.takeIf { it > 0 },
                resolution = record.resolution,
                codec = record.codec,
            )

            when (val urlResult = client.uploadUrl(idToken, req)) {
                is RecordingsApiClient.UploadUrlResult.AuthExpired -> {
                    // freshIdToken already handled a truly-dead session; a lone 401 here is
                    // worth a retry after the token refreshes again.
                    dao.markUploadStatus(recordId, "failed")
                    Result.retry()
                }
                is RecordingsApiClient.UploadUrlResult.Error -> {
                    dao.markUploadStatus(recordId, "failed")
                    Result.retry()
                }
                is RecordingsApiClient.UploadUrlResult.Ok -> {
                    val file = File(record.filePath)
                    if (!file.exists()) {
                        // 文件已删:标 missing=1,排除出开机补扫(否则每次开机重扫→churn)。
                        dao.markUploadStatus(recordId, "failed")
                        dao.markMissing(listOf(recordId))
                        return Result.failure()
                    }
                    val put = client.putFile(urlResult.uploadUrl, contentType, file)
                    if (!put) {
                        dao.markUploadStatus(recordId, "failed")
                        return Result.retry()
                    }
                    // #4: complete()'s bool must be honored — if the backend never finalizes the
                    // recording row, marking "uploaded" here would orphan it (file sits in S3,
                    // backend row stays pending, and nothing ever retries). The whole flow is
                    // idempotent (upload-url on clientUuid, re-PUT overwrites the same S3 key,
                    // complete is idempotent), so retrying complete is safe; bounded by #2's cap.
                    val done = client.complete(idToken, urlResult.recordingId, file.length(), gpsTrack = record.gpsTrack)
                    if (!done) {
                        dao.markUploadStatus(recordId, "failed")
                        return Result.retry()
                    }
                    dao.markUploadStatus(recordId, "uploaded")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            dao.markUploadStatus(recordId, "failed")
            Result.retry()
        }
    }
}

/** Backend `kind` must be one of video/audio/photo; frame-grab rows are photos taken mid-recording. */
internal fun uploadKind(kind: String): String = if (kind == "frame-grab") "photo" else kind

internal fun contentTypeFor(record: CaptureRecord): String = when (record.kind) {
    "video" -> "video/mp4"
    "audio" -> if (record.fileName.substringAfterLast('.', "").lowercase() == "wav") "audio/wav" else "audio/mp4"
    "photo", "frame-grab" -> "image/jpeg"
    else -> "application/octet-stream"
}

// NZ-local ISO so the server derives the date folder (startedAt[:10]) in NZ, not UTC — a UTC
// evening is already the next NZ day; a UTC-based folder was off by one (G3).
internal fun iso8601(epochMillis: Long): String =
    java.time.OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(epochMillis),
        java.time.ZoneId.of("Pacific/Auckland"),
    ).toString()
