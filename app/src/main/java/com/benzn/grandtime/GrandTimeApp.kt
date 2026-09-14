package com.benzn.grandtime

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.benzn.grandtime.auth.CognitoAuthManager
import com.benzn.grandtime.auth.CognitoClient
import com.benzn.grandtime.auth.EncryptedTokenStore
import com.benzn.grandtime.capture.MediaStorage
import com.benzn.grandtime.capture.StorageReclaimer
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.SiteStore
import com.benzn.grandtime.core.siteDataStore
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.device.DeviceIdentity
import com.benzn.grandtime.upload.DeviceStatusWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.SupervisorJob

class GrandTimeApp : Application(), ImageLoaderFactory, Configuration.Provider {
    val authManager: CognitoAuthManager by lazy {
        CognitoAuthManager(
            client = CognitoClient(BuildConfig.COGNITO_CLIENT_ID, BuildConfig.COGNITO_REGION),
            tokenStore = EncryptedTokenStore(this),
            dao = CaptureDb.get(this).captureRecords(),
            publicRoot = { MediaStorage.publicRoot(this) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // The next person to sign in must not inherit this account's site, nor see its site
            // list in the picker's disk cache. Both are per-account facts on a shared device.
            onSignedOut = {
                val sites = SiteStore(this@GrandTimeApp.siteDataStore)
                sites.set(null)
                sites.setSiteList(emptyList())
                AppState.availableSites.value = emptyList()
            },
        )
    }

    /**
     * Process-lifetime scope for fire-and-forget persistence that must outlive a screen or dialog.
     * The site picker writes the selected site to DataStore here instead of a composition
     * `rememberCoroutineScope`, which onDismiss() would cancel mid-write (dropping the selection).
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * WorkManager is initialised on demand from here, not by androidx.startup -- see the provider
     * entry in AndroidManifest.xml. Auto-initialisation opened its database before onCreate, which
     * on a full disk crashed the process before anything of ours could give space back.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // Resolve the device's own identity before anything can make a request.
        // Until this runs, DeviceIdentity reports nothing and the ledger reads
        // the device as never-seen, which is true rather than wrong.
        DeviceIdentity.init(this)

        // Before anything opens a database: on a partition filled to 0 bytes, SQLite cannot even
        // set its journal mode, and the first thing to try -- WorkManager, the capture database --
        // throws and kills the process. This gives back just enough to open them, using nothing
        // that needs a database to decide. Recordings on another volume are left alone; deleting
        // them would free nothing the databases can use.
        runCatching {
            StorageReclaimer(
                mediaRoot = { MediaStorage.fieldSightRoot(this) },
                recordingFreeBytes = { MediaStorage.publicRoot(this).usableSpace },
                databaseFreeBytes = { filesDir.usableSpace },
                sameVolume = { filesDir.totalSpace == MediaStorage.publicRoot(this).totalSpace },
                log = { Log.w(TAG, it) },
            ).emergency()
        }.onFailure { Log.w(TAG, "emergency storage reclaim failed", it) }

        // The backlog channel. Rare on purpose: a frozen record is not losing its retry
        // budget while it waits, so nothing here is urgent — the value is that a device
        // falling behind stops being invisible, not that it is noticed within the minute.
        //
        // KEEP, unlike the upload queue's REPLACE: a duplicate probe has nothing to rescue,
        // so coalescing is exactly what you want.
        // Guarded: if the disk is still too full to open WorkManager's database, the device-status
        // probe is not scheduled this launch -- it is telemetry, and must never be the reason the
        // app does not start.
        runCatching { WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DeviceStatusWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeviceStatusWorker>(
                DeviceStatusWorker.INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build(),
        ) }.onFailure { Log.w(TAG, "periodic device-status work not scheduled", it) }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

    private companion object {
        const val TAG = "GrandTimeApp"
    }
}
