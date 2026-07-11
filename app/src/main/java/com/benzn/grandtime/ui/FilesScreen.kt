package com.benzn.grandtime.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benzn.grandtime.R
import com.benzn.grandtime.ui.theme.LocalFsColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MediaFilter(val label: String) { ALL("All"), VIDEO("Video"), AUDIO("Audio"), PHOTO("Photo") }

data class MediaEntry(val name: String, val type: MediaFilter, val sizeBytes: Long, val modifiedMillis: Long)

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    val entries = remember { scanMedia(context) }
    val filtered = if (filter == MediaFilter.ALL) entries else entries.filter { it.type == filter }
    val fs = LocalFsColors.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            MediaFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                )
            }
        }
        if (filtered.isEmpty()) {
            FsCard {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_nav_files),
                        contentDescription = null,
                        tint = fs.textTertiary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No recordings yet", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Recordings will appear here after you record",
                        style = MaterialTheme.typography.bodySmall,
                        color = fs.textTertiary,
                    )
                }
            }
        } else {
            val grouped = filtered.groupBy { dayLabel(it.modifiedMillis) }
            LazyColumn(Modifier.weight(1f)) {
                grouped.forEach { (day, dayItems) ->
                    item(key = "header-$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = fs.textTertiary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(dayItems, key = { "${it.type}-${it.name}" }) { entry -> MediaRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(entry: MediaEntry) {
    val fs = LocalFsColors.current
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                entry.type.label.first().toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            entry.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(formatSize(entry.sizeBytes), style = MaterialTheme.typography.bodySmall, color = fs.textTertiary)
    }
}

/** SP3 存储契约:录制文件落 getExternalFilesDir(null)/media/{video,audio,photo}。 */
private fun scanMedia(context: Context): List<MediaEntry> {
    val root = File(context.getExternalFilesDir(null), "media")
    val dirs = mapOf(
        "video" to MediaFilter.VIDEO,
        "audio" to MediaFilter.AUDIO,
        "photo" to MediaFilter.PHOTO,
    )
    return dirs.flatMap { (dirName, type) ->
        File(root, dirName).listFiles()?.filter { it.isFile }?.map { f ->
            MediaEntry(f.name, type, f.length(), f.lastModified())
        } ?: emptyList()
    }.sortedByDescending { it.modifiedMillis }
}

private fun dayLabel(millis: Long): String {
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
    val display = SimpleDateFormat("d MMM yyyy", Locale.US)
    return if (dayKey.format(Date(millis)) == dayKey.format(Date())) "Today" else display.format(Date(millis))
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
