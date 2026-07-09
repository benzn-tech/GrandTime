package com.benzn.grandtime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.RawDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProbeScreen() {
    val entries by AppState.probeEntries.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HardKey.entries.forEach { key ->
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(key) {
                            awaitEachGesture {
                                // requireUnconsumed=false:不与按钮自身的点击手势竞争
                                awaitFirstDown(requireUnconsumed = false)
                                AppState.screenKeyEvents.tryEmit(key to RawDirection.DOWN)
                                waitForUpOrCancellation()
                                AppState.screenKeyEvents.tryEmit(key to RawDirection.UP)
                            }
                        },
                ) { Text(keyLabel(key)) }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries) { entry ->
                Text(
                    "${timeFormat.format(Date(entry.timestampMillis))}  ${entry.text}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
