package com.benzn.grandtime.core

import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.RawDirection
import com.benzn.grandtime.keymap.KeyAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class ProbeEntry(val timestampMillis: Long, val text: String)

sealed interface LoginState {
    data object LoggedOut : LoginState
    data class LoggedIn(val displayName: String) : LoginState
}

/** Service 写、Compose 读的全局状态仓。 */
object AppState {
    const val PROBE_LIMIT = 200

    val serviceRunning = MutableStateFlow(false)
    val loginState = MutableStateFlow<LoginState>(LoginState.LoggedOut)
    val overrides = MutableStateFlow<Map<String, KeyAction>>(emptyMap())
    val probeEntries = MutableStateFlow<List<ProbeEntry>>(emptyList())
    val lastAction = MutableStateFlow<String?>(null)

    /** 采集状态(Service 写,Home 卡读)。 */
    val captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)

    /** UI 屏幕按键 → Service(down/up 原始事件)。 */
    val screenKeyEvents = MutableSharedFlow<Pair<HardKey, RawDirection>>(extraBufferCapacity = 16)

    /** RecordingScreen 提供的预览 surface(前台可见时非 null)。 */
    val previewSurface = MutableStateFlow<androidx.camera.core.Preview.SurfaceProvider?>(null)

    /** 自动熄屏请求(录像满 N 分钟置 true;UI 观察后释放屏幕常亮)。 */
    val screenOffRequest = MutableStateFlow(false)

    fun addProbe(entry: ProbeEntry) {
        probeEntries.value = (listOf(entry) + probeEntries.value).take(PROBE_LIMIT)
    }
}
