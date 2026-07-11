package com.benzn.grandtime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.R
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.service.CoreService
import com.benzn.grandtime.ui.theme.FieldSightTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startCore() // 授权与否都启动:通知不可见不影响 FGS 运行
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FieldSightTheme { MainScaffold() } }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCore()
        } else {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startCore() {
        startForegroundService(Intent(this, CoreService::class.java))
    }
}

@Composable
private fun MainScaffold() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val isSubScreen = screen == Screen.KEY_BINDINGS || screen == Screen.DIAGNOSTICS
    BackHandler(enabled = isSubScreen) { screen = Screen.SETTINGS }
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = if (isSubScreen) screen.title else null,
                showBack = isSubScreen,
                onBack = { screen = Screen.SETTINGS },
                serviceRunning = running,
            )
        },
        bottomBar = {
            if (!isSubScreen) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val itemColors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.secondary,
                            selectedIconColor = MaterialTheme.colorScheme.onSecondary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NavigationBarItem(
                            selected = screen == Screen.HOME,
                            onClick = { screen = Screen.HOME },
                            icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                            label = { Text("Home") },
                            colors = itemColors,
                        )
                        NavigationBarItem(
                            selected = screen == Screen.FILES,
                            onClick = { screen = Screen.FILES },
                            icon = { Icon(painterResource(R.drawable.ic_nav_files), contentDescription = null) },
                            label = { Text("Files") },
                            colors = itemColors,
                        )
                        NavigationBarItem(
                            selected = screen == Screen.SETTINGS,
                            onClick = { screen = Screen.SETTINGS },
                            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            label = { Text("Settings") },
                            colors = itemColors,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen()
                Screen.FILES -> FilesScreen()
                Screen.SETTINGS -> SettingsScreen(onOpen = { screen = it })
                Screen.KEY_BINDINGS -> KeyBindingsScreen()
                Screen.DIAGNOSTICS -> DiagnosticsScreen()
            }
        }
    }
}
