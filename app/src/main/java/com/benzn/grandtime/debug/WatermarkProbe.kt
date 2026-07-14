package com.benzn.grandtime.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.benzn.grandtime.capture.camera2.GlRecordPipeline
import com.benzn.grandtime.capture.camera2.SegmentRecorder
import com.benzn.grandtime.capture.camera2.VideoSpec
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * SP-Capture2-P3 水印方向真机定标探针(临时,Task14 随本轮探针一起删除)。
 * 相机 → GlRecordPipeline(叠水印)→ SegmentRecorder,录 ~3s 到 /sdcard/FieldSight/_probe/probe_wm.mp4。
 * 复用正式 setWatermarkBitmap 实现(不另写 GL),只为验证 WM_ROTATION_DEG 是否定标正确。
 * 结果打到 logcat 标签 "WMPROBE"。由 DiagnosticsScreen 的临时按钮触发。
 */
class WatermarkProbe(private val context: Context) {

    companion object {
        const val TAG = "WMPROBE"
    }

    private fun log(s: String) = Log.i(TAG, s)

    /** 相机 → GL(叠水印)→ SegmentRecorder 录 ~3s,1440×1080。返回结果串或错误串。 */
    @SuppressLint("MissingPermission")
    suspend fun run(): String {
        log("==== watermark probe start ====")
        val dir = File("/sdcard/FieldSight/_probe").apply { mkdirs() }
        val out = File(dir, "probe_wm.mp4")
        val rec = SegmentRecorder(::log)
        val spec = VideoSpec(1440, 1080, 20_000_000, 90)
        val gl = GlRecordPipeline()
        val ht = HandlerThread("wmprobe").apply { start() }
        val handler = Handler(ht.looper)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val result = try {
            val encSurface = rec.prepare(out, spec, hevcPreferred = true, location = null, recordAudio = false)
            val stDeferred = CompletableDeferred<SurfaceTexture>()
            gl.start(1440, 1080) { stDeferred.complete(it) }
            val camTex = stDeferred.await()
            gl.setWatermarkBitmap(makeMarkerBitmap()) // 复用正式叠加实现,验证 WM_ROTATION_DEG
            gl.addTarget(encSurface)
            rec.start()
            val camInput = Surface(camTex)
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val cam: CameraDevice = suspendCancellableCoroutine { cont ->
                cm.openCamera(backId, object : CameraDevice.StateCallback() {
                    override fun onOpened(c: CameraDevice) { cont.resume(c) {} }
                    override fun onDisconnected(c: CameraDevice) { c.close() }
                    override fun onError(c: CameraDevice, e: Int) {
                        c.close(); if (cont.isActive) cont.cancel(RuntimeException("openCamera error $e"))
                    }
                }, handler)
            }
            camera = cam
            val sess: CameraCaptureSession = suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOf(camInput), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) {} }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.cancel(RuntimeException("session configure failed"))
                    }
                }, handler)
            }
            session = sess
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(camInput) }.build()
            sess.setRepeatingRequest(req, null, handler)
            delay(3000)
            sess.stopRepeating()
            gl.removeTarget(encSurface)
            rec.stop()
            gl.release()
            "OK codec=${rec.actualCodec} size=${out.length()} path=${out.absolutePath}"
        } catch (e: Throwable) {
            runCatching { rec.stop() }
            runCatching { gl.release() }
            "探针异常: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            ht.quitSafely()
        }
        log("probeGlRecord(watermark) => $result")
        log("==== watermark probe end ====")
        return result
    }
}

/** 透明底 + 顶部黄字 "TOP" + 左上红角(判方向用)。 */
fun makeMarkerBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(480, 120, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.argb(120, 0, 0, 0))
    c.drawRect(0f, 0f, 60f, 60f, Paint().apply { color = Color.RED })
    c.drawText("TOP", 80f, 80f, Paint().apply { color = Color.YELLOW; textSize = 64f; isAntiAlias = true })
    return bmp
}
