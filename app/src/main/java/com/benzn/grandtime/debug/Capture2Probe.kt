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
        runCatching { enumerateFov() }.onFailure { log("enumerateFov 异常: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching { enumerate() }.onFailure { log("enumerate 异常: ${it.javaClass.simpleName}: ${it.message}") }
        // Task2:HEVC 端到端,失败降 H.264
        val hevc = runCatching { recordClip(useHevc = true) }
            .getOrElse { "HEVC 抛异常: ${it.javaClass.simpleName}: ${it.message}" }
        log("recordClip(HEVC) => $hevc")
        if (hevc.startsWith("HEVC")) {
            val avc = runCatching { recordClip(useHevc = false) }
                .getOrElse { "AVC 抛异常: ${it.javaClass.simpleName}: ${it.message}" }
            log("recordClip(AVC 降级) => $avc")
        }
        // Task3:三流并发(编码器+拍照+预览),验录像中拍照
        val conc = runCatching { probeConcurrent() }
            .getOrElse { "concurrent 抛异常: ${it.javaClass.simpleName}: ${it.message}" }
        log("probeConcurrent => $conc")
        log("==== Capture2 probe end ====")
    }

    /** Task 2:Camera2 → MediaCodec(hevc/avc)→ MediaMuxer 出 mp4,录 ~3s。返回路径或错误串。 */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun recordClip(useHevc: Boolean): String {
        val mime = if (useHevc) "video/hevc" else "video/avc"
        val w = 1440; val h = 1080 // 4:3,在 1920x1088 编码器上限内(测满宽 4:3 广角)
        val dir = java.io.File("/sdcard/FieldSight/_probe").apply { mkdirs() }
        val out = java.io.File(dir, "probe_${if (useHevc) "hevc" else "avc"}.mp4")
        val ht = android.os.HandlerThread("cap2probe").apply { start() }
        val handler = android.os.Handler(ht.looper)
        var codec: android.media.MediaCodec? = null
        var camera: android.hardware.camera2.CameraDevice? = null
        var session: android.hardware.camera2.CameraCaptureSession? = null
        var muxer: android.media.MediaMuxer? = null
        try {
            // 编码器
            val fmt = android.media.MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, 20_000_000)
                setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = android.media.MediaCodec.createEncoderByType(mime)
            codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()
            // muxer(Task4:setLocation)
            muxer = android.media.MediaMuxer(out.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            runCatching { muxer.setLocation(-36.85f, 174.76f) }.onFailure { log("setLocation 失败: ${it.message}") }
            // 开相机 + 建会话(协程桥)
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val cam: android.hardware.camera2.CameraDevice = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cm.openCamera(backId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(c: android.hardware.camera2.CameraDevice) { cont.resume(c) {} }
                    override fun onDisconnected(c: android.hardware.camera2.CameraDevice) { c.close() }
                    override fun onError(c: android.hardware.camera2.CameraDevice, e: Int) {
                        c.close(); if (cont.isActive) cont.cancel(RuntimeException("openCamera error $e"))
                    }
                }, handler)
            }
            camera = cam
            val sess: android.hardware.camera2.CameraCaptureSession = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOf(inputSurface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) { cont.resume(s) {} }
                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                        if (cont.isActive) cont.cancel(RuntimeException("session configure failed"))
                    }
                }, handler)
            }
            session = sess
            val req = cam.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(inputSurface)
            }.build()
            sess.setRepeatingRequest(req, null, handler)

            // drain 编码器 ~3s
            val info = android.media.MediaCodec.BufferInfo()
            var trackIdx = -1
            var muxerStarted = false
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIdx = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                } else if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxerStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIdx, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
            }
            sess.stopRepeating()
            codec.signalEndOfInputStream()
            // drain 剩余
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx < 0) break
                val buf = codec.getOutputBuffer(idx)!!
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxerStarted) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIdx, buf, info)
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            return "OK path=${out.absolutePath} size=${out.length()} ${w}x$h $mime"
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { codec?.stop(); codec?.release() }
            runCatching { muxer?.stop(); muxer?.release() }
            ht.quitSafely()
        }
    }

    /** 广角调查:枚举全部 cameraId 的 FOV(视场角)+ 变焦范围,判断是否有更广的镜头/0.5x 是否成立。 */
    fun enumerateFov() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        log("---- FOV / 变焦调查:全部 cameraId=${cm.cameraIdList.joinToString()} ----")
        for (id in cm.cameraIdList) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                else -> "EXT"
            }
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) // mm
            val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) // mm
            // 水平/垂直/对角 FOV(度)= 2*atan(尺寸 / (2*焦距))
            val fovStr = if (phys != null && focals != null && focals.isNotEmpty()) {
                focals.joinToString(" | ") { f ->
                    val hDeg = Math.toDegrees(2.0 * Math.atan((phys.width / (2.0 * f)).toDouble()))
                    val vDeg = Math.toDegrees(2.0 * Math.atan((phys.height / (2.0 * f)).toDouble()))
                    val dDeg = Math.toDegrees(2.0 * Math.atan((Math.hypot(phys.width.toDouble(), phys.height.toDouble()) / (2.0 * f))))
                    "f=${f}mm H=${"%.1f".format(hDeg)}° V=${"%.1f".format(vDeg)}° D=${"%.1f".format(dDeg)}°"
                }
            } else "无焦距/物理尺寸"
            val zoomRange = runCatching { ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }.getOrNull()
            val maxDigital = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            val physIds = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) ch.physicalCameraIds else emptySet<String>()
            }.getOrDefault(emptySet())
            log("cam=$id facing=$facing physSize=${phys?.width}x${phys?.height}mm activeArray=${ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}")
            log("  $fovStr")
            log("  zoomRatioRange=$zoomRange maxDigitalZoom=$maxDigital physicalCams=$physIds")
        }
        log("---- FOV 调查结束(zoomRatioRange 下限<1.0 = 支持 0.5x 广角变焦) ----")
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

    /** Task 3:编码器 + JPEG ImageReader(满5MP)+ 预览 SurfaceTexture 三流;录像中单拍一张。 */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun probeConcurrent(): String {
        val w = 1440; val h = 1080
        val ht = android.os.HandlerThread("cap2conc").apply { start() }
        val handler = android.os.Handler(ht.looper)
        var codec: android.media.MediaCodec? = null
        var camera: android.hardware.camera2.CameraDevice? = null
        var session: android.hardware.camera2.CameraCaptureSession? = null
        var jpegReader: android.media.ImageReader? = null
        var previewTex: android.graphics.SurfaceTexture? = null
        var previewSurface: android.view.Surface? = null
        try {
            val fmt = android.media.MediaFormat.createVideoFormat("video/hevc", w, h).apply {
                setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, 20_000_000)
                setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = android.media.MediaCodec.createEncoderByType("video/hevc")
            codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = codec.createInputSurface(); codec.start()
            // JPEG reader 满 5MP(4:3)
            jpegReader = android.media.ImageReader.newInstance(2592, 1944, ImageFormat.JPEG, 2)
            // 预览 SurfaceTexture(消费掉帧,不显示)
            previewTex = SurfaceTexture(0).apply { setDefaultBufferSize(w, h) }
            previewSurface = android.view.Surface(previewTex)

            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val cam: android.hardware.camera2.CameraDevice = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cm.openCamera(backId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(c: android.hardware.camera2.CameraDevice) { cont.resume(c) {} }
                    override fun onDisconnected(c: android.hardware.camera2.CameraDevice) { c.close() }
                    override fun onError(c: android.hardware.camera2.CameraDevice, e: Int) {
                        c.close(); if (cont.isActive) cont.cancel(RuntimeException("openCamera error $e"))
                    }
                }, handler)
            }
            camera = cam
            val (sess, streams) = try {
                configureSession(cam, listOf(encSurface, jpegReader.surface, previewSurface), handler) to 3
            } catch (e: Throwable) {
                log("三流配置失败(${e.message}),退化测 encoder+jpeg 双流")
                configureSession(cam, listOf(encSurface, jpegReader.surface), handler) to 2
            }
            session = sess
            // 录像请求(编码器 + 预览若三流)
            val recTargets = if (streams == 3) listOf(encSurface, previewSurface) else listOf(encSurface)
            val recReq = cam.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD).apply {
                recTargets.forEach { addTarget(it) }
            }.build()
            sess.setRepeatingRequest(recReq, null, handler)
            // 空跑编码器输出(不落盘,只为不堵)
            val info = android.media.MediaCodec.BufferInfo()
            val encThread = Thread {
                val dl = System.currentTimeMillis() + 3500
                while (System.currentTimeMillis() < dl) {
                    val i = runCatching { codec.dequeueOutputBuffer(info, 10_000) }.getOrDefault(-1)
                    if (i >= 0) runCatching { codec.releaseOutputBuffer(i, false) }
                }
            }.apply { start() }
            // 录像中拍一张(满 5MP)
            var jpegDims = "未拍到"
            val latch = java.util.concurrent.CountDownLatch(1)
            jpegReader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                if (img != null) {
                    val buf = img.planes[0].buffer; val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    java.io.File("/sdcard/FieldSight/_probe/probe_still.jpg").writeBytes(bytes)
                    jpegDims = "${img.width}x${img.height} ${bytes.size}B"
                    img.close()
                }
                latch.countDown()
            }, handler)
            kotlinx.coroutines.delay(1000)
            val stillReq = cam.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegReader.surface)
            }.build()
            sess.capture(stillReq, null, handler)
            val gotStill = latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
            encThread.join(1000)
            return "streams=$streams 录像中拍照=${if (gotStill) "成功 $jpegDims" else "失败/超时"}"
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { codec?.stop(); codec?.release() }
            runCatching { jpegReader?.close() }
            runCatching { previewSurface?.release() }
            runCatching { previewTex?.release() }
            ht.quitSafely()
        }
    }

    private suspend fun configureSession(
        cam: android.hardware.camera2.CameraDevice,
        outputs: List<android.view.Surface>,
        handler: android.os.Handler,
    ): android.hardware.camera2.CameraCaptureSession =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            @Suppress("DEPRECATION")
            cam.createCaptureSession(outputs, object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) { cont.resume(s) {} }
                override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                    if (cont.isActive) cont.cancel(RuntimeException("session configure failed"))
                }
            }, handler)
        }
}
