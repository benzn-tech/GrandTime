package com.benzn.grandtime.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Queues the deliberate end-of-session signal. Injectable so CaptureManager stays testable. */
interface SessionCloseEnqueuer {
    fun enqueue(sessionId: String, endedAtMillis: Long)
}

class WorkManagerSessionCloseEnqueuer(private val context: Context) : SessionCloseEnqueuer {
    override fun enqueue(sessionId: String, endedAtMillis: Long) {
        val request = OneTimeWorkRequestBuilder<SessionCloseWorker>()
            .setInputData(
                workDataOf(
                    SessionCloseWorker.KEY_SESSION_ID to sessionId,
                    SessionCloseWorker.KEY_ENDED_AT to endedAtMillis,
                )
            )
            // CONNECTED, never UNMETERED. On a site with no WiFi, UNMETERED does not
            // mean "later" -- the constraint is simply never satisfied and the signal
            // waits forever, which is the same defect that kept video chunks from
            // uploading at all. This request is a few hundred bytes.
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SessionCloseWorker.uniqueName(sessionId),
            // KEEP, not REPLACE: a second End for the same session is the same
            // message. REPLACE would restart the backoff, so a user tapping End
            // twice would push the email further away rather than nearer.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
