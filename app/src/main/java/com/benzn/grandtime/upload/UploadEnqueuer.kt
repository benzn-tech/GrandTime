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
    fun enqueue(recordId: String)
}

/**
 * Real WorkManager-backed enqueuer (SP4b). Unique work per recordId so duplicate enqueues
 * (e.g. a stray retry hook) coalesce instead of running the upload twice.
 */
class WorkManagerUploadEnqueuer(private val context: Context) : UploadEnqueuer {
    override fun enqueue(recordId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("recordId" to recordId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("upload_$recordId", ExistingWorkPolicy.KEEP, request)
    }
}
