package com.benzn.grandtime.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.capture.MediaStorage
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.core.ResourceStatus
import com.benzn.grandtime.core.WarnLevel
import com.benzn.grandtime.core.assessResources
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.ui.theme.LocalFsColors
import com.benzn.grandtime.upload.WorkManagerUploadEnqueuer
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    val login by AppState.loginState.collectAsStateWithLifecycle()
    val capture by AppState.captureState.collectAsStateWithLifecycle()
    val site by AppState.selectedSite.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showSitePicker by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val startOfDay = startOfDayMillis(System.currentTimeMillis(), zone)
    val uploadCounts by remember(startOfDay) {
        CaptureDb.get(context).captureRecords().observeUploadStatusCountsSince(startOfDay)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val uploadSummary = summarizeUploads(uploadCounts)

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(capture) {
        while (capture !is CaptureState.Idle) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    var setupComplete by remember { mutableStateOf(isSetupComplete(context)) }
    var resourceStatus by remember { mutableStateOf(readResourceStatus(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                setupComplete = isSetupComplete(context)
                resourceStatus = readResourceStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            resourceStatus = readResourceStatus(context)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (resourceStatus.hasWarning) {
            ResourceWarningBanner(resourceStatus)
            Spacer(Modifier.height(12.dp))
        }
        FsCard {
            FsCardTitle("Device")
            val (dotColor, statusText) = when (val s = capture) {
                is CaptureState.RecordingVideo ->
                    MaterialTheme.colorScheme.error to "Recording ${mmss(nowMillis - s.startedAtMillis)}"
                is CaptureState.RecordingAudio ->
                    MaterialTheme.colorScheme.error to "Recording audio ${mmss(nowMillis - s.startedAtMillis)}"
                CaptureState.Idle ->
                    (if (running) fs.successDot else MaterialTheme.colorScheme.outline) to
                        (if (running) "Standing by" else "Service stopped")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(10.dp))
                Text(statusText, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (val s = login) {
                    is LoginState.LoggedIn -> "Signed in as ${s.displayName}"
                    LoginState.LoggedOut -> "Not signed in"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (login is LoginState.LoggedIn) {
                Spacer(Modifier.height(8.dp))
                val siteName = site?.name?.takeIf { it.isNotBlank() }
                Button(
                    onClick = { showSitePicker = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        if (siteName != null) "Site: $siteName" else "Select site",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
        }
        if (login is LoginState.LoggedIn) {
            Spacer(Modifier.height(12.dp))
            FsCard {
                FsCardTitle("Today's uploads")
                if (uploadSummary.total == 0) {
                    Text(
                        "No recordings today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column {
                        Text(
                            "Uploaded ${uploadSummary.uploaded}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = fs.successDot,
                        )
                        Text(
                            "Waiting ${uploadSummary.inProgress}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Failed ${uploadSummary.failed}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (uploadSummary.allDone) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "All uploaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = fs.successDot,
                        )
                    }
                    if (uploadSummary.failed > 0) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val failedRecs = CaptureDb.get(context).captureRecords()
                                        .listByUploadStatus(listOf("failed"))
                                    failedRecs.forEach { WorkManagerUploadEnqueuer(context).enqueue(it.id) }
                                    Toast.makeText(
                                        context,
                                        "Retrying ${failedRecs.size} uploads",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text("Retry failed (${uploadSummary.failed})")
                        }
                    }
                }
            }
            val connected by AppState.siteVoiceConnected.collectAsStateWithLifecycle()
            val inbox by AppState.siteVoiceInbox.collectAsStateWithLifecycle()
            Spacer(Modifier.height(12.dp))
            FsCard {
                FsCardTitle("Site voice")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (connected) fs.successDot else MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (connected) "Connected" else "Offline",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (inbox.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No recent messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    inbox.take(5).forEach { clip ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "From ${clip.senderUserId.take(8)} · ${clip.durationS}s",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { AppState.siteVoiceReplayRequests.tryEmit(clip.s3Key) }) {
                                Text("Replay")
                            }
                        }
                    }
                }
            }
        }
        if (showSitePicker) {
            SitePickerDialog(onDismiss = { showSitePicker = false })
        }
        if (!setupComplete) {
            Spacer(Modifier.height(12.dp))
            FsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openSetup(context) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tap to finish setup — permissions missing",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = fs.warningText,
                    )
                }
            }
        }
    }
}

/** Prominent top-of-screen banner shown only while [status] has an active storage or battery warning. */
@Composable
private fun ResourceWarningBanner(status: ResourceStatus) {
    val fs = LocalFsColors.current
    val worst = if (status.storage == WarnLevel.CRITICAL || status.battery == WarnLevel.CRITICAL) {
        WarnLevel.CRITICAL
    } else {
        WarnLevel.WARNING
    }
    val tint = if (worst == WarnLevel.CRITICAL) MaterialTheme.colorScheme.error else fs.warningText
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint, MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        if (status.storage != WarnLevel.NONE) {
            val freeGb = "%.1f".format(status.freeBytes / (1024.0 * 1024 * 1024)) // GiB, matching the binary thresholds
            Text(
                if (status.storage == WarnLevel.CRITICAL) {
                    "Storage critical — $freeGb GB left, recording may stop"
                } else {
                    "Low storage — $freeGb GB left"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
        if (status.battery != WarnLevel.NONE) {
            if (status.storage != WarnLevel.NONE) Spacer(Modifier.height(4.dp))
            Text(
                if (status.battery == WarnLevel.CRITICAL) {
                    "Battery critical — ${status.batteryPct}%"
                } else {
                    "Low battery — ${status.batteryPct}%"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
    }
}

/** Live storage (StatFs) + battery (sticky intent) read. Storage read failure never false-alarms. */
private fun readResourceStatus(context: Context): ResourceStatus {
    val freeBytes = runCatching {
        StatFs(MediaStorage.publicRoot(context).path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return assessResources(freeBytes, pct, charging)
}

private fun mmss(elapsedMillis: Long): String {
    val total = (elapsedMillis / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun isSetupComplete(context: Context): Boolean {
    val camera = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    val batteryExempt = pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)
    return camera && mic && Settings.canDrawOverlays(context) && Environment.isExternalStorageManager() && batteryExempt
}

private fun openSetup(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    } else if (!Environment.isExternalStorageManager()) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    } else {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }
}
