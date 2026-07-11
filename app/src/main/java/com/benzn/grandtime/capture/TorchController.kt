package com.benzn.grandtime.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** 手电:相机已绑定时走 CameraControl,空闲时走 CameraManager.setTorchMode。 */
class TorchController(
    private val context: Context,
    private val session: CameraSession,
) {
    var torchOn: Boolean = false
        private set

    fun toggle() {
        torchOn = !torchOn
        val camera = session.camera
        if (camera != null && camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(torchOn)
            return
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backWithFlash = cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (backWithFlash == null) {
            torchOn = !torchOn
            return
        }
        try {
            cm.setTorchMode(backWithFlash, torchOn)
        } catch (e: Exception) {
            torchOn = !torchOn
        }
    }
}
