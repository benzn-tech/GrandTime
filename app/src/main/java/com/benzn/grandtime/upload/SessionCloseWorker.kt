package com.benzn.grandtime.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.net.SessionsApiClient

/**
 * Tells the backend the meeting is over, durably.
 *
 * Why this is a worker and not a call. `session_close(intent="end")` finalizes
 * with grace 0, so when it lands the confirmation email goes out in about a
 * minute -- measured end to end on 2026-08-09: last chunk 00:07:40, email sent
 * 00:08:34. When it does NOT land, nothing reports it and the session waits the
 * full 15-minute idle inference. Over ten days the signal arrived 8 times out of
 * 28: the old call was fire-and-forget inside a `runCatching`, and its client
 * ended in `.getOrElse { false }`, so a single blip lost it with no retry and no
 * log. WorkManager gives the same delivery guarantee the uploads already have --
 * persisted across process death and reboot, `CONNECTED` means "wait", not
 * "fail", and backoff retries until it lands.
 *
 * Why it waits for the uploads. This REPLACES the immediate call rather than
 * joining it, because sending at the moment of stop is only safe while it keeps
 * failing. grace 0 means the backend finalizes at once, so a close that arrives
 * before the last chunks do produces an email covering a transcript that has not
 * finished arriving -- short, and unrecoverable, since a `finalizing` session is
 * not resumed by a later chunk. The device is the only party that knows how many
 * chunks it made, and it already has that in its own table, so it is asked here
 * rather than reconstructed on the server.
 *
 * The 15-minute idle inference stays exactly as it is. Everything below can fail
 * -- an uninstalled app, a lost device, a week with no network -- and the result
 * is the behaviour we have today, never worse.
 */
class SessionCloseWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val endedAt = inputData.getLong(KEY_ENDED_AT, 0L)
        if (endedAt <= 0L) return Result.failure()

        val app = applicationContext as GrandTimeApp
        return try {
            val dao = CaptureDb.get(applicationContext).captureRecords()
            val inFlight = dao.countInFlightForSession(sessionId)
            if (inFlight > 0) {
                // Not an error: the meeting is over but its audio is not up yet.
                // Retry rather than send, so the email covers the whole session.
                return Result.retry()
            }

            val token = app.authManager.freshIdToken()
                // Retry, NOT failure: a token that cannot be refreshed right now
                // usually can be later, and giving up here is exactly how the old
                // fire-and-forget call lost the signal silently.
                ?: return Result.retry()

            val ok = SessionsApiClient(BuildConfig.ORG_API_BASE_URL)
                .close(token, sessionId, endedAt, "end")
            if (ok) Result.success() else Result.retry()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_ENDED_AT = "endedAt"
        /** One per session; a second stop for the same session coalesces. */
        fun uniqueName(sessionId: String) = "close_$sessionId"
    }
}
