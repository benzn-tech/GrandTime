package com.benzn.grandtime.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benzn.grandtime.db.CaptureRecord
import com.benzn.grandtime.ui.theme.LocalFsColors
import kotlinx.coroutines.delay

/**
 * Bottom sheet audio player for a single [record]. Starts playback on entry and always
 * releases the [MediaPlayer] when the sheet leaves composition (dismiss, back gesture, or
 * parent recomposition removing it) — see the DisposableEffect below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerSheet(record: CaptureRecord, onDismiss: () -> Unit) {
    val fs = LocalFsColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val mediaPlayer = remember { MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { mediaPlayer.stop() }
            runCatching { mediaPlayer.release() }
        }
    }

    LaunchedEffect(record.filePath) {
        try {
            mediaPlayer.setDataSource(record.filePath)
            mediaPlayer.setOnPreparedListener { mp ->
                durationMs = mp.duration
                mp.start()
                isPlaying = true
            }
            mediaPlayer.setOnCompletionListener { mp ->
                runCatching { mp.seekTo(0) }
                isPlaying = false
                positionMs = 0f
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                errorMessage = "Could not play this recording"
                isPlaying = false
                true
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            errorMessage = "Could not play this recording"
        }
    }

    // Ticks the displayed position while playing; suspended while the user is dragging
    // the slider so the tick doesn't fight the drag gesture.
    LaunchedEffect(isPlaying, isDragging) {
        while (isPlaying && !isDragging) {
            positionMs = runCatching { mediaPlayer.currentPosition.toFloat() }.getOrDefault(positionMs)
            delay(300)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                record.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            if (errorMessage != null) {
                Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
            } else {
                Slider(
                    value = positionMs.coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                    onValueChange = {
                        isDragging = true
                        positionMs = it
                    },
                    onValueChangeFinished = {
                        runCatching { mediaPlayer.seekTo(positionMs.toInt()) }
                        isDragging = false
                    },
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatClock(positionMs.toLong()), color = fs.textTertiary, style = MaterialTheme.typography.bodySmall)
                    Text(formatClock(durationMs.toLong()), color = fs.textTertiary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            runCatching {
                                if (isPlaying) mediaPlayer.pause() else mediaPlayer.start()
                            }
                            isPlaying = !isPlaying
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        // Text glyphs (matching the "▶" duration-badge convention elsewhere in
                        // ui/): the material-icons-extended artifact (with Pause) is not a
                        // project dependency and we're not adding one for this.
                        Text(
                            if (isPlaying) "❚❚" else "▶",
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
