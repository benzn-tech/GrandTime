package com.benzn.grandtime.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.ui.theme.LocalFsColors
import kotlinx.coroutines.delay

@Composable
fun HomeScreen() {
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    val login by AppState.loginState.collectAsStateWithLifecycle()
    val capture by AppState.captureState.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current
    val context = LocalContext.current

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(capture) {
        while (capture !is CaptureState.Idle) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    var setupComplete by remember { mutableStateOf(isSetupComplete(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) setupComplete = isSetupComplete(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
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

private fun mmss(elapsedMillis: Long): String {
    val total = (elapsedMillis / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun isSetupComplete(context: Context): Boolean {
    val camera = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    return camera && mic && Settings.canDrawOverlays(context)
}

private fun openSetup(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    } else {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }
}
