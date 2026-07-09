package com.benzn.grandtime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

@Composable
fun KeymapScreen() {
    val context = LocalContext.current
    val store = remember { KeyMapStore(context.applicationContext.keymapDataStore) }
    val scope = rememberCoroutineScope()
    val overrides by AppState.overrides.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        HardKey.entries.forEach { key ->
            PressType.entries.forEach { pressType ->
                val current = KeyMapping.resolve(KeyPress(key, pressType), overrides)
                var menuOpen by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${keyLabel(key)} ${pressLabel(pressType)}", Modifier.weight(1f))
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(actionLabel(current))
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
        Button(
            onClick = { scope.launch { store.resetToDefaults() } },
            Modifier.padding(top = 16.dp),
        ) { Text("恢复默认映射") }
    }
}
