package com.benzn.grandtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.benzn.grandtime.R
import com.benzn.grandtime.auth.AuthManager
import com.benzn.grandtime.auth.StubAuthManager
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.ProbeEntry
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
    private lateinit var probeLog: ProbeLog
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须在 startForegroundService 后尽快前台化,先于任何 suspend 工作
        startForeground(NOTIFICATION_ID, buildNotification("待命"))
        probeLog = ProbeLog(File(filesDir, "probe"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!pipelineStarted) {
            pipelineStarted = true
            startPipeline()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        val auth: AuthManager = StubAuthManager()
        val keyMapStore = KeyMapStore(applicationContext.keymapDataStore)

        // 先构造两个源(不 start),等下面所有收集协程都挂上订阅后再 start()——
        // MutableSharedFlow(replay=0) 会丢弃订阅前发出的事件,顺序反了会丢首个按键。
        val f2sp = F2spKeyEventSource(this, lifecycleScope)
        f2spSource = f2sp
        val onScreen = OnScreenKeyEventSource(lifecycleScope)

        lifecycleScope.launch {
            AppState.screenKeyEvents.collect { (key, direction) -> onScreen.onScreenKey(key, direction) }
        }
        lifecycleScope.launch {
            auth.silentLogin()
            auth.loginState.collect { AppState.loginState.value = it }
        }
        lifecycleScope.launch {
            keyMapStore.overrides.collect { AppState.overrides.value = it }
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

        AppState.serviceRunning.value = true
        probe("service started")
    }

    private fun handleAction(action: KeyAction, press: KeyPress) {
        val text = "[桩] ${actionLabel(action)}"
        AppState.lastAction.value = text
        probe("${press.key.name} ${press.pressType.name} → ${action.name}")
        notifyStatus(text)
    }

    private fun probe(text: String) {
        val now = System.currentTimeMillis()
        AppState.addProbe(ProbeEntry(now, text))
        probeLog.append("${timeFormat.format(Date(now))} $text")
        Log.i(TAG, text)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "GrandTime 常驻", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_grandtime)
            .setContentTitle("GrandTime")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun notifyStatus(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        AppState.serviceRunning.value = false
        f2spSource?.stop()
        super.onDestroy()
    }
}
