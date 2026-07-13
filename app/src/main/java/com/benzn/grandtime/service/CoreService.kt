package com.benzn.grandtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.R
import com.benzn.grandtime.auth.AuthManager
import com.benzn.grandtime.capture.CaptureManager
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.ProbeEntry
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.settingsDataStore
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.hardware.F2spKeyEventSource
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.OnScreenKeyEventSource
import com.benzn.grandtime.keymap.KeyAction
import com.benzn.grandtime.keymap.KeyActionDispatcher
import com.benzn.grandtime.keymap.KeyMapStore
import com.benzn.grandtime.keymap.keymapDataStore
import com.benzn.grandtime.ui.actionLabel
import com.benzn.grandtime.util.ProbeLog
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CoreService : LifecycleService() {

    companion object {
        private const val TAG = "GrandTime"
        private const val CHANNEL_ID = "core"
        private const val NOTIFICATION_ID = 1
    }

    private var pipelineStarted = false
    private var f2spSource: F2spKeyEventSource? = null
    private var captureManager: CaptureManager? = null
    private lateinit var probeLog: ProbeLog
    private lateinit var overlayGuard: OverlayGuard
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须在 startForegroundService 后尽快前台化,先于任何 suspend 工作
        startForeground(NOTIFICATION_ID, buildNotification("Standing by"))
        probeLog = ProbeLog(File(filesDir, "probe"))
        // 息屏/后台相机访问需要 overlay 窗口维持进程可见态,见 OverlayGuard 注释
        overlayGuard = OverlayGuard(this)
        overlayGuard.show()
        // 开机自启路径:BOOT_COMPLETED 拉起的 FGS 在 Android 12+ 拿不到相机/麦克风
        // while-in-use 权限(allowWhileInUsePermissionInFgs=false,UID capability 缺 C/M,
        // CameraService 报 "Access has been restricted"/ERROR_CAMERA_DISABLED——真机场景 7
        // 实测复现;Android 11 的 SAW 豁免在 12+ 已被移除)。但 AMS 在每次 startService
        // 都会重新评估该标志,且"UID 有可见非 toast 窗口"(即上面的 OverlayGuard)可通过
        // isUidForeground 豁免——标志只升不降。窗口要等首帧绘制后才在 WMS 中可见,
        // 故延迟重踢两次(1s 快路径 + 4s 兜底),onStartCommand 幂等,重复无害。
        val rekick = Runnable { startService(Intent(this, CoreService::class.java)) }
        val handler = android.os.Handler(mainLooper)
        handler.postDelayed(rekick, 1_000)
        handler.postDelayed(rekick, 4_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 幂等:SAW 权限可能在服务已运行后才授予(如用户从设置页返回),
        // MainActivity.onResume 会重踢 startForegroundService 触发这里——
        // show() 内部对"已显示"或"SAW 未授权"均 no-op,重复调用无害。
        overlayGuard.show()
        if (!pipelineStarted) {
            pipelineStarted = true
            startPipeline()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        val auth: AuthManager = (application as GrandTimeApp).authManager
        val keyMapStore = KeyMapStore(applicationContext.keymapDataStore)

        // 先构造两个源(不 start),等下面所有收集协程都挂上订阅后再 start()——
        // MutableSharedFlow(replay=0) 会丢弃订阅前发出的事件,顺序反了会丢首个按键。
        // 刻意偏离规格:F2sp 与 OnScreen 两个源始终同时运行(DeviceProfile 仅用于展示,不做二选一)——F2sp 在模拟器上需要 adb 广播驱动 E2E,而 OnScreen 虚拟键在真机上无害。
        val f2sp = F2spKeyEventSource(this, lifecycleScope)
        f2spSource = f2sp
        val onScreen = OnScreenKeyEventSource(lifecycleScope)

        lifecycleScope.launch {
            AppState.screenKeyEvents.collect { (key, direction) -> onScreen.onScreenKey(key, direction) }
        }
        // 拆成两个独立协程:collect 先挂上订阅,silentLogin() 单独启动——
        // 避免未来 silentLogin() 若抛异常,导致同一协程内尚未执行到的 collect 永远不会挂上,AppState 停止镜像登录态。
        lifecycleScope.launch {
            auth.loginState.collect { AppState.loginState.value = it }
        }
        lifecycleScope.launch {
            auth.silentLogin()
        }
        lifecycleScope.launch {
            keyMapStore.overrides.collect { AppState.overrides.value = it }
        }

        captureManager = CaptureManager(
            context = this,
            scope = lifecycleScope,
            settingsStore = SettingsStore(applicationContext.settingsDataStore),
            dao = CaptureDb.get(applicationContext).captureRecords(),
            notify = ::notifyStatus,
            probe = ::probe,
        )

        val dispatcher = KeyActionDispatcher({ AppState.overrides.value }, ::handleAction)
        lifecycleScope.launch {
            merge(f2sp.keyPresses, onScreen.keyPresses).collect { press ->
                probe("${press.key.name} ${press.pressType.name}")
                dispatcher.dispatch(press)
            }
        }
        lifecycleScope.launch {
            merge(f2sp.rawEvents, onScreen.rawEvents).collect { probe(it.action) }
        }

        // 所有收集协程已挂上订阅,现在才 start() 广播接收器,避免早期事件被 replay=0 丢弃。
        f2sp.start()

        AppState.serviceRunning.value = true
        probe("service started")
    }

    private fun handleAction(action: KeyAction, press: KeyPress) {
        probe("${press.key.name} ${press.pressType.name} → ${action.name}")
        val manager = captureManager
        if (manager != null && action in manager.handledActions) {
            manager.handle(action)
        } else {
            val text = when (action) {
                KeyAction.ASK_AGENT -> "Ask agent coming soon"
                else -> "[stub] ${actionLabel(action)}"
            }
            AppState.lastAction.value = text
            notifyStatus(text)
        }
    }

    private fun probe(text: String) {
        val now = System.currentTimeMillis()
        AppState.addProbe(ProbeEntry(now, text))
        probeLog.append("${timeFormat.format(Date(now))} $text")
        Log.i(TAG, text)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "FieldSight service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fieldsight)
            .setContentTitle("FieldSight")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun notifyStatus(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        AppState.serviceRunning.value = false
        captureManager?.shutdown()
        f2spSource?.stop()
        overlayGuard.hide()
        super.onDestroy()
    }
}
