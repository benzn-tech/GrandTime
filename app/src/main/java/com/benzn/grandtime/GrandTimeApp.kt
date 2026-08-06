package com.benzn.grandtime

import android.app.Application
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
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.device.DeviceIdentity
import com.benzn.grandtime.upload.DeviceStatusWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.SupervisorJob

class GrandTimeApp : Application(), ImageLoaderFactory {
    val authManager: CognitoAuthManager by lazy {
        CognitoAuthManager(
            client = CognitoClient(BuildConfig.COGNITO_CLIENT_ID, BuildConfig.COGNITO_REGION),
            tokenStore = EncryptedTokenStore(this),
            dao = CaptureDb.get(this).captureRecords(),
            publicRoot = { MediaStorage.publicRoot(this) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    /**
     * Process-lifetime scope for fire-and-forget persistence that must outlive a screen or dialog.
     * The site picker writes the selected site to DataStore here instead of a composition
     * `rememberCoroutineScope`, which onDismiss() would cancel mid-write (dropping the selection).
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Resolve the device's own identity before anything can make a request.
        // Until this runs, DeviceIdentity reports nothing and the ledger reads
        // the device as never-seen, which is true rather than wrong.
        DeviceIdentity.init(this)

        // The backlog channel. Rare on purpose: a frozen record is not losing its retry
        // budget while it waits, so nothing here is urgent — the value is that a device
        // falling behind stops being invisible, not that it is noticed within the minute.
        //
        // KEEP, unlike the upload queue's REPLACE: a duplicate probe has nothing to rescue,
        // so coalescing is exactly what you want.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
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
        )
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()
}
