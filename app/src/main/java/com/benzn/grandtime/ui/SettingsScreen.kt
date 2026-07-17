package com.benzn.grandtime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.AspectRatio
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.core.PhotoQuality
import com.benzn.grandtime.core.PhotoResolution
import com.benzn.grandtime.core.RecordingSettings
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.VideoQuality
import com.benzn.grandtime.core.settingsDataStore
import com.benzn.grandtime.ui.theme.LocalFsColors
import kotlinx.coroutines.launch

private enum class SettingDialog { VIDEO_QUALITY, ASPECT_RATIO, SEGMENT, PHOTO_QUALITY, PHOTO_RESOLUTION, WATERMARK, SCREEN_OFF, VIDEO_UPLOAD }

@Composable
fun SettingsScreen(onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext.settingsDataStore) }
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = RecordingSettings())
    val login by AppState.loginState.collectAsStateWithLifecycle()
    val auth = remember { (context.applicationContext as GrandTimeApp).authManager }
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        GroupHeader("Recording")
        FsCard(contentPadding = 0.dp) {
            SettingRow("Video quality", settings.videoQuality.label) { dialog = SettingDialog.VIDEO_QUALITY }
            RowDivider()
            SettingRow("Aspect ratio", settings.aspectRatio.label) { dialog = SettingDialog.ASPECT_RATIO }
            RowDivider()
            SettingRow("Segment length", "${settings.segmentMinutes} min") { dialog = SettingDialog.SEGMENT }
            RowDivider()
            SettingRow("Photo quality", settings.photoQuality.label) { dialog = SettingDialog.PHOTO_QUALITY }
            RowDivider()
            SettingRow("Photo resolution", settings.photoResolution.label) { dialog = SettingDialog.PHOTO_RESOLUTION }
            RowDivider()
            SettingRow("Watermark", if (settings.watermarkEnabled) "On" else "Off") { dialog = SettingDialog.WATERMARK }
            RowDivider()
            SettingRow(
                "Auto screen-off",
                if (settings.screenOffMinutes == 0) "Never" else "${settings.screenOffMinutes} min",
            ) { dialog = SettingDialog.SCREEN_OFF }
            RowDivider()
            SettingRow(
                "Video upload",
                if (settings.videoUploadWifiOnly) "Wi-Fi only" else "Any network",
            ) { dialog = SettingDialog.VIDEO_UPLOAD }
        }
        GroupHeader("Keys")
        FsCard(contentPadding = 0.dp) {
            SettingRow("Key bindings", null) { onOpen(Screen.KEY_BINDINGS) }
        }
        GroupHeader("System")
        FsCard(contentPadding = 0.dp) {
            SettingRow("Diagnostics", null) { onOpen(Screen.DIAGNOSTICS) }
            RowDivider()
            SettingRow("About", versionName, onClick = null)
        }
        GroupHeader("Account")
        FsCard(contentPadding = 0.dp) {
            SettingRow(
                when (val s = login) {
                    is LoginState.LoggedIn -> "Signed in as ${s.displayName}"
                    LoginState.LoggedOut -> "Not signed in"
                },
                null,
                onClick = null,
            )
            RowDivider()
            SettingRow("Sign out", null, enabled = true, onClick = { scope.launch { auth.signOut() } })
        }
        Spacer(Modifier.height(24.dp))
    }

    when (dialog) {
        SettingDialog.VIDEO_QUALITY -> RadioDialog(
            title = "Video quality",
            options = VideoQuality.entries,
            selected = settings.videoQuality,
            label = { it.label },
            onSelect = { scope.launch { store.setVideoQuality(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.ASPECT_RATIO -> RadioDialog(
            title = "Aspect ratio",
            options = AspectRatio.entries,
            selected = settings.aspectRatio,
            label = { it.label },
            onSelect = { scope.launch { store.setAspectRatio(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.SEGMENT -> RadioDialog(
            title = "Segment length",
            options = SettingsStore.SEGMENT_OPTIONS,
            selected = settings.segmentMinutes,
            label = { "$it min" },
            onSelect = { scope.launch { store.setSegmentMinutes(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.PHOTO_QUALITY -> RadioDialog(
            title = "Photo quality",
            options = PhotoQuality.entries,
            selected = settings.photoQuality,
            label = { it.label },
            onSelect = { scope.launch { store.setPhotoQuality(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.PHOTO_RESOLUTION -> RadioDialog(
            title = "Photo resolution",
            options = PhotoResolution.entries,
            selected = settings.photoResolution,
            label = { it.label },
            onSelect = { scope.launch { store.setPhotoResolution(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.WATERMARK -> RadioDialog(
            title = "Watermark",
            options = listOf(true, false),
            selected = settings.watermarkEnabled,
            label = { if (it) "On" else "Off" },
            onSelect = { scope.launch { store.setWatermarkEnabled(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.SCREEN_OFF -> RadioDialog(
            title = "Auto screen-off",
            options = SettingsStore.SCREEN_OFF_OPTIONS,
            selected = settings.screenOffMinutes,
            label = { if (it == 0) "Never" else "$it min" },
            onSelect = { scope.launch { store.setScreenOffMinutes(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.VIDEO_UPLOAD -> RadioDialog(
            title = "Video upload",
            options = listOf(true, false),
            selected = settings.videoUploadWifiOnly,
            label = { if (it) "Wi-Fi only" else "Any network" },
            onSelect = { scope.launch { store.setVideoUploadWifiOnly(it) } },
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalFsColors.current.textTertiary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp),
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingRow(
    title: String,
    value: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val fs = LocalFsColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else fs.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else fs.textTertiary,
            )
        }
        if (onClick != null && enabled) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = fs.textTertiary,
            )
        }
    }
}

@Composable
private fun <T> RadioDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { onSelect(option); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}
