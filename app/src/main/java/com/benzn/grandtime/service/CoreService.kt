package com.benzn.grandtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.benzn.grandtime.ui.MainActivity
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.R
import com.benzn.grandtime.ask.AskManager
import com.benzn.grandtime.ask.PttDirection
import com.benzn.grandtime.auth.AuthManager
import com.benzn.grandtime.capture.CaptureManager
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.ProbeEntry
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.SiteStore
import com.benzn.grandtime.core.settingsDataStore
import com.benzn.grandtime.core.siteDataStore
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.hardware.F2spKeyEventSource
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.OnScreenKeyEventSource
import com.benzn.grandtime.keymap.KeyAction
import com.benzn.grandtime.keymap.KeyActionDispatcher
import com.benzn.grandtime.keymap.KeyMapStore
import com.benzn.grandtime.keymap.keymapDataStore
import com.benzn.grandtime.net.SitesApiClient
import com.benzn.grandtime.ui.actionLabel
import com.benzn.grandtime.upload.WorkManagerUploadEnqueuer
import com.benzn.grandtime.upload.uploadRequiresUnmetered
import com.benzn.grandtime.util.ProbeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CoreService : LifecycleService() {

    companion object {
        private const val TAG = "GrandTime"
        private const val CHANNEL_ID = "core"
        private const val NOTIFICATION_ID = 1
        /** BootReceiver 置 true → 开机自动拉起 MainActivity(进 Home / 登录页)。 */
        const val EXTRA_FROM_BOOT = "from_boot"
    }

    private var pipelineStarted = false
    private var bootLaunchDone = false
    private var f2spSource: F2spKeyEventSource? = null
    private var captureManager: CaptureManager? = null
    private var askManager: AskManager? = null
    private var pttSource: com.benzn.grandtime.ask.PttKeySource? = null
    private lateinit var probeLog: ProbeLog
    private lateinit var overlayGuard: OverlayGuard
    private lateinit var captureWakeLock: CaptureWakeLock
    private val led = com.benzn.grandtime.hardware.LedController()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须在 startForegroundService 后尽快前台化,先于任何 suspend 工作
        startForeground(NOTIFICATION_ID, buildNotification("Standing by"))
        probeLog = ProbeLog(File(filesDir, "probe"))
        // 息屏/后台相机访问需要 overlay 窗口维持进程可见态,见 OverlayGuard 注释
        overlayGuard = OverlayGuard(this)
        captureWakeLock = CaptureWakeLock(this)
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
        // Re-apply the screen-off timeout on every start command. onResume re-kicks
        // startForegroundService, so this fires when the user returns from granting WRITE_SETTINGS
        // (the startPipeline collector already fired-and-suppressed while it was still ungranted).
        lifecycleScope.launch {
            val minutes = SettingsStore(applicationContext.settingsDataStore).settings.first().screenOffMinutes
            ScreenTimeoutController.apply(applicationContext, minutes)
        }
        if (!pipelineStarted) {
            pipelineStarted = true
            startPipeline()
        }
        // 开机路径:前台化后延时拉起 MainActivity(仅一次)。延时让窗口子系统就绪、
        // 越过开机早期"抢窗口"竞态;悬浮窗权限提供后台启动 Activity 豁免。登录门决定落 Home 或登录页。
        if (intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true && !bootLaunchDone) {
            bootLaunchDone = true
            android.os.Handler(mainLooper).postDelayed({
                if (Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }, 2_500)
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
            if (AppState.loginState.value is com.benzn.grandtime.core.LoginState.LoggedIn) {
                // 补扫前先预取工地列表进缓存——补扫会瞬间占满网络,若不先取,
                // SitePickerDialog 冷开时的 GET /org/sites 要排在一堆上传后面,能卡到 ~10s。
                val idToken = auth.freshIdToken()
                if (idToken != null) {
                    runCatching {
                        val sites = withContext(Dispatchers.IO) {
                            SitesApiClient(BuildConfig.ORG_API_BASE_URL).listSites(idToken)
                        }
                        AppState.availableSites.value = sites
                        SiteStore(applicationContext.siteDataStore).setSiteList(sites)
                    }
                }
                // 登录态落定后补扫:重新入队从未成功的录制(pending/failed 和被取消卡住的
                // uploading)。关键:**只对文件仍在磁盘的行入队**;已删文件(如上传功能上线
                // 前留下的大量旧测试录制)标 missing=1——被 listByUploadStatus 的 missing=0
                // 过滤排除,不再每次开机重扫。否则几十条缺失行会灌爆 WorkManager 的 worker
                // 池(撞并发上限/10分钟窗口→取消→failed→再扫),把实时拍摄/录制上传饿死。
                // 仅补扫带 20s 初始延迟,避免占满网络挤掉前台;实时上传仍 delay=0 不受影响。
                // Crash recovery: turn any orphan .pcm temp (process killed mid-recording, so
                // AudioRecorder.stop() never ran and the target .wav was never assembled) back
                // into its .wav. Must run before the rescan loop below so the now-present file
                // passes the File(rec.filePath).exists() check and gets enqueued.
                val recovered = withContext(Dispatchers.IO) {
                    com.benzn.grandtime.capture.AudioRecoverer.recover(
                        com.benzn.grandtime.capture.MediaStorage.publicRoot(applicationContext),
                    )
                }
                if (recovered > 0) probe("recovered $recovered interrupted audio recording(s)")
                val dao = CaptureDb.get(applicationContext).captureRecords()
                val enq = WorkManagerUploadEnqueuer(applicationContext)
                // Read once — the rescan is a single pass, no need to react to a mid-scan setting change.
                val wifiOnly = SettingsStore(applicationContext.settingsDataStore).settings.first().videoUploadWifiOnly
                val onDisk = mutableListOf<com.benzn.grandtime.db.CaptureRecord>()
                for (rec in dao.listByUploadStatus(listOf("pending", "failed", "uploading"))) {
                    if (java.io.File(rec.filePath).exists()) onDisk.add(rec)
                    else dao.markMissing(listOf(rec.id))   // 已删文件:标 missing,排除出后续扫描
                }
                // **封顶补扫**:只补最近 10 条磁盘存在的,避免大 backlog 灌爆 WorkManager/网络栈、
                // 拖垮实时上传;更旧的留给用户在 Files 里手动点角标补传。实时上传仍 delay=0。
                onDisk.sortedByDescending { it.startedAt }.take(10)
                    .forEach {
                        enq.enqueue(
                            it.id,
                            initialDelaySeconds = 20,
                            requireUnmetered = uploadRequiresUnmetered(it.kind, wifiOnly),
                        )
                    }
            }
        }
        lifecycleScope.launch {
            keyMapStore.overrides.collect { AppState.overrides.value = it }
        }
        lifecycleScope.launch {
            SiteStore(applicationContext.siteDataStore).site.collect { AppState.selectedSite.value = it }
        }
        // Drive the system screen-off timeout from the app setting so the display sleeps at the
        // chosen minutes (recording and idle). No-op until WRITE_SETTINGS is granted; re-applied on
        // every change and on start (the setting is global and may have drifted).
        lifecycleScope.launch {
            SettingsStore(applicationContext.settingsDataStore).settings
                .map { it.screenOffMinutes }
                .distinctUntilChanged()
                .collect { if (!ScreenTimeoutController.apply(applicationContext, it)) probe("screen-off timeout not applied (WRITE_SETTINGS not granted)") }
        }
        // Keep the CPU awake only while a capture is active, so recording survives the screen sleeping.
        lifecycleScope.launch {
            AppState.captureState.collect { state ->
                val recording = state is com.benzn.grandtime.capture.CaptureState.RecordingVideo ||
                    state is com.benzn.grandtime.capture.CaptureState.RecordingAudio
                if (recording) captureWakeLock.acquire() else captureWakeLock.release()
            }
        }
        // SP3b 物理灯(2 号灯,sysfs)1Hz 闪:录像红 > 录音黄 > 待机蓝。节点不可写则不启。
        if (led.available) {
            lifecycleScope.launch {
                try {
                    while (true) {
                        val color = when (AppState.captureState.value) {
                            is com.benzn.grandtime.capture.CaptureState.RecordingVideo -> com.benzn.grandtime.hardware.LedColor.RED
                            is com.benzn.grandtime.capture.CaptureState.RecordingAudio -> com.benzn.grandtime.hardware.LedColor.YELLOW
                            else -> com.benzn.grandtime.hardware.LedColor.BLUE // 待机
                        }
                        led.show(color)                 // 亮相位:统一 1s
                        kotlinx.coroutines.delay(1000)
                        led.show(null)                  // 灭相位:待机 2s,录像/录音 1s
                        kotlinx.coroutines.delay(
                            if (color == com.benzn.grandtime.hardware.LedColor.BLUE) 2000 else 1000,
                        )
                    }
                } finally {
                    led.off()
                }
            }
        }

        captureManager = CaptureManager(
            context = this,
            scope = lifecycleScope,
            settingsStore = SettingsStore(applicationContext.settingsDataStore),
            dao = CaptureDb.get(applicationContext).captureRecords(),
            notify = ::notifyStatus,
            probe = ::probe,
            uploadEnqueuer = WorkManagerUploadEnqueuer(applicationContext),
        )

        val ask = AskManager(
            context = this,
            scope = lifecycleScope,
            auth = auth,
            apiBaseUrl = BuildConfig.ORG_API_BASE_URL,
            probe = ::probe,
        )
        askManager = ask
        val ptt = com.benzn.grandtime.ask.PttKeySource(this)
        pttSource = ptt
        lifecycleScope.launch {
            ptt.events.collect { dir ->
                probe("ptt ${dir.name}")
                when (dir) {
                    PttDirection.DOWN -> ask.onPttDown()
                    PttDirection.UP -> ask.onPttUp()
                }
            }
        }

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
        ptt.start()

        AppState.serviceRunning.value = true
        probe("service started")
    }

    private fun handleAction(action: KeyAction, press: KeyPress) {
        probe("${press.key.name} ${press.pressType.name} → ${action.name}")
        val manager = captureManager
        if (manager != null && action in manager.handledActions) {
            manager.handle(action)
        } else if (action == KeyAction.ASK_AGENT) {
            askManager?.onDiscreteAsk()
        } else {
            val text = "[stub] ${actionLabel(action)}"
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
        pttSource?.stop()
        askManager?.shutdown()
        overlayGuard.hide()
        captureWakeLock.release()
        super.onDestroy()
    }
}
