package com.benzn.grandtime.ui

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.benzn.grandtime.ui.theme.LocalFsColors
import kotlinx.coroutines.delay
import java.io.File

/** What the Files screen asks the player for: a whole recording, starting at one of its segments. */
data class PlaybackRequest(val unit: RecordingUnit, val startIndex: Int = 0)

/**
 * Plays ONE recording -- audio or video -- inside the app, its segments in order, as a playlist.
 *
 * WHY. Video used to be handed to whatever external player the device had, which left the app; and a
 * recording split into ~30 s segments had to be played a part at a time, picked by hand from a list.
 * The segments are NOT merged on disk: this device rolls old recordings away to stay above its
 * storage floor, and a merged copy would double what a recording costs. They are queued instead, and
 * [PlaybackTimeline] makes the position bar span the whole recording, so a drag to 5:00 lands in
 * whichever segment holds 5:00.
 *
 * A segment that will not open is skipped rather than ending playback: truncated and half-written
 * segments have happened on these devices, and the rest of the session is still worth watching.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingPlayerSheet(unit: RecordingUnit, startIndex: Int = 0, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fs = LocalFsColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isVideo = unit.representative.kind == "video"
    val firstIndex = startIndex.coerceIn(0, unit.segments.lastIndex)

    val player = remember { ExoPlayer.Builder(context).build() }
    var segmentIndex by remember { mutableIntStateOf(firstIndex) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragGlobalMs by remember { mutableLongStateOf(0L) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var skipped by remember { mutableIntStateOf(0) }
    // The rows already know how long finalized segments are; the player refines each as it opens it.
    // Starting from the rows means the bar spans the whole recording immediately, rather than growing
    // as playback moves through it.
    var durations by remember { mutableStateOf(unit.segments.map { it.durationMs ?: 0L }) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                segmentIndex = p.currentMediaItemIndex
                isPlaying = p.isPlaying
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                durations = mergeDurations(timeline, durations)
            }

            override fun onPlayerError(error: PlaybackException) {
                // One unreadable segment must not end the recording.
                if (player.hasNextMediaItem()) {
                    skipped++
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    errorMessage = "Could not play this recording"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(unit.representative.id, firstIndex) {
        runCatching {
            player.setMediaItems(unit.segments.map { MediaItem.fromUri(File(it.filePath).toURI().toString()) })
            player.seekTo(firstIndex, 0L)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { errorMessage = "Could not play this recording" }
    }

    // Ticks the displayed position while playing, and stands aside while the bar is being dragged so
    // the tick does not fight the gesture (the rule the audio player this replaces already used).
    LaunchedEffect(isPlaying, isDragging) {
        while (isPlaying && !isDragging) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            segmentIndex = runCatching { player.currentMediaItemIndex }.getOrDefault(segmentIndex)
            delay(300)
        }
    }

    val totalMs = PlaybackTimeline.total(durations)
    val seekable = PlaybackTimeline.seekable(durations)
    val globalMs = if (isDragging) dragGlobalMs else PlaybackTimeline.globalPosition(segmentIndex, positionMs, durations)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                if (unit.isGroup) {
                    unit.segmentCount.toString() + " segments, " + formatClock(totalMs)
                } else {
                    unit.representative.fileName
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            if (isVideo) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                keepScreenOn = true
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setPlayer(player)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (errorMessage != null) {
                Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
            } else {
                Slider(
                    value = globalMs.coerceIn(0L, totalMs.coerceAtLeast(1L)).toFloat(),
                    valueRange = 0f..totalMs.coerceAtLeast(1L).toFloat(),
                    enabled = seekable,
                    onValueChange = {
                        isDragging = true
                        dragGlobalMs = it.toLong()
                    },
                    onValueChangeFinished = {
                        val target = PlaybackTimeline.locate(dragGlobalMs, durations)
                        runCatching { player.seekTo(target.segmentIndex, target.positionMs) }
                        segmentIndex = target.segmentIndex
                        positionMs = target.positionMs
                        isDragging = false
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(globalMs), color = fs.textTertiary, style = MaterialTheme.typography.bodySmall)
                    Text(formatClock(totalMs), color = fs.textTertiary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            runCatching {
                                if (player.isPlaying) {
                                    player.pause()
                                } else {
                                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
                                    player.play()
                                }
                            }
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        // Text glyphs, matching the duration badge and the player this replaces: the
                        // material-icons-extended artifact is not a dependency of this project.
                        Text(
                            if (isPlaying) "❚❚" else "▶",
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (unit.isGroup) {
                    val skippedNote = if (skipped > 0) ", " + skipped + " unreadable skipped" else ""
                    Text(
                        "Segment " + (segmentIndex + 1) + " of " + unit.segmentCount + skippedNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = fs.textTertiary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Lengths the player has discovered, falling back to what the rows already said. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun mergeDurations(timeline: Timeline, current: List<Long>): List<Long> {
    if (timeline.windowCount == 0) return current
    val window = Timeline.Window()
    return current.mapIndexed { index, known ->
        if (index >= timeline.windowCount) {
            known
        } else {
            val reported = timeline.getWindow(index, window).durationMs
            if (reported != C.TIME_UNSET && reported > 0) reported else known
        }
    }
}
