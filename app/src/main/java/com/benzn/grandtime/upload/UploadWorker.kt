package com.benzn.grandtime.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.capture.ChunkNaming
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

    /**
     * Caps how many uploads this process runs at once, process-wide.
     *
     * WorkManager does NOT bound this for us: CoroutineWorker launches on its own
     * [coroutineContext] and returns the WorkManager executor thread immediately, so the
     * pool size is not the limit — JobScheduler decides, and it happily starts ~20.
     *
     * Why it matters: a device coming back online releases its whole backlog at once. On
     * 2026-08-03 that meant a burst of ~520 API calls against an account whose Lambda
     * concurrency limit is 10; the device DDoSed its own backend, got 5XX for 88% of the
     * burst, and lost data it had successfully recorded. Two at a time still saturates a
     * site link but keeps each request inside its timeout and keeps the server reachable.
     */
    override val coroutineContext = UPLOAD_DISPATCHER

    /**
     * Retry WITHOUT consuming the permanent-failure budget in [doWork]'s attempt cap.
     *
     * [Result.retry] increments runAttemptCount, and at 8 the record is abandoned for good.
     * That budget exists to stop a genuinely broken upload retrying forever — but "the
     * server is busy" is not broken, and burning the budget on backpressure is how good
     * recordings got marked permanently failed while the backend was merely full. Instead,
     * hand the record to a FRESH work request (attempt count back to zero) on a long delay,
     * and let this instance end quietly.
     */
    private fun backOffWithoutSpendingBudget(): Result {
        val recordId = inputData.getString("recordId") ?: return Result.failure()
        WorkManagerUploadEnqueuer(applicationContext).enqueue(
            recordId,
            initialDelaySeconds = BUSY_BACKOFF_SECONDS,
            replace = true,
        )
        return Result.success()      // superseded, not failed — the new request owns it now
    }

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
                // Wire name carries the session/chunk tokens the backend groups on; local file name
                // and audio segmentation are untouched. Falls back to record.fileName for photos /
                // legacy rows without a valid session id (ChunkNaming.wireFileName).
                fileName = ChunkNaming.wireFileName(record.fileName, record.sessionId, record.segmentIndex),
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
                is RecordingsApiClient.UploadUrlResult.Busy -> {
                    dao.markUploadStatus(recordId, "failed")
                    backOffWithoutSpendingBudget()
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
                    // #5: a failed PUT does NOT mean S3 rejected the bytes. OK_HTTP caps the
                    // whole call at 60s, so a large chunk on a congested link can be fully
                    // received by S3 and still surface here as a failure — the client simply
                    // stopped waiting for the response. That is exactly how 69 of one device's
                    // recordings ended up present in S3 with their row never completed on
                    // 2026-08-03. So try `complete` anyway: it is idempotent, and if the
                    // object really is there this finishes the upload instead of re-sending
                    // the whole file. If the object is absent the backend just stays pending
                    // and we retry the PUT on the next attempt.
                    // Return value deliberately ignored — `complete` below is the real
                    // verdict on whether the object made it (see the comment above).
                    client.putFile(urlResult.uploadUrl, contentType, file)
                    val status = client.completeStatus(
                        idToken, urlResult.recordingId, file.length(), gpsTrack = record.gpsTrack)
                    when {
                        status in 200..299 -> {
                            dao.markUploadStatus(recordId, "uploaded")
                            Result.success()
                        }
                        // #4: complete's outcome must be honored — marking "uploaded" without
                        // it orphans the recording (file in S3, backend row pending forever).
                        RecordingsApiClient.isTransient(status) -> {
                            dao.markUploadStatus(recordId, "failed")
                            backOffWithoutSpendingBudget()
                        }
                        else -> {
                            // A real 4xx from complete (or a PUT that genuinely never landed).
                            // Retry within the normal budget; #2's cap eventually gives up.
                            dao.markUploadStatus(recordId, "failed")
                            Result.retry()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            dao.markUploadStatus(recordId, "failed")
            Result.retry()
        }
    }
}

/** Concurrent uploads per process. See [UploadWorker.coroutineContext]. */
internal const val MAX_CONCURRENT_UPLOADS = 2

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val UPLOAD_DISPATCHER =
    kotlinx.coroutines.Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_UPLOADS)

/**
 * How long to stand down when the server signals backpressure. Long enough that a backlog
 * does not simply re-storm the same full queue, short enough that an unattended device still
 * drains the same evening.
 */
internal const val BUSY_BACKOFF_SECONDS = 300L

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
