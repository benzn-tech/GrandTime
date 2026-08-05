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

    override suspend fun doWork(): Result {
        val recordId = inputData.getString("recordId") ?: return Result.failure()
        val app = applicationContext as GrandTimeApp
        val dao = CaptureDb.get(applicationContext).captureRecords()
        return try {
            val record = dao.getById(recordId) ?: return Result.success()
            // Before the age check, not after: an already-uploaded record is DONE, and a
            // stray re-enqueue of an old one must not be re-labelled "failed".
            if (record.uploadStatus == "uploaded") return Result.success()

            // #2: OneTimeWorkRequest has no built-in attempt cap, so something must stop a
            // genuinely doomed upload retrying forever. This used to be a count (8 attempts),
            // which conflated two very different situations: eight attempts against a broken
            // request means broken, but eight attempts against a busy backend means the
            // backend was busy eight times — and on 2026-08-03 that spent a good recording's
            // entire budget while the server was merely full, abandoning it for good.
            //
            // Age is the honest bound. A recording that still cannot upload a week after it
            // was made is dead however many attempts that took, and a backend outage of any
            // plausible length no longer costs us data. WorkManager's own exponential backoff
            // caps at 5h, so a doomed record costs on the order of tens of requests over that
            // week, not one every few minutes.
            if (System.currentTimeMillis() - record.startedAt > GIVE_UP_AFTER_MS) {
                dao.markUploadStatus(recordId, "failed")
                return Result.failure()
            }

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
                // From the row, not from live state: this worker can run long
                // after the recording, and the group it belonged to may since
                // have ended. The row is what remembers.
                groupId = record.groupId,
            )

            when (val urlResult = client.uploadUrl(idToken, req)) {
                is RecordingsApiClient.UploadUrlResult.AuthExpired -> {
                    // freshIdToken already handled a truly-dead session; a lone 401 here is
                    // worth a retry after the token refreshes again.
                    dao.markUploadStatus(recordId, "failed")
                    Result.retry()
                }
                is RecordingsApiClient.UploadUrlResult.Busy -> {
                    // Retry on WorkManager's own exponential backoff. Safe to do freely
                    // now that the give-up rule is age-based: a busy backend no longer
                    // eats a budget that a broken request should have spent.
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
                    // #5: a failed PUT does NOT mean S3 rejected the bytes. Any client-side
                    // give-up (write stall, socket reset, process pressure) can happen after
                    // S3 has already received the body — S3 keeps what it got, we just never
                    // saw the response. That is exactly how 69 of one device's
                    // recordings ended up present in S3 with their row never completed on
                    // 2026-08-03. So try `complete` anyway: it is idempotent, and if the
                    // object really is there this finishes the upload instead of re-sending
                    // the whole file. If the object is absent the backend just stays pending
                    // and we retry the PUT on the next attempt.
                    // Return value deliberately ignored — `complete` below is the real
                    // verdict on whether the object made it (see the comment above).
                    client.putFile(urlResult.uploadUrl, contentType, file)
                    val completed = client.completeStatus(
                        idToken, urlResult.recordingId, file.length(), gpsTrack = record.gpsTrack)
                    val status = completed.code
                    // Multi-device merge: the upload response is the only way the
                    // server can reach a device that is not holding a connection
                    // open. Handled by CaptureManager, which owns the recorder.
                    if (completed.groupEnded) AppState.meetingEndedElsewhere.tryEmit(Unit)
                    when {
                        status in 200..299 -> {
                            dao.markUploadStatus(recordId, "uploaded")
                            Result.success()
                        }
                        // #4: complete's outcome must be honored — marking "uploaded" without
                        // it orphans the recording (file in S3, backend row pending forever).
                        RecordingsApiClient.isTransient(status) -> {
                            dao.markUploadStatus(recordId, "failed")
                            Result.retry()
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
 * How long an un-uploaded recording stays worth retrying. See [UploadWorker.doWork]'s #2
 * for why the bound is age rather than an attempt count.
 */
internal const val GIVE_UP_AFTER_MS = 7L * 24 * 60 * 60 * 1000

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
