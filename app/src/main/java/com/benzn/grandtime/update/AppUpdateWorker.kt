package com.benzn.grandtime.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.net.AppUpdateClient
import com.benzn.grandtime.net.LatestRelease
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Checks for a newer build, downloads it, verifies it, and marks it ready to install.
 *
 * Nothing is offered until [AppUpdatePolicy.acceptDownload] holds for the file on disk: the digest
 * and size the server published, this app's package name, the version the manifest promised, newer
 * than what is installed, and signed by the same certificate as the installed app.
 */
class AppUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = LOCK.withLock {
        val app = applicationContext as GrandTimeApp
        try {
            app.authManager.silentLogin()
            // Signed-in only, by design. Not signed in is not a failure worth retrying: the check at
            // the next sign-in, or the next period, asks again.
            val idToken = app.authManager.freshIdToken() ?: return@withLock Result.success()
            val current = AppUpdates.currentVersionCode(applicationContext)
            when (val answer = AppUpdateClient(BuildConfig.ORG_API_BASE_URL).latest(idToken)) {
                AppUpdateClient.Answer.Failed -> Result.retry()
                AppUpdateClient.Answer.NoRelease -> {
                    AppUpdates.discard(applicationContext)
                    Result.success()
                }
                is AppUpdateClient.Answer.Release -> handle(answer.release, current)
            }
        } catch (e: Exception) {
            Log.w(TAG, "app update check failed", e)
            Result.retry()
        }
    }

    private fun handle(latest: LatestRelease, current: Long): Result {
        val store = AppUpdateStore(applicationContext)
        if (AppUpdatePolicy.offer(current, latest.versionCode, latest.minVersionCode) == AppUpdatePolicy.Offer.NONE) {
            AppUpdates.discard(applicationContext)
            return Result.success()
        }
        store.read()?.let { ready ->
            if (ready.versionCode == latest.versionCode && ready.sha256 == latest.sha256 && store.apkFile(ready).isFile) {
                AppUpdates.publish(ready)
                return Result.success()
            }
        }
        // Twice the APK: the part file and room for the system to stage the install.
        if (applicationContext.filesDir.usableSpace < latest.sizeBytes * 2) {
            Log.w(TAG, "not enough space to download update ${latest.versionName}")
            return Result.retry()
        }
        store.clear()
        store.dir.mkdirs()
        val part = store.partFile(latest.versionCode)
        val sha = download(latest.url, part)
        if (sha == null) {
            part.delete()
            return Result.retry()
        }
        if (!accept(part, sha, latest, current)) {
            Log.w(TAG, "downloaded update ${latest.versionName} failed verification; discarded")
            part.delete()
            return Result.retry()
        }
        val final = store.finalFile(latest.versionCode)
        if (!part.renameTo(final)) {
            part.delete()
            return Result.retry()
        }
        val ready = ReadyUpdate(
            versionCode = latest.versionCode,
            versionName = latest.versionName,
            minVersionCode = latest.minVersionCode,
            sha256 = latest.sha256,
            fileName = final.name,
            notes = latest.notes,
        )
        store.write(ready)
        AppUpdates.publish(ready)
        Log.i(TAG, "update ${latest.versionName} (${latest.versionCode}) downloaded and verified")
        return Result.success()
    }

    /** Streams to [target], hashing as it writes. Null on any failure. */
    private fun download(url: String, target: File): String? = runCatching {
        HTTP.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val digest = MessageDigest.getInstance("SHA-256")
            val body = resp.body ?: return@runCatching null
            DigestInputStream(body.byteStream(), digest).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun accept(file: File, sha: String, latest: LatestRelease, current: Long): Boolean {
        val pm = applicationContext.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val own = pm.getPackageInfo(applicationContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archiveSigners = AppUpdates.signerDigests(archive)
        return AppUpdatePolicy.acceptDownload(
            expectedSha256 = latest.sha256,
            actualSha256 = sha,
            expectedSizeBytes = latest.sizeBytes,
            actualSizeBytes = file.length(),
            ownPackage = applicationContext.packageName,
            archivePackage = archive?.packageName,
            expectedVersionCode = latest.versionCode,
            archiveVersionCode = archive?.longVersionCode,
            currentVersionCode = current,
            signersMatch = archiveSigners.isNotEmpty() && archiveSigners == AppUpdates.signerDigests(own),
        )
    }

    private companion object {
        const val TAG = "AppUpdate"
        /** The periodic and the check-now worker must never download over each other. */
        val LOCK = Mutex()
        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
