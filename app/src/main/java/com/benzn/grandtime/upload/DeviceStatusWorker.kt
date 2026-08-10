package com.benzn.grandtime.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.settingsDataStore
import com.benzn.grandtime.capture.MicHealthStore
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.net.DeviceStatusClient
import com.benzn.grandtime.net.DeviceVitals

/**
 * Tells the ledger how far behind this device is, and hears back whether anything it froze
 * may be tried again.
 *
 * Runs rarely on purpose. There is no urgency in either direction: a frozen record is not
 * losing its budget while it waits (see [UploadAging]), and twenty devices at four calls a
 * day is nothing against org-api's reserved concurrency. The whole point of the channel is
 * that nobody has to watch it.
 *
 * Every failure here is silent and total — no upload changes, no row changes, nothing
 * surfaces. This is telemetry riding alongside the work, not part of it.
 */
class DeviceStatusWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GrandTimeApp
        val dao = CaptureDb.get(applicationContext).captureRecords()
        return try {
            // Restores the accurate session in a headless process, exactly as UploadWorker
            // does — otherwise a cold start reads LoggedOut and the thaw below would decline
            // to thaw anything, for the wrong reason.
            app.authManager.silentLogin()
            val idToken = app.authManager.freshIdToken() ?: return Result.retry()

            val now = System.currentTimeMillis()
            val oldest = dao.oldestUnsentStartedAt()
            val frozen = dao.listFrozen()
            // Read from disk, not memory: this worker routinely runs in a cold process hours
            // after the capture that produced the counters.
            val mic = MicHealthStore(applicationContext.settingsDataStore).read()
            val vitals = DeviceVitals(
                oldestPendingAgeS = oldest?.let { (now - it) / 1000 },
                pending = dao.countUnsent(),
                frozen = frozen.size,
                dead = dao.countDead(),
                fingerprints = dao.frozenFingerprints(),
                silentSecondsS = mic.silentSecondsS,
                longestSilentRunS = mic.longestSilentRunS,
                silentRunsWithMicBorrowed = mic.silentRunsWithMicBorrowed,
                lowestSessionPeak = mic.lowestSessionPeak,
            )

            val response = DeviceStatusClient(BuildConfig.ORG_API_BASE_URL).report(idToken, vitals)
                ?: return Result.retry()

            val settings = SettingsStore(applicationContext.settingsDataStore)
            response.serverBuild?.let { settings.setLastKnownServerBuild(it) }

            val currentAuthorSub = (AppState.loginState.value as? LoginState.LoggedIn)?.authorSub
            val enqueuer = WorkManagerUploadEnqueuer(applicationContext)
            for (record in frozen) {
                when {
                    ThawDecision.shouldThaw(record, currentAuthorSub, response.serverBuild, response.thaw) -> {
                        dao.thaw(record.id, UploadAging.creditOnThaw(record, now))
                        // replace = true: a request sitting in backoff counts as unfinished,
                        // and KEEP would drop this one — the reason the Retry button used to
                        // show its toast and do nothing at all.
                        enqueuer.enqueue(record.id, replace = true)
                    }
                    // Frozen before this device had ever heard a build. Adopt the current one
                    // WITHOUT thawing, or "build differs" is true on every probe forever.
                    ThawDecision.shouldAdoptBuild(record) ->
                        response.serverBuild?.let { dao.adoptFrozenBuild(record.id, it) }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "device_status"
        const val INTERVAL_HOURS = 6L
    }
}
