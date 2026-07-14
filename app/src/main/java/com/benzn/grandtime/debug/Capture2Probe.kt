package com.benzn.grandtime.debug

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Log
import android.util.Size

/**
 * SP-Capture2-P1 真机可行性探针(临时,P2 完成后移除)。
 * 结果打到 logcat 标签 "CAP2PROBE"。由 DiagnosticsScreen 的临时按钮触发。
 */
class Capture2Probe(private val context: Context) {

    companion object {
        const val TAG = "CAP2PROBE"
    }

    private fun log(s: String) = Log.i(TAG, s)

    suspend fun runAll() {
        log("==== Capture2 probe start ====")
        runCatching { enumerate() }.onFailure { log("enumerate 异常: ${it.javaClass.simpleName}: ${it.message}") }
        // Task2/3/4 后续接入:recordClip / probeConcurrent / setLocation
        log("==== Capture2 probe end ====")
    }

    /** Task 1:不开相机,仅读 CameraCharacteristics + MediaCodecList,枚举尺寸与编码器能力。 */
    fun enumerate() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        if (backId == null) { log("无后置相机"); return }
        val ch = cm.getCameraCharacteristics(backId)
        log("后置 cameraId=$backId hwLevel=${ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}")
        log("activeArray=${ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)} " +
            "pixelArray=${ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) { log("无 StreamConfigurationMap"); return }

        fun report(label: String, sizes: Array<Size>?) {
            if (sizes == null) { log("$label: null"); return }
            val sorted = sizes.sortedByDescending { it.width.toLong() * it.height }
            val max43 = sorted.firstOrNull { it.width * 3 == it.height * 4 }
            val max169 = sorted.firstOrNull { it.width * 9 == it.height * 16 }
            log("$label: max=${sorted.firstOrNull()}  max4:3=$max43  max16:9=$max169  count=${sizes.size}")
            log("$label all=${sorted.joinToString(",") { "${it.width}x${it.height}" }}")
        }
        report("VIDEO(MediaRecorder)", map.getOutputSizes(MediaRecorder::class.java))
        report("JPEG", map.getOutputSizes(ImageFormat.JPEG))
        report("PREVIEW(SurfaceTexture)", map.getOutputSizes(SurfaceTexture::class.java))

        for (mime in listOf("video/hevc", "video/avc")) {
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            if (encoders.isEmpty()) { log("$mime 编码器: 无"); continue }
            for (enc in encoders) {
                val vc: MediaCodecInfo.VideoCapabilities = runCatching {
                    enc.getCapabilitiesForType(mime).videoCapabilities
                }.getOrNull() ?: continue
                log("$mime 编码器 name=${enc.name} " +
                    "W=${vc.supportedWidths} H=${vc.supportedHeights} " +
                    "bitrate=${vc.bitrateRange} fps=${vc.supportedFrameRates}")
            }
        }
    }
}
