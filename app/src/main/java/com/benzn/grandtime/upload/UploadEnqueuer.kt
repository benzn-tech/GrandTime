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

/** Enqueues an upload for a finalized capture_records row. Injectable so CaptureManager stays testable. */
interface UploadEnqueuer {
    /**
     * [initialDelaySeconds] > 0 defers the work's earliest run (hardening #85 startup-sweep
     * throttle) so a backlog of pending/failed uploads doesn't congest the network right when
     * the user is interacting in the foreground. Real-time captures pass 0 (immediate).
     */
    fun enqueue(recordId: String, initialDelaySeconds: Long = 0)
}

/**
 * Real WorkManager-backed enqueuer (SP4b). Unique work per recordId so duplicate enqueues
 * (e.g. a stray retry hook) coalesce instead of running the upload twice.
 */
class WorkManagerUploadEnqueuer(private val context: Context) : UploadEnqueuer {
    override fun enqueue(recordId: String, initialDelaySeconds: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val builder = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("recordId" to recordId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (initialDelaySeconds > 0) {
            builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
        }
        WorkManager.getInstance(context)
            .enqueueUniqueWork("upload_$recordId", ExistingWorkPolicy.KEEP, builder.build())
    }
}
