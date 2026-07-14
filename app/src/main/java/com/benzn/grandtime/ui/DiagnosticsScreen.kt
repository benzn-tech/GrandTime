package com.benzn.grandtime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.RawDirection
import com.benzn.grandtime.ui.theme.LocalFsColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen() {
    val entries by AppState.probeEntries.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    var pressed by remember { mutableStateOf<HardKey?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HardKey.entries.forEach { key ->
                OutlinedButton(
                    onClick = {},
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (pressed == key) fs.surfaceSelected else MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .pointerInput(key) {
                            awaitEachGesture {
                                // requireUnconsumed=false:不与按钮自身的点击手势竞争
                                awaitFirstDown(requireUnconsumed = false)
                                pressed = key
                                AppState.screenKeyEvents.tryEmit(key to RawDirection.DOWN)
                                waitForUpOrCancellation()
                                pressed = null
                                AppState.screenKeyEvents.tryEmit(key to RawDirection.UP)
                            }
                        },
                ) { Text(shortKeyLabel(key), maxLines = 1) }
            }
        }
        FsCard(modifier = Modifier.weight(1f)) {
            FsCardTitle("Event log")
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "No events yet — press a key",
                        style = MaterialTheme.typography.bodySmall,
                        color = fs.textTertiary,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(entries) { entry ->
                        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                timeFormat.format(Date(entry.timestampMillis)),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = fs.textTertiary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                entry.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
