package com.benzn.grandtime

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.benzn.grandtime.auth.CognitoAuthManager
import com.benzn.grandtime.auth.CognitoClient
import com.benzn.grandtime.auth.EncryptedTokenStore
import com.benzn.grandtime.capture.MediaStorage
import com.benzn.grandtime.db.CaptureDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()
}
