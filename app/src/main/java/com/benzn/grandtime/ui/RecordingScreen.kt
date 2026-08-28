package com.benzn.grandtime.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.AspectRatio
import com.benzn.grandtime.core.RecordingSettings
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.settingsDataStore
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.capture.sessionIdOrNull
import com.benzn.grandtime.keymap.KeyAction
import kotlinx.coroutines.delay

/**
 * The screen a body-worn device shows while it is capturing.
 *
 * It answers three questions, in this order, for someone who has just woken the screen and is
 * not going to read anything:
 *
 * 1. **Is it still recording?** A live elapsed timer, a lit status line, and — depending on the
 *    kind — a camera preview or a meter driven by the real sample stream. Nothing on this screen
 *    animates unless the capture behind it is actually running, which is the whole point: on this
 *    ROM a refused microphone returns buffers of zeros and every other layer reports success.
 * 2. **How do I stop it?** One target big enough to hit through a glove without aiming. Ending is
 *    the action people need in a hurry; pause is the one they can afford to look for.
 * 3. **Is it picking the room up?** The speaker count the backend confirms as chunks land.
 *
 * Deliberately NOT here: a "mark highlight" button (nobody checks a chest-mounted screen often
 * enough for it to mean anything) and a photo button (the physical key already does that well,
 * and a second way to fire the shutter is a second way to fire it by accident).
 */
@Composable
fun RecordingScreen() {
    val capture by AppState.captureState.collectAsStateWithLifecycle()
    val video = capture is CaptureState.RecordingVideo || capture is CaptureState.PausedVideo
    val paused = capture is CaptureState.PausedVideo || capture is CaptureState.PausedAudio

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMillis = System.currentTimeMillis(); delay(1000) } }

    // Invite has to live HERE, not on Home: it needs a live session, and having one is exactly
    // what navigates away from Home.
    val group by AppState.pendingGroup.collectAsStateWithLifecycle()
    var showMeetingCode by remember { mutableStateOf(false) }
    // Always the GROUP's id, never this device's. A device that already joined and then showed its
    // own session id would open a SECOND group, and the two halves of one meeting would never
    // merge. For the lead the two are the same value, so this is one rule rather than a special
    // case.
    val codeId = group?.groupId ?: capture.sessionIdOrNull()

    Box(Modifier.fillMaxSize().background(ScreenBg)) {
        if (video) VideoBackdrop()

        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SiteHeader()
            Spacer(Modifier.height(4.dp))
            ElapsedTimer(capture, nowMillis)
            Spacer(Modifier.height(6.dp))
            StatusLine(capture = capture, paused = paused)

            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // The video path proves itself with the preview behind this column, so the middle
                // is left empty rather than filled with a meter it has no samples for.
                if (!video) MicMeter(active = !paused)
            }

            BigStopButton(
                onClick = {
                    AppState.screenCaptureActions.tryEmit(
                        if (video) KeyAction.END_VIDEO else KeyAction.END_AUDIO
                    )
                },
                dimmed = video,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Tap to end and upload",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryAction(
                    label = if (paused) "Resume" else "Pause",
                    modifier = Modifier.weight(1f),
                ) {
                    AppState.screenCaptureActions.tryEmit(
                        if (video) KeyAction.START_STOP_VIDEO else KeyAction.START_STOP_AUDIO
                    )
                }
                if (codeId != null) {
                    SecondaryAction(label = "Invite", modifier = Modifier.weight(1f)) {
                        showMeetingCode = true
                    }
                }
            }
        }

        if (showMeetingCode) {
            val id = codeId
            if (id == null) showMeetingCode = false
            else MeetingCodeDialog(sessionId = id, onDismiss = { showMeetingCode = false })
        }
    }
}

/**
 * The camera preview, behind everything else.
 *
 * FIT_CENTER rather than fill: the recorded frame is landscape and the screen is portrait, so
 * filling would crop the ends of the picture and make the preview disagree with the file. What is
 * on this screen is what is on the tape.
 */
@Composable
private fun VideoBackdrop() {
    val context = LocalContext.current
    val surfaceView = remember {
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { AppState.previewSurface.value = h.surface }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { AppState.previewSurface.value = h.surface }
                override fun surfaceDestroyed(h: SurfaceHolder) { AppState.previewSurface.value = null }
            })
        }
    }
    DisposableEffect(Unit) { onDispose { AppState.previewSurface.value = null } }

    val settingsStore = remember { SettingsStore(context.settingsDataStore) }
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = RecordingSettings())
    val ratio = if (settings.aspectRatio == AspectRatio.RATIO_16_9) 16f / 9f else 4f / 3f

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitH = minOf(maxHeight, maxWidth / ratio)
        AndroidView(factory = { surfaceView }, modifier = Modifier.width(fitH * ratio).height(fitH))
    }
    // The controls sit on top of moving picture; without this they stop being readable the moment
    // the operator walks into a bright scene.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
}

@Composable
private fun SiteHeader() {
    val site by AppState.selectedSite.collectAsStateWithLifecycle()
    Text(
        site?.name?.uppercase() ?: "NO SITE SELECTED",
        color = MutedText,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** Big enough to read at arm's length, which is the only distance this ever gets read at. */
@Composable
private fun ElapsedTimer(capture: CaptureState, nowMillis: Long) {
    // startedAtMillis is already the timer ORIGIN in every state — CaptureCore shifts it forward
    // by the pause duration on resume — so this counts recording time, not wall-clock since the
    // first press.
    val started = when (capture) {
        is CaptureState.RecordingVideo -> capture.startedAtMillis
        is CaptureState.PausedVideo -> capture.startedAtMillis
        is CaptureState.RecordingAudio -> capture.startedAtMillis
        is CaptureState.PausedAudio -> capture.startedAtMillis
        CaptureState.Idle -> null
    }
    // A paused session freezes at the length it reached, rather than counting on: the number has
    // to mean "how much recording exists", or a paused device looks like a running one.
    val frozenAt = when (capture) {
        is CaptureState.PausedVideo -> capture.pausedAtMillis
        is CaptureState.PausedAudio -> capture.pausedAtMillis
        else -> null
    }
    val elapsed = (frozenAt ?: nowMillis) - (started ?: nowMillis)
    Text(
        mmss(elapsed),
        color = Color.White,
        fontSize = 52.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun StatusLine(capture: CaptureState, paused: Boolean) {
    val speakers by AppState.sessionSpeakers.collectAsStateWithLifecycle()
    val count = speakersForSession(speakers, capture.sessionIdOrNull())

    val label = when {
        paused -> "PAUSED"
        capture is CaptureState.RecordingVideo -> "RECORDING VIDEO"
        capture is CaptureState.RecordingAudio -> "RECORDING AUDIO"
        else -> "STANDING BY"
    }
    val tint = if (paused) MutedText else LiveOrange
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(7.dp))
        Text(
            label + speakerSuffix(count),
            color = tint,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Seven bars fed by the real microphone level.
 *
 * Never animated on its own. On this ROM a refused capture returns a positive read length and a
 * buffer of zeros, so an idling animation here would be a confident lie about the one fault that
 * is otherwise invisible until someone reads a transcript. Flat bars mean flat audio, and after a
 * few seconds of digital silence the screen says so in words.
 */
@Composable
private fun MicMeter(active: Boolean) {
    // A short history, so the bars read as a moving waveform rather than seven copies of one
    // number.
    val history = remember { mutableStateListOf(0f, 0f, 0f, 0f, 0f, 0f, 0f) }
    LaunchedEffect(active) {
        while (active) {
            history.removeAt(0)
            history.add(AppState.micLevel.value)
            delay(120)
        }
    }
    // One poller rather than an effect keyed on the level: the level changes ten times a second,
    // and restarting a coroutine that often to time a five-second run is a lot of churn for a
    // warning line.
    var silentSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        silentSeconds = 0L
        var silentSinceMillis = 0L
        while (active) {
            if (AppState.micLevel.value > 0f) {
                silentSinceMillis = 0L
                silentSeconds = 0L
            } else {
                if (silentSinceMillis == 0L) silentSinceMillis = System.currentTimeMillis()
                silentSeconds = (System.currentTimeMillis() - silentSinceMillis) / 1000
            }
            delay(250)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.height(48.dp),
        ) {
            for ((i, value) in history.withIndex()) {
                val target = if (active) value else 0f
                val h by animateFloatAsState(targetValue = target, label = "bar$i")
                Box(
                    Modifier
                        .width(7.dp)
                        // A floor of 4dp so the meter is visibly PRESENT at zero rather than
                        // absent — "nothing is arriving" and "there is no meter" must not look
                        // the same.
                        .height((4f + h * 44f).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (active) LiveOrange else MutedText)
                )
            }
        }
        if (active && silentSeconds >= SILENCE_WARNING_SECONDS) {
            Spacer(Modifier.height(10.dp))
            Text(
                "No sound reaching the microphone",
                color = WarnAmber,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The one control that matters, sized to be hit without aiming.
 *
 * [dimmed] on the video screen only because it sits over a live preview there and a solid disc
 * that big would hide the picture the operator is also using to check framing.
 */
@Composable
private fun BigStopButton(onClick: () -> Unit, dimmed: Boolean) {
    Box(
        Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(if (dimmed) LiveOrange.copy(alpha = 0.88f) else LiveOrange)
            .border(6.dp, StopRing, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F3F1)))
    }
}

@Composable
private fun SecondaryAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MutedText.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MutedText, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The speaker count to show under THIS session's timer, or null for nothing.
 *
 * The backend answers on the chunk-upload response, and chunks from a FINISHED session keep
 * uploading for a while after the next one has started — an untagged count would put the last
 * recording's speakers under the current recording's clock, which is worse than no number at all.
 */
internal fun speakersForSession(reported: AppState.SessionSpeakers?, sessionId: String?): Int? =
    reported?.takeIf { sessionId != null && it.sessionId == sessionId }?.count

/**
 * What gets appended to the status line for a speaker count.
 *
 * Null is empty, NOT " · 0 speakers": null means the backend has not said anything, and printing
 * a zero would put a finding on screen that nobody made. A real 0 does print — a microphone that
 * is running and has confirmed nobody is exactly what the operator should be told.
 */
internal fun speakerSuffix(count: Int?): String = when (count) {
    null -> ""
    1 -> " · 1 speaker"
    else -> " · $count speakers"
}

/** Seconds of digital silence before the screen stops implying the microphone is fine. Long enough
 *  that a pause in the conversation never trips it — the level is a PEAK, so a quiet room is not
 *  zero, only a dead capture is. */
private const val SILENCE_WARNING_SECONDS = 5L

// Local to this screen rather than the app theme: everything else in the app is the light
// FieldSight palette, and this is the one surface that has to be readable at night, on a chest,
// with a two-second glance.
private val ScreenBg = Color(0xFF1A1A1A)
private val LiveOrange = Color(0xFFCC5500)
private val StopRing = Color(0xFF3A2410)
private val MutedText = Color(0xFF9A9A9A)
private val WarnAmber = Color(0xFFFFB020)

private fun mmss(elapsed: Long): String {
    val t = (elapsed / 1000).coerceAtLeast(0); return "%02d:%02d".format(t / 60, t % 60)
}
