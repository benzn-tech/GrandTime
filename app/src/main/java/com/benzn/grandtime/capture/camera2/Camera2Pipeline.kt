package com.benzn.grandtime.capture.camera2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.benzn.grandtime.core.AspectRatio
import com.benzn.grandtime.core.VideoQuality
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Camera2 录制/拍照门面。会话固定 2 路输出:GL 相机 SurfaceTexture 面 + JPEG ImageReader。
 * GL 把相机帧画到编码器输入面(录像段)与预览面(挂了才画)。CaptureManager 只调本类。
 */
class Camera2Pipeline(
    private val context: Context,
    private val probe: (String) -> Unit = {},
) {
    private val camThread = HandlerThread("cam2").apply { start() }
    private val handler = Handler(camThread.looper)

    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var jpegReader: ImageReader? = null
    @Volatile private var gl: GlRecordPipeline? = null
    @Volatile private var cameraSurface: Surface? = null
    @Volatile private var segment: SegmentRecorder? = null
    @Volatile private var previewSurface: Surface? = null
    // Fixed 90, NOT read from CameraCharacteristics: this ROM reports SENSOR_ORIENTATION=0 for both
    // cameras, which is false — playback provably needs a 90° hint (T1 device probe + acceptance).
    // Reading the characteristic silently flipped orientationHint/JPEG_ORIENTATION to 0 for every
    // session after the first camera open (first session kept this default and looked fine).
    // Photo JPEG orientation hint (camera HAL writes EXIF rotation for stills).
    private val sensorOrientation = 90
    // Video needs NO playback rotation: the GL pipeline already renders camera frames upright
    // (verified on device — raw 960x720 frames are upright landscape). So the muxer orientationHint
    // is 0 and the encoder watermark is drawn un-prerotated (prerotate=false), making the recorded
    // MP4 match the live preview (WYSIWYG). Previously hint=90 + a prerotated watermark rotated the
    // already-upright content into sideways portrait at playback (bug: 4:3 preview -> 3:4 rotated).
    private val videoOrientationHint = 0
    @Volatile private var torchOn = false
    @Volatile private var activeSpec: VideoSpec? = null
    @Volatile private var photoInFlight = false
    @Volatile private var pendingWatermark: android.graphics.Bitmap? = null

    /** 相机死亡(被抢占/热降频/HAL 错)通知上层,便于状态机退出录像态。 */
    @Volatile var onCameraLost: (() -> Unit)? = null

    val isRecording: Boolean get() = segment != null

    data class SegmentResult(val codec: String, val resolution: String)

    private fun cm() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun backId(): String = cm().cameraIdList.first {
        cm().getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private fun supportedVideoSizes(id: String): List<VideoSize> {
        val map = cm().getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.map { VideoSize(it.width, it.height) } ?: emptyList()
    }

    /** 相机死亡(被抢占/热降频/HAL 错):停在飞段(免线程泄漏)+ 全拆会话资源(免下次 ensureSession 覆盖泄漏)+ 通知上层。 */
    private fun invalidateOnCameraLost(reason: String) {
        val rec = segment; val enc = currentEncSurface
        segment = null; currentEncSurface = null; onFinalizedCb = null
        if (rec != null) {
            if (enc != null) gl?.removeTarget(enc)
            Thread { runCatching { rec.stop() } }.apply { name = "seg-lost-teardown"; start() }
        }
        runCatching { session?.close() }; session = null
        runCatching { jpegReader?.close() }; jpegReader = null
        runCatching { gl?.release() }; gl = null
        runCatching { cameraSurface?.release() }; cameraSurface = null
        activeSpec = null
        probe("camera lost: $reason")
        onCameraLost?.invoke()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraIfNeeded(): CameraDevice {
        camera?.let { return it }
        val id = backId()
        val cam: CameraDevice = suspendCancellableCoroutine { cont ->
            cm().openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { cont.resume(c) }
                override fun onDisconnected(c: CameraDevice) {
                    c.close()
                    if (camera == c) { camera = null; invalidateOnCameraLost("disconnected") }
                }
                override fun onError(c: CameraDevice, e: Int) {
                    c.close()
                    if (camera == c) { camera = null; invalidateOnCameraLost("error $e") }
                    if (cont.isActive) cont.cancel(RuntimeException("openCamera $e"))
                }
            }, handler)
        }
        camera = cam
        return cam
    }

    /** 建/复用会话([glCameraSurface, jpegReader])。GL 首次建时创建相机 SurfaceTexture。 */
    private suspend fun ensureSession(spec: VideoSpec) {
        if (session != null) return
        val cam = openCameraIfNeeded()
        // JPEG reader:满 5MP 4:3(拍照精度在 takePhoto 时不重开会话,固定用最大档取证)
        val jpegSize = pickJpegSize(backId())
        val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        jpegReader = reader
        // GL 相机纹理(缓冲设编码尺寸;透传绘制到编码器面按其自身尺寸)
        val glp = GlRecordPipeline()
        gl = glp
        val stDeferred = kotlinx.coroutines.CompletableDeferred<android.graphics.SurfaceTexture>()
        glp.start(spec.width, spec.height) { stDeferred.complete(it) }
        if (pendingWatermark != null) glp.setWatermarkBitmap(pendingWatermark)
        val camTex = stDeferred.await()
        val camSurface = Surface(camTex)
        cameraSurface = camSurface
        session = suspendCancellableCoroutine { cont ->
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(camSurface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) { if (cont.isActive) cont.cancel(RuntimeException("session configure failed")) }
            }, handler)
        }
        applyRepeating()
    }

    private fun pickJpegSize(id: String): Size {
        val map = cm().getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        return sizes.filter { it.width * 3 == it.height * 4 }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1440, 1080)
    }

    /** 重设 repeating 请求:目标=相机 GL 面(录像/预览的帧源);带手电标志。 */
    private fun applyRepeating() {
        val cam = camera ?: return
        val s = session ?: return
        val camSurface = cameraSurface ?: return
        runCatching {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(camSurface)
                set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }.build()
            s.setRepeatingRequest(req, null, handler)
        }
    }

    suspend fun startSegment(
        file: File,
        aspect: AspectRatio,
        quality: VideoQuality,
        hevcPreferred: Boolean,
        location: Pair<Float, Float>?,
        onFinalized: (error: Boolean, message: String?) -> Unit,
    ): SegmentResult? {
        var addedEnc: Surface? = null
        var rec: SegmentRecorder? = null
        return try {
            val spec = activeSpec ?: run {
                val size = VideoSizeSelector.pickSize(aspect, quality, supportedVideoSizes(backId()))
                VideoSpec(size.width, size.height, VideoSizeSelector.bitRateFor(quality), videoOrientationHint)
            }
            ensureSession(spec)
            activeSpec = spec
            val recorder = SegmentRecorder(probe)
            rec = recorder
            val encSurface = recorder.prepare(file, spec, hevcPreferred, location, recordAudio = true)
            gl!!.addTarget(encSurface, prerotate = false); addedEnc = encSurface
            recorder.start()
            segment = recorder
            onFinalizedCb = onFinalized
            currentEncSurface = encSurface
            SegmentResult(recorder.actualCodec, "${spec.width}x${spec.height}")
        } catch (e: Exception) {
            probe("startSegment 失败: ${e.message}")
            addedEnc?.let { gl?.removeTarget(it) }
            runCatching { rec?.stop() }
            null
        }
    }

    @Volatile private var onFinalizedCb: ((Boolean, String?) -> Unit)? = null
    @Volatile private var currentEncSurface: Surface? = null

    /** 结束当前段:GL 停画编码器面 → SegmentRecorder.stop → 回调。drain 阻塞放独立线程,不堵相机 handler。 */
    fun stopSegment() {
        val rec = segment ?: return
        val enc = currentEncSurface
        val cb = onFinalizedCb
        segment = null; currentEncSurface = null; onFinalizedCb = null
        if (enc != null) gl?.removeTarget(enc)   // 入队 GL 摘目标(GL 线程异步处理,快)
        Thread {
            runCatching { rec.stop() }           // drain join 阻塞在此独立线程,不堵相机 handler
            cb?.invoke(false, null)
        }.apply { name = "seg-teardown"; start() }
    }

    /** Idle/录音态拍照前确保会话存在并让 3A 收敛;录像中已有会话则 no-op。 */
    suspend fun prepareForPhoto(aspect: AspectRatio, quality: VideoQuality) {
        if (session != null) return
        val size = VideoSizeSelector.pickSize(aspect, quality, supportedVideoSizes(backId()))
        ensureSession(VideoSpec(size.width, size.height, VideoSizeSelector.bitRateFor(quality), videoOrientationHint))
        kotlinx.coroutines.delay(400) // 让 AE/AF 收敛,避免首帧过暗/失焦
    }

    /** 拍照:对 jpegReader 发 STILL_CAPTURE(录像中也可,满 5MP)。 */
    suspend fun takePhoto(file: File, jpegQuality: Int): Boolean {
        val cam = camera ?: return false
        val s = session ?: return false
        val reader = jpegReader ?: return false
        if (photoInFlight) return false
        photoInFlight = true
        return try {
            kotlinx.coroutines.withTimeoutOrNull(3000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    cont.invokeOnCancellation { runCatching { reader.setOnImageAvailableListener(null, handler) } }
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage()
                        var ok = false
                        if (img != null) {
                            runCatching {
                                val buf = img.planes[0].buffer
                                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                                file.outputStream().use { it.write(bytes) }
                                ok = true
                            }
                            img.close()
                        }
                        runCatching { reader.setOnImageAvailableListener(null, handler) }
                        if (cont.isActive) cont.resume(ok) {}
                    }, handler)
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                        set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                        set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                    }.build()
                    runCatching { s.capture(req, null, handler) }.onFailure {
                        runCatching { reader.setOnImageAvailableListener(null, handler) }
                        if (cont.isActive) cont.resume(false) {}
                    }
                } ?: false
            } ?: false
        } finally {
            photoInFlight = false
        }
    }

    /** 预览面挂/摘:只切 GL 目标,不动相机会话(录像不中断)。 */
    fun setPreviewSurface(surface: Surface?) {
        val glp = gl
        val old = previewSurface
        previewSurface = surface
        if (glp == null) return
        if (old != null && old != surface) glp.removeTarget(old)
        if (surface != null) glp.addTarget(surface, prerotate = false)
    }

    /** 会话活时经 repeating 请求切手电;返回是否已处理(false=调用方走 CameraManager)。 */
    fun setTorch(on: Boolean): Boolean {
        torchOn = on
        return if (session != null) { applyRepeating(); true } else false
    }

    /** 水印位图转发给 GL 叠加层;会话未建时记住,ensureSession 里补设。null=不叠。 */
    fun setWatermarkBitmap(bmp: android.graphics.Bitmap?) {
        pendingWatermark = bmp
        gl?.setWatermarkBitmap(bmp)
    }

    suspend fun release() {
        val rec = segment
        val enc = currentEncSurface
        segment = null; currentEncSurface = null; onFinalizedCb = null
        if (rec != null) {
            if (enc != null) gl?.removeTarget(enc)   // 先摘 GL 编码目标,避免 GL 画到已释放的 Surface
            withContext(Dispatchers.IO) { runCatching { rec.stop() } }  // drain 阻塞放 IO,避免主线程 ANR
        }
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { jpegReader?.close() }; jpegReader = null
        runCatching { gl?.release() }; gl = null
        runCatching { cameraSurface?.release() }; cameraSurface = null
        previewSurface = null; torchOn = false
        activeSpec = null
        pendingWatermark = null
    }
}
