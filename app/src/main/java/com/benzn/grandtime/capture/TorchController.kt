package com.benzn.grandtime.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.benzn.grandtime.capture.camera2.Camera2Pipeline

/** 手电:管线会话活时经 repeating 请求(FLASH_MODE_TORCH),空闲走 CameraManager.setTorchMode。 */
class TorchController(
    private val context: Context,
    private val pipeline: Camera2Pipeline,
) {
    var torchOn: Boolean = false
        private set

    fun toggle() {
        torchOn = !torchOn
        // 管线会话活 → 管线处理(返回 true);否则走 CameraManager。
        if (pipeline.setTorch(torchOn)) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backWithFlash = cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (backWithFlash == null) { torchOn = !torchOn; return }
        try { cm.setTorchMode(backWithFlash, torchOn) } catch (e: Exception) { torchOn = !torchOn }
    }
}
