package com.benzn.grandtime.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 息屏/后台时相机与麦克风访问需要进程被系统视为"可见"——仅持有
 * SYSTEM_ALERT_WINDOW 权限不够,系统的 AppOps OP_CAMERA/OP_RECORD_AUDIO
 * 在 UID 变为非 top/后台态时会置为 IGNORED,CameraService 随即拒绝连接
 * ("Access for <pkg> has been restricted" / ERROR_CAMERA_DISABLED,
 * 真机 F2S202503103054 场景 2 实测复现:视频段落地 0 字节)。
 * 挂一个 1x1 透明、不可点击、不获焦的 overlay 窗口是该限制的标准规避方式。
 */
class OverlayGuard(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return
        val view = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { windowManager.addView(view, params) }.onSuccess { overlayView = view }
    }

    fun hide() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }
}
