package com.benzn.grandtime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.capture.GroupExit
import com.benzn.grandtime.capture.SessionGroup

/**
 * These two dialogs are read at arm's length, outdoors, often in gloves, by
 * someone who is mid-task and did not come to the phone to read. So: one idea
 * per screen, full-width targets, no explanatory paragraphs. Anything that
 * needs a sentence of justification belongs in this comment, not on the screen.
 */
private val BUTTON_HEIGHT = 64.dp

/**
 * The lead device's code. The other device scans this from [QrJoinMeetingScreen].
 *
 * Rendered white-on-white-background regardless of theme — a QR inverted by a
 * dark theme does not scan, and the failure looks like a broken camera.
 */
@Composable
fun MeetingCodeDialog(sessionId: String, onDismiss: () -> Unit) {
    val bitmap = remember(sessionId) {
        MeetingCode.bitmap(
            SessionGroup.format(sessionId, env = BuildConfig.QR_ENV), sizePx = 512,
        ).asImageBitmap()
    }
    // Full screen, not a normal dialog. The F2SP is 480x640 px at 240dpi, so
    // 320x427 dp of logical space. A default AlertDialog spends ~160dp of that
    // on title, caption and button before the code gets any, and the code was
    // then taller than what was left — a QR missing its bottom rows does not
    // scan, and the failure looks like the other device's camera.
    //
    // A Dialog rather than a screen swap so the recording preview underneath is
    // never torn down: this is shown WHILE recording, from RecordingScreen.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "On the other device: Join a meeting",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                // weight(1f) — the code takes everything the caption and button
                // leave, so it is as large as this screen can make it. Bigger is
                // not cosmetic here: it is what the other camera has to resolve.
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Meeting join code",
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(Color.White)
                            .padding(6.dp),
                    )
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.small,
                ) { Text("Done", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}


/**
 * Asked after recording stops, and reachable manually.
 *
 * Three answers, not two. "I'm leaving" exists because an inspector finishing
 * early must not stop everyone else's recording — with only "meeting ended"
 * available, they would use it, and the rest of the group would stop mid-walk.
 * See [GroupExit] for what each one does.
 */
@Composable
fun MeetingExitDialog(onDecision: (GroupExit.Decision) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDecision(GroupExit.Decision.NOT_YET) },
        title = { Text("Has the meeting ended?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigChoice("Yes — meeting ended") { onDecision(GroupExit.Decision.MEETING_ENDED) }
                BigChoice("I'm leaving, others continue") { onDecision(GroupExit.Decision.I_AM_LEAVING) }
                BigChoice("Not yet") { onDecision(GroupExit.Decision.NOT_YET) }
            }
        },
        // The choices ARE the buttons. A separate confirm row would give the
        // dialog a fourth tappable thing and a wrong default.
        confirmButton = {},
    )
}

/**
 * Shown after another device ended the meeting and this one stopped.
 *
 * States what already happened before asking anything — the recording stopped
 * without the user touching it, and an unexplained stop is how people lose
 * trust in a recorder. Resuming starts a fresh SOLO recording; post-meeting
 * audio never lands in the meeting.
 */
@Composable
fun MeetingResumeDialog(onResume: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meeting ended", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Recording stopped. Start a new one?",
                    style = MaterialTheme.typography.titleMedium,
                )
                BigChoice("Start recording", onResume)
                BigChoice("Not now", onDismiss)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun BigChoice(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
