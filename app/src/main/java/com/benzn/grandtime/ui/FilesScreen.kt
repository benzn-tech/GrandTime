package com.benzn.grandtime.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.benzn.grandtime.capture.CaptureState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaFilter(val label: String, val kind: String?) {
    ALL("All", null),
    VIDEO("Video", "video"),
    AUDIO("Audio", "audio"),
    PHOTO("Photo", "photo"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { CaptureDb.get(context.applicationContext).captureRecords() }
    var filter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    var playingAudio by remember { mutableStateOf<CaptureRecord?>(null) }
    var detailUnit by remember { mutableStateOf<RecordingUnit?>(null) }
    var menuUnit by remember { mutableStateOf<RecordingUnit?>(null) }
    var deleteUnit by remember { mutableStateOf<RecordingUnit?>(null) }
    val records by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    // Session id of the recording in progress (if any) — its segment rows exist in the DB before the
    // recording ends (the live one is unfinalized + still being written). Deleting that unit would
    // unlink the file MediaCodec is writing + take down the whole live session, so block it.
    val capture by AppState.captureState.collectAsStateWithLifecycle()
    val activeSessionId = when (val c = capture) {
        is CaptureState.RecordingVideo -> c.sessionId
        is CaptureState.PausedVideo -> c.sessionId
        is CaptureState.RecordingAudio -> c.sessionId
        is CaptureState.PausedAudio -> c.sessionId
        else -> null
    }
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
            val units = groupIntoRecordingUnits(filtered)
            val grouped = units.groupBy { dayLabel(it.representative.startedAt) }
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
                    items(dayItems, key = { it.representative.id }) { unit ->
                        MediaCell(
                            unit = unit,
                            onClick = {
                                if (unit.isGroup) {
                                    detailUnit = unit
                                } else {
                                    val record = unit.representative
                                    if (record.kind == "audio") playingAudio = record else openFile(context, record)
                                }
                            },
                            onLongClick = { menuUnit = unit },
                        )
                    }
                }
            }
        }
    }

    playingAudio?.let { record ->
        AudioPlayerSheet(record) { playingAudio = null }
    }

    detailUnit?.let { unit ->
        RecordingDetailSheet(
            unit = unit,
            onPlaySegment = { seg -> if (seg.kind == "audio") playingAudio = seg else openFile(context, seg) },
            onDismiss = { detailUnit = null },
        )
    }

    menuUnit?.let { unit ->
        ModalBottomSheet(onDismissRequest = { menuUnit = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    "Re-upload",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (unit.segments.all { it.uploadStatus == "uploaded" }) {
                                Toast.makeText(context, "Already uploaded", Toast.LENGTH_SHORT).show()
                            } else {
                                unit.ids.forEach { WorkManagerUploadEnqueuer(context).enqueue(it) }
                                val label = if (unit.isGroup) {
                                    "recording (${unit.segmentCount} segments)"
                                } else {
                                    unit.representative.fileName
                                }
                                Toast.makeText(context, "Re-uploading $label", Toast.LENGTH_SHORT).show()
                            }
                            menuUnit = null
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                )
                Text(
                    "Delete",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Only the live video/audio recording is protected; a photo taken during a
                            // recording shares its sessionId but is a closed file — safe to delete anytime.
                            if (unit.representative.kind != "photo" && unit.representative.sessionId == activeSessionId) {
                                Toast.makeText(context, "Stop this recording before deleting", Toast.LENGTH_SHORT).show()
                            } else {
                                deleteUnit = unit
                            }
                            menuUnit = null
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }

    deleteUnit?.let { unit ->
        AlertDialog(
            onDismissRequest = { deleteUnit = null },
            title = { Text("Delete recording") },
            text = { Text(deleteConfirmMessage(unit.aggregateUploadStatus())) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = unit.ids
                    val paths = unit.segments.map { it.filePath }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            paths.forEach { path -> runCatching { File(path).delete() } }
                            dao.markMissing(ids)
                        }
                    }
                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    deleteUnit = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteUnit = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(unit: RecordingUnit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val fs = LocalFsColors.current
    val record = unit.representative
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
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
            // Video/audio recordings (segmented, possibly multi-part) get a duration badge; a
            // multi-segment recording also gets an "xN" tag so it reads as one grouped tile.
            if (record.kind != "photo") {
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
                    Spacer(Modifier.size(3.dp))
                    Text(mmssLabel(unit.totalDurationMs), color = Color.White, fontSize = 9.sp)
                    if (unit.isGroup) {
                        Spacer(Modifier.size(3.dp))
                        Text("×${unit.segmentCount}", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            RecordingUploadStatusBadge(unit, Modifier.align(Alignment.TopEnd))
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

/** Upload-status corner badge (duration-badge styling). For a single unit this mirrors the
 *  segment's own status exactly (same 4 symbols/enqueue behavior as before grouping existed);
 *  for a multi-segment recording it falls back to [aggregateUploadStatus]'s 3-bucket rule, and
 *  tapping it re-enqueues every not-yet-uploaded segment in the group. */
@Composable
private fun RecordingUploadStatusBadge(unit: RecordingUnit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fs = LocalFsColors.current
    val status = if (unit.isGroup) unit.aggregateUploadStatus() else unit.representative.uploadStatus
    val (symbol, color) = when (status) {
        "uploaded" -> "✓" to fs.successDot
        "uploading" -> "↑" to Color.White
        "failed" -> "!" to MaterialTheme.colorScheme.error
        else -> "…" to Color(0xFFBDBDBD) // pending (or, for a group, any non-uploaded/non-failed mix)
    }
    val enqueueableIds = unit.segments.filter { it.uploadStatus == "pending" || it.uploadStatus == "failed" }.map { it.id }
    Row(
        modifier
            .padding(4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Color(0x99000000))
            .let { base ->
                if (enqueueableIds.isNotEmpty()) {
                    base.clickable { enqueueableIds.forEach { id -> WorkManagerUploadEnqueuer(context).enqueue(id) } }
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

/** Bottom sheet listing a multi-segment recording's parts in order (c0000..), each tappable to
 *  play/open that individual segment file — no merge/playlist, segment-by-segment per the slice's
 *  scope (a true single-file/seamless-playback export is a later slice). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingDetailSheet(unit: RecordingUnit, onPlaySegment: (CaptureRecord) -> Unit, onDismiss: () -> Unit) {
    val fs = LocalFsColors.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // verticalScroll: at 30s segments a multi-minute recording has many rows; without scroll the
        // ModalBottomSheet clips overflow and later segments become untappable.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "${unit.segmentCount} segments · ${mmssLabel(unit.totalDurationMs)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            unit.segments.forEachIndexed { index, segment ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlaySegment(segment) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Segment ${index + 1}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    segment.durationMs?.let { d ->
                        Text(mmssLabel(d), color = fs.textTertiary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (index != unit.segments.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
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

/** 公共根 FieldSight/device/{video,audio,photo} 扫盘,供对账补插磁盘上有而 DB 无的文件。
 *  internal (not private): reused by CoreService's startup reconcile, see startPipeline(). */
internal fun scanDisk(context: Context): List<FilesReconciler.DiskFile> {
    val root = MediaStorage.publicRoot(context)
    val kinds = listOf("video", "audio", "photo")
    return kinds.flatMap { kind ->
        MediaStorage.mediaSubdir(root, AppState.mediaScope.value.folder, kind).listFiles()?.filter { it.isFile }?.map { f ->
            FilesReconciler.DiskFile(f.absolutePath, f.name, kind, f.length(), f.lastModified())
        } ?: emptyList()
    }
}

internal fun readDurationMillis(path: String): Long? = try {
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
