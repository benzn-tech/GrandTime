package com.benzn.grandtime.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.benzn.grandtime.core.AppState
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The Android side of in-app updates: scheduling the check, restoring a ready update at launch,
 * reading signing certificates, and handing a verified APK to the system installer. The rules are
 * in [AppUpdatePolicy]; the check and download are [AppUpdateWorker].
 */
object AppUpdates {
    const val PERIODIC_NAME = "app_update_check"
    const val NOW_NAME = "app_update_check_now"
    const val INTERVAL_HOURS = 6L
    private const val APK_MIME = "application/vnd.android.package-archive"

    enum class InstallOutcome { STARTED, NEEDS_PERMISSION, RECORDING_IN_PROGRESS, MISSING }

    fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun publish(ready: ReadyUpdate) {
        AppState.appUpdate.value = ready
    }

    fun discard(context: Context) {
        AppUpdateStore(context).clear()
        AppState.appUpdate.value = null
    }

    /** At launch: show a verified download still newer than what is installed; otherwise it is done with. */
    fun restoreOnStart(context: Context) {
        val store = AppUpdateStore(context)
        val ready = store.read()
        if (ready != null && ready.versionCode > currentVersionCode(context) && store.apkFile(ready).isFile) {
            publish(ready)
        } else {
            store.clear()
        }
    }

    private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** KEEP: rescheduling on every launch would reset the period and a device restarted often would never check. */
    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** At sign-in and from Settings: check now rather than within the next six hours. */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** SHA-256 of each certificate that signed [info]. Empty when it cannot be read -- which never matches. */
    fun signerDigests(info: PackageInfo?): Set<String> {
        val signers = info?.signingInfo?.apkContentsSigners ?: return emptySet()
        return signers.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /**
     * Opens the system install dialog for a verified download.
     *
     * Android asks once per app whether it may install others; the first tap on a device opens that
     * settings page instead, and the next tap installs. Never while recording: installing kills this
     * process, and the recording with it.
     */
    fun install(context: Context, ready: ReadyUpdate, captureIdle: Boolean): InstallOutcome {
        if (!AppUpdatePolicy.mayInstallNow(captureIdle)) return InstallOutcome.RECORDING_IN_PROGRESS
        val file = AppUpdateStore(context).apkFile(ready)
        if (!file.isFile) return InstallOutcome.MISSING
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return InstallOutcome.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return InstallOutcome.STARTED
    }
}
