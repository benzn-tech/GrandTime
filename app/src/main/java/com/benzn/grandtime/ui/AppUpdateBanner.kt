package com.benzn.grandtime.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.update.AppUpdatePolicy
import com.benzn.grandtime.update.AppUpdates

/**
 * Top-of-home prompt for a downloaded, verified update. Short lines on purpose: the F2SP screen is
 * 320 dp wide. While a capture is running it says so and does nothing on tap -- installing kills
 * the process, and the recording with it.
 */
@Composable
fun AppUpdateBanner(capture: CaptureState) {
    val ready by AppState.appUpdate.collectAsStateWithLifecycle()
    val update = ready ?: return
    val context = LocalContext.current
    val current = remember { AppUpdates.currentVersionCode(context) }
    val required = AppUpdatePolicy.offer(current, update.versionCode, update.minVersionCode) ==
        AppUpdatePolicy.Offer.REQUIRED
    val idle = capture is CaptureState.Idle
    val tint = if (required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint, MaterialTheme.shapes.medium)
            .clickable(enabled = idle) { installWithFeedback(context, update, idle) }
            .padding(16.dp),
    ) {
        Text(
            if (required) "Update required: ${update.versionName}" else "Update ready: ${update.versionName}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
        Text(
            if (idle) "Tap to install" else "Install after recording",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(12.dp))
}

/** Shared by the banner and the Settings row. */
fun installWithFeedback(
    context: android.content.Context,
    update: com.benzn.grandtime.update.ReadyUpdate,
    idle: Boolean,
) {
    when (AppUpdates.install(context, update, idle)) {
        AppUpdates.InstallOutcome.STARTED -> Unit
        AppUpdates.InstallOutcome.NEEDS_PERMISSION ->
            Toast.makeText(context, "Allow FieldSight to install updates, then tap again", Toast.LENGTH_LONG).show()
        AppUpdates.InstallOutcome.RECORDING_IN_PROGRESS ->
            Toast.makeText(context, "Stop recording first", Toast.LENGTH_SHORT).show()
        AppUpdates.InstallOutcome.MISSING -> {
            Toast.makeText(context, "Update file missing, downloading again", Toast.LENGTH_SHORT).show()
            AppUpdates.checkNow(context)
        }
    }
}
