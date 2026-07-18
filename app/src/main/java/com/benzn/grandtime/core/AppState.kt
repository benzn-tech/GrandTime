package com.benzn.grandtime.core

import com.benzn.grandtime.auth.MediaScope
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.RawDirection
import com.benzn.grandtime.keymap.KeyAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class ProbeEntry(val timestampMillis: Long, val text: String)

sealed interface LoginState {
    data object LoggedOut : LoginState
    data class LoggedIn(val displayName: String, val authorSub: String? = null) : LoginState
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
    val previewSurface = MutableStateFlow<android.view.Surface?>(null)

    /** 当前媒体作用域:登出=device/kind 前缀,登录=用户目录/用户名前缀。 */
    val mediaScope = MutableStateFlow(MediaScope("device", null))

    /** 当前选中的工地(SiteStore 恢复的持久化选择)。 */
    val selectedSite = MutableStateFlow<SelectedSite?>(null)

    /** 工地列表缓存(启动时由 CoreService 在补扫前预取,SitePickerDialog 直接读,秒开)。 */
    val availableSites = MutableStateFlow<List<com.benzn.grandtime.net.SitesApiClient.SiteOption>>(emptyList())

    /** Site voice: WS connected (UI status dot). */
    val siteVoiceConnected = MutableStateFlow(false)

    /** Site voice owns the mic/speaker right now (recording/sending/playing). */
    val siteVoiceActive = MutableStateFlow(false)

    /** Ask owns the mic/speaker right now — status mirror written by AskManager, read by
     *  SiteVoiceCore for one-talk-at-a-time mutual exclusion. Ask's own behavior is unchanged. */
    val askActive = MutableStateFlow(false)

    /** Recent inbound Site-voice clips (last N, replayable). Written by SiteVoiceManager. */
    val siteVoiceInbox = MutableStateFlow<List<com.benzn.grandtime.sitevoice.VoiceClip>>(emptyList())

    /** UI → service: replay a recent inbox clip by its s3Key. */
    val siteVoiceReplayRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 刚拍摄照片的绝对路径(#81 快门确认闪现);null=无待展示。写入方=CaptureManager,读取方=全局悬浮层。 */
    val lastPhotoFlash = MutableStateFlow<String?>(null)

    fun addProbe(entry: ProbeEntry) {
        probeEntries.value = (listOf(entry) + probeEntries.value).take(PROBE_LIMIT)
    }
}
