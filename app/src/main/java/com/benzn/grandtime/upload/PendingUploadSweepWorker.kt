package com.benzn.grandtime.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.settingsDataStore
import com.benzn.grandtime.db.CaptureDb
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Puts stalled uploads back on the queue, on a timer.
 *
 * The same sweep already ran, but only when CoreService STARTED. So a chunk that
 * failed during a meeting stayed failed until the app was restarted or someone
 * found the "Retry failed" button -- and the person who most needs it is the one
 * who assumed the signal was fine, walked away, and has no reason to look. This
 * runs whether or not the app is open, so recovery does not depend on anybody
 * noticing.
 *
 * It re-enqueues rather than uploading: WorkManager's unique-work name per record
 * means an enqueue for something already in flight coalesces instead of
 * duplicating, so this is safe to run while uploads are happening.
 *
 * Ordering is deliberately NOT imposed. The backend sorts by the chunk index in
 * the filename and never by arrival (chunk_stitch orders "by index here, never by
 * arrival"), and every chunk is transcribed independently. Uploading in sequence
 * would only add head-of-line blocking: one bad chunk would hold up every chunk
 * behind it, which is the failure this worker exists to prevent.
 */
class PendingUploadSweepWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // No signed-in account means no correct owner to attribute an upload
            // to. Uploading someone else's recording under this account is worse
            // than leaving it queued.
            val author = (AppState.loginState.value as? LoginState.LoggedIn)?.authorSub
                ?: return Result.success()

            val dao = CaptureDb.get(applicationContext).captureRecords()
            val wifiOnly = SettingsStore(applicationContext.settingsDataStore)
                .settings.first().videoUploadWifiOnly
            val enq = WorkManagerUploadEnqueuer(applicationContext)

            // "frozen" and "dead" are absent on purpose, matching the startup
            // sweep: a frozen record waits on a fix only the office can make, and
            // sweeping it back would restart the very retry loop the freeze exists
            // to stop -- here, every fifteen minutes, forever.
            val pending = dao.listPendingForAuthor(
                listOf("pending", "failed", "uploading", "retrying"), author,
            )
            var requeued = 0
            for (rec in pending.sortedByDescending { it.startedAt }.take(SWEEP_CAP)) {
                if (!File(rec.filePath).exists()) {
                    dao.markMissing(listOf(rec.id))
                    continue
                }
                enq.enqueue(
                    rec.id,
                    initialDelaySeconds = 0,
                    requireUnmetered = uploadRequiresUnmetered(rec.kind, wifiOnly),
                )
                requeued++
            }
            Result.success()
        } catch (t: Throwable) {
            // Retry rather than fail: this is a safety net, and a net that gives
            // up the first time it snags is not one.
            Result.retry()
        }
    }

    companion object {
        private const val SWEEP_CAP = 400
        private const val UNIQUE_NAME = "pending_upload_sweep"

        /**
         * 15 minutes is WorkManager's floor for periodic work, not a chosen
         * number. It is also comfortably shorter than the backend's 15-minute
         * idle inference, so a chunk recovered here still lands before the
         * session is written off.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PendingUploadSweepWorker>(
                15, TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP: re-scheduling on every service start would reset the
                // period each time, so on a device that restarts the service
                // often the sweep would never actually come due.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
