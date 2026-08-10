package com.benzn.grandtime.devprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Dev-only. Runs one block of the probe per press and writes the takes to app-specific storage.
 *
 * The layout is deliberately plain and vertically scrollable: this screen is only ever read on a
 * 320x427dp F2SP display, where Material3's default spacing silently pushes controls off-screen
 * (see memory grandtime-f2sp-tiny-screen-layout).
 */
class AudioProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A block runs ~2.2 minutes with the operator forbidden to touch the device. If the screen
        // timed out this activity would leave the foreground, and on API 33 a backgrounded app's
        // AudioRecord is silenced — later takes would record digital zeros with no error at all,
        // poisoning the comparison invisibly.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ProbeScreen() {
        val scope = rememberCoroutineScope()
        val runner = remember { ProbeRunner(this) }
        var status by remember { mutableStateOf("Idle") }
        var running by remember { mutableStateOf(false) }
        var granted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
        val ask = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted = it }

        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AudioProbe", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)

            if (!granted) {
                Button(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant microphone")
                }
                return@Column
            }

            for (block in ProbeBlock.entries) {
                Text("${block.label} — ${block.instruction}", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = !running,
                    onClick = {
                        if (runner.isMicBusy()) {
                            status = "Mic is busy — stop recording in the main app first."
                            return@Button
                        }
                        running = true
                        status = "Block ${block.label}: starting"
                        scope.launch {
                            val dir = runCatching {
                                runner.runBlock(block, seconds = block.seconds) { take, dbfs ->
                                    status = "Block ${block.label} — take ${take.index} " +
                                        "${take.name}: %.1f dBFS".format(Locale.US, dbfs)
                                }
                            }
                            running = false
                            status = dir.fold(
                                onSuccess = { "Block ${block.label} done -> ${it.absolutePath}" },
                                onFailure = { "Block ${block.label} failed: ${it.message}" },
                            )
                        }
                    },
                ) { Text("Run block ${block.label}") }
            }
        }
    }
}
