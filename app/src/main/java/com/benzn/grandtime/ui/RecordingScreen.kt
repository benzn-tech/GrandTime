package com.benzn.grandtime.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.AspectRatio
import com.benzn.grandtime.core.RecordingSettings
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.settingsDataStore
import com.benzn.grandtime.capture.CaptureState
import kotlinx.coroutines.delay

@Composable
fun RecordingScreen(onStop: () -> Unit) {
    val context = LocalContext.current
    val capture by AppState.captureState.collectAsStateWithLifecycle()
    val screenOff by AppState.screenOffRequest.collectAsStateWithLifecycle()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMillis = System.currentTimeMillis(); delay(1000) } }

    // 预览 surface 交给 CaptureManager;熄屏请求未到时保持屏幕常亮。
    // FIT_CENTER(非默认 FILL_CENTER):录制帧是 16:9/4:3 横向,屏幕是竖屏容器,
    // 填满竖屏会裁掉画面两端 → 看起来"比例不对"/像被放大裁切。
    // FIT_CENTER 完整显示录制帧(上下留黑边),做到"所见即所录"——
    // 参见 Goal A 结论:实际录制并未裁横向 FOV,裁的只是竖直方向,问题出在预览的裁切显示上。
    val activity = remember(context) { context as? android.app.Activity }
    val surfaceView = remember {
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { AppState.previewSurface.value = h.surface }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { AppState.previewSurface.value = h.surface }
                override fun surfaceDestroyed(h: SurfaceHolder) { AppState.previewSurface.value = null }
            })
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            AppState.previewSurface.value = null
            // 安全网:离开录像屏时兜底清掉常亮标志,防止 LaunchedEffect(screenOff) 的
            // 常规路径因某种原因未执行(如页面被直接销毁)而漏清,导致标志泄漏到其他页面。
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(screenOff) {
        val w = activity?.window ?: return@LaunchedEffect
        if (screenOff) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    val settingsStore = remember { SettingsStore(context.settingsDataStore) }
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = RecordingSettings())
    val ratio = if (settings.aspectRatio == AspectRatio.RATIO_16_9) 16f / 9f else 4f / 3f

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 竖屏容器里按录制宽高比 FIT_CENTER(等价旧 PreviewView.FIT_CENTER,上下留黑)
            val cw = maxWidth
            val ch = maxHeight
            val fitH = minOf(ch, cw / ratio)
            val fitW = fitH * ratio
            AndroidView(factory = { surfaceView }, modifier = Modifier.width(fitW).height(fitH))
        }
        val start = (capture as? CaptureState.RecordingVideo)?.startedAtMillis
        Row(
            Modifier.align(Alignment.TopStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(8.dp))
            Text(
                "REC ${mmss((nowMillis - (start ?: nowMillis)))}",
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
        }
        Button(
            onClick = onStop,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White,
            ),
        ) { Text("Stop") }
    }
}

private fun mmss(elapsed: Long): String {
    val t = (elapsed / 1000).coerceAtLeast(0); return "%02d:%02d".format(t / 60, t % 60)
}
