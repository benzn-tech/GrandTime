package com.benzn.grandtime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.hardware.DeviceProfile
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType
import com.benzn.grandtime.keymap.KeyMapping

@Composable
fun StatusScreen() {
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    val login by AppState.loginState.collectAsStateWithLifecycle()
    val overrides by AppState.overrides.collectAsStateWithLifecycle()
    val lastAction by AppState.lastAction.collectAsStateWithLifecycle()

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("设备:${if (DeviceProfile.isF2spFamily()) "F2SP 家族" else "通用(屏幕按键)"}")
        Text("服务:${if (running) "运行中" else "未运行"}")
        Text(
            "登录:" + when (val s = login) {
                is LoginState.LoggedIn -> s.displayName
                LoginState.LoggedOut -> "未登录"
            }
        )
        Text("最近动作:${lastAction ?: "—"}")
        Spacer(Modifier.height(16.dp))
        Text("当前映射", style = MaterialTheme.typography.titleMedium)
        HardKey.entries.forEach { key ->
            PressType.entries.forEach { pressType ->
                val action = KeyMapping.resolve(KeyPress(key, pressType), overrides)
                val overridden = KeyMapping.overrideKeyOf(key, pressType) in overrides
                Text("${keyLabel(key)} ${pressLabel(pressType)} → ${actionLabel(action)}" +
                    if (overridden) "(已改)" else "")
            }
        }
    }
}
