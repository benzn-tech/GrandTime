package com.benzn.grandtime.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.benzn.grandtime.R
import com.benzn.grandtime.capture.MediaStorage
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.db.CaptureRecord
import com.benzn.grandtime.db.FilesReconciler
import com.benzn.grandtime.ui.theme.LocalFsColors
import com.benzn.grandtime.upload.WorkManagerUploadEnqueuer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MediaFilter(val label: String, val kind: String?) {
    ALL("All", null),
    VIDEO("Video", "video"),
    AUDIO("Audio", "audio"),
    PHOTO("Photo", "photo"),
}

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val dao = remember { CaptureDb.get(context.applicationContext).captureRecords() }
    var filter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    val records by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val fs = LocalFsColors.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            FilesReconciler(dao, durationReader = ::readDurationMillis).reconcile(scanDisk(context))
        }
    }

    val filtered = filter.kind?.let { k -> records.filter { it.kind == k } } ?: records

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
            val grouped = filtered.groupBy { dayLabel(it.startedAt) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                grouped.forEach { (day, dayItems) ->
                    item(key = "header-$day", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            day,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = fs.textTertiary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(dayItems, key = { it.id }) { record ->
                        MediaCell(record) { openFile(context, record) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCell(record: CaptureRecord, onClick: () -> Unit) {
    val context = LocalContext.current
    val fs = LocalFsColors.current
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (record.kind == "audio") {
                Icon(
                    painterResource(R.drawable.ic_nav_files),
                    contentDescription = null,
                    tint = fs.textTertiary,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                val model = ImageRequest.Builder(context)
                    .data(File(record.filePath))
                    .apply { if (record.kind == "video") videoFrameMillis(0) }
                    .crossfade(true)
                    .build()
                AsyncImage(
                    model = model,
                    contentDescription = record.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (record.kind == "video") {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color(0x99000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("▶", color = Color.White, fontSize = 9.sp)
                    record.durationMs?.let { d ->
                        Spacer(Modifier.size(3.dp))
                        Text(mmssLabel(d), color = Color.White, fontSize = 9.sp)
                    }
                }
            }
            UploadStatusBadge(record, Modifier.align(Alignment.TopEnd))
        }
        Text(
            record.fileName,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = fs.textTertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 上传状态角标(仿 duration badge 样式),pending/failed 可点击(重新)入队。 */
@Composable
private fun UploadStatusBadge(record: CaptureRecord, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fs = LocalFsColors.current
    val (symbol, color) = when (record.uploadStatus) {
        "uploaded" -> "✓" to fs.successDot
        "uploading" -> "↑" to Color.White
        "failed" -> "!" to MaterialTheme.colorScheme.error
        else -> "…" to Color(0xFFBDBDBD) // pending
    }
    val enqueueable = record.uploadStatus == "pending" || record.uploadStatus == "failed"
    Row(
        modifier
            .padding(4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Color(0x99000000))
            .let { base ->
                if (enqueueable) {
                    base.clickable { WorkManagerUploadEnqueuer(context).enqueue(record.id) }
                } else {
                    base
                }
            }
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** 打开文件用 FileProvider content:// (file:// 在 API24+ 传给外部 app 会崩)。 */
private fun openFile(context: Context, record: CaptureRecord) {
    val mime = when (record.kind) {
        "video" -> "video/*"
        "audio" -> "audio/*"
        else -> "image/*"
    }
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(record.filePath),
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, "File not accessible", Toast.LENGTH_SHORT).show()
    }
}

/** 公共根 FieldSight/device/{video,audio,photo} 扫盘,供对账补插磁盘上有而 DB 无的文件。 */
private fun scanDisk(context: Context): List<FilesReconciler.DiskFile> {
    val root = MediaStorage.publicRoot(context)
    val kinds = listOf("video", "audio", "photo")
    return kinds.flatMap { kind ->
        MediaStorage.mediaSubdir(root, AppState.mediaScope.value.folder, kind).listFiles()?.filter { it.isFile }?.map { f ->
            FilesReconciler.DiskFile(f.absolutePath, f.name, kind, f.length(), f.lastModified())
        } ?: emptyList()
    }
}

private fun readDurationMillis(path: String): Long? = try {
    android.media.MediaMetadataRetriever().use { r ->
        r.setDataSource(path)
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }
} catch (e: Exception) {
    null
}

private fun mmssLabel(durationMs: Long): String {
    val total = durationMs / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun dayLabel(millis: Long): String {
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
    val display = SimpleDateFormat("d MMM yyyy", Locale.US)
    return if (dayKey.format(Date(millis)) == dayKey.format(Date())) "Today" else display.format(Date(millis))
}
