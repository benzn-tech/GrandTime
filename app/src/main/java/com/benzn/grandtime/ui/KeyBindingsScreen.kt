package com.benzn.grandtime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType
import com.benzn.grandtime.keymap.KeyAction
import com.benzn.grandtime.keymap.KeyMapStore
import com.benzn.grandtime.keymap.KeyMapping
import com.benzn.grandtime.keymap.keymapDataStore
import com.benzn.grandtime.ui.theme.LocalFsColors
import kotlinx.coroutines.launch

@Composable
fun KeyBindingsScreen() {
    val context = LocalContext.current
    val store = remember { KeyMapStore(context.applicationContext.keymapDataStore) }
    val scope = rememberCoroutineScope()
    val overrides by AppState.overrides.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        FsCard(contentPadding = 0.dp) {
            HardKey.entries.forEach { key ->
                PressType.entries.forEach { pressType ->
                    val current = KeyMapping.resolve(KeyPress(key, pressType), overrides)
                    val overridden = KeyMapping.overrideKeyOf(key, pressType) in overrides
                    var menuOpen by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(if (overridden) fs.surfaceSelected else Color.Transparent)
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(if (overridden) MaterialTheme.colorScheme.secondary else Color.Transparent)
                        )
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${shortKeyLabel(key)} · ${shortPressLabel(pressType)}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (overridden) {
                                Text("edited", style = MaterialTheme.typography.bodySmall, color = fs.textTertiary)
                            }
                        }
                        Box {
                            OutlinedButton(onClick = { menuOpen = true }, shape = MaterialTheme.shapes.small) {
                                Text(actionLabel(current), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                KeyAction.entries.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(actionLabel(action)) },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch { store.setOverride(key, pressType, action) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { scope.launch { store.resetToDefaults() } },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
        ) { Text("Reset to defaults") }
    }
}
