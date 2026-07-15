package com.benzn.grandtime.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.benzn.grandtime.capture.camera2.Camera2Pipeline
import com.benzn.grandtime.capture.camera2.WatermarkRenderer
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.core.PhotoQuality
import com.benzn.grandtime.core.PhotoResolution
import com.benzn.grandtime.core.RecordingSettings
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.db.CaptureRecord
import com.benzn.grandtime.db.CaptureRecordDao
import com.benzn.grandtime.keymap.KeyAction
import com.benzn.grandtime.upload.UploadEnqueuer
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 命令执行器:CaptureCore 决策,这里做真事(录制/拍照/DB/震动/通知)。
 * 相机走 Camera2Pipeline(Camera2+MediaCodec+GL)。全部在 scope 串行。
 */
class CaptureManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val dao: CaptureRecordDao,
    private val notify: (String) -> Unit,
    private val probe: (String) -> Unit,
    private val uploadEnqueuer: UploadEnqueuer = object : UploadEnqueuer {
        override fun enqueue(recordId: String, initialDelaySeconds: Long) {}
    },
) {
    private val core = CaptureCore(clock = System::currentTimeMillis, newId = { UUID.randomUUID().toString() })
    private val pipeline = Camera2Pipeline(context, probe)
    private val audio = AudioRecorder(context)
    private val torch = TorchController(context, pipeline)
    private val volume = VolumeCycler(context)
    private val storage = MediaStorage({ MediaStorage.publicRoot(context) }, scopeProvider = { AppState.mediaScope.value })
    private val sounds = CaptureSounds()
    private val gps = GpsTracker(context)

    private var segmentTimer: Job? = null
    private var screenOffTimer: Job? = null
    private var watermarkTimer: Job? = null
    private var pendingRoll = false
    private var currentVideoRecordId: String? = null
    private var currentVideoFile: File? = null
    private var currentVideoStartedAt: Long = 0
    private var currentAudioRecordId: String? = null
    private var currentAudioFile: File? = null

    init {
        // 相机死亡(被抢占/热降频/HAL 错):录像态收尾落库+停计时器/音效,退出录像态。
        pipeline.onCameraLost = {
            scope.launch {
                if (core.state is CaptureState.RecordingVideo) {
                    finalizeVideoDbRow()?.let { uploadEnqueuer.enqueue(it) }
                    stopScreenOffTimer()
                    stopWatermarkTimer()
                    gps.stop()
                    sounds.stopRecording()
                    execute(core.onFailure("Camera lost — recording stopped"))
                }
            }
        }
        // 首启一次性迁移(不变)
        scope.launch(Dispatchers.IO) {
            val oldRoots = context.getExternalFilesDirs(null).filterNotNull()
            val newRoot = MediaStorage.publicRoot(context)
            for (oldRoot in oldRoots) {
                val migrator = MediaMigrator(oldRoot = oldRoot, newRoot = newRoot) { oldPath, newFile ->
                    scope.launch { dao.updatePath(oldPath, newFile.absolutePath) }
                }
                runCatching { migrator.migrate() }
            }
        }
        // 预览挂/摘:录像态且 UI 给了 surface 才挂;否则摘。setPreviewSurface 只切 GL 目标,
        // 不动相机会话(录像不中断)。distinctUntilChanged 防每次段滚动重复挂。
        scope.launch {
            combine(AppState.previewSurface, AppState.captureState) { sp, state ->
                sp.takeIf { state is CaptureState.RecordingVideo }
            }.distinctUntilChanged().collect { sp -> pipeline.setPreviewSurface(sp) }
        }
    }

    val handledActions: Set<KeyAction> = setOf(
        KeyAction.START_STOP_VIDEO, KeyAction.TAKE_PHOTO, KeyAction.START_STOP_AUDIO,
        KeyAction.TOGGLE_TORCH, KeyAction.ADJUST_VOLUME,
    )

    fun handle(action: KeyAction) {
        scope.launch {
            if (!preflight(action)) return@launch
            execute(core.onAction(action))
        }
    }

    fun shutdown() {
        segmentTimer?.cancel()
        screenOffTimer?.cancel()
        stopWatermarkTimer()
        gps.stop()
        if (pipeline.isRecording) pipeline.stopSegment()
        if (audio.isRecording) audio.stop()
        sounds.release()
        scope.launch { pipeline.release() }
        AppState.screenOffRequest.value = false
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun preflight(action: KeyAction): Boolean {
        val needsCamera = action == KeyAction.START_STOP_VIDEO || action == KeyAction.TAKE_PHOTO
        val needsMic = action == KeyAction.START_STOP_VIDEO || action == KeyAction.START_STOP_AUDIO
        if (needsCamera && !granted(Manifest.permission.CAMERA)) {
            notify("Camera permission required — open the app"); return false
        }
        if (needsMic && !granted(Manifest.permission.RECORD_AUDIO)) {
            notify("Microphone permission required — open the app"); return false
        }
        val startsCaptureAny = action == KeyAction.START_STOP_VIDEO ||
            action == KeyAction.START_STOP_AUDIO || action == KeyAction.TAKE_PHOTO
        if (startsCaptureAny && !Environment.isExternalStorageManager()) {
            notify("Storage access required — finish setup"); return false
        }
        val startsCapture = (action == KeyAction.START_STOP_VIDEO || action == KeyAction.START_STOP_AUDIO) &&
            core.state is CaptureState.Idle || action == KeyAction.TAKE_PHOTO
        if (startsCapture && !storage.hasFreeSpace()) {
            notify("Storage full"); vibrate(2); return false
        }
        return true
    }

    private suspend fun execute(commands: List<CaptureCommand>) {
        for (cmd in commands) {
            val ok = when (cmd) {
                is CaptureCommand.StartVideoSegment -> startVideoSegment(cmd)
                is CaptureCommand.StopVideo -> {
                    segmentTimer?.cancel()
                    pendingRoll = cmd.rollToNext
                    pipeline.stopSegment()
                    true
                }
                is CaptureCommand.TakePhoto -> { takePhoto(cmd); true }
                is CaptureCommand.StartAudio -> startAudio(cmd)
                CaptureCommand.StopAudio -> { stopAudio(); true }
                CaptureCommand.ToggleTorch -> {
                    torch.toggle(); probe("torch ${if (torch.torchOn) "on" else "off"}"); true
                }
                CaptureCommand.CycleVolume -> { probe("volume ${volume.cycle()}%"); true }
                is CaptureCommand.Vibrate -> { vibrate(cmd.times); true }
                is CaptureCommand.Notify -> { notify(cmd.text); true }
            }
            if (!ok) break
        }
        AppState.captureState.value = core.state
    }

    private suspend fun startVideoSegment(cmd: CaptureCommand.StartVideoSegment): Boolean {
        val settings = settingsStore.settings.first()
        val file = storage.newFile(MediaStorage.Kind.VIDEO)
        val startedAt = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        // 段 1:按权限起 GPS(未授权 GpsTracker.start() 自己 no-op),并按设置起水印刷新定时器。
        if (cmd.segmentIndex == 1) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) gps.start()
            startWatermarkTimer(settings)
        }
        val result = pipeline.startSegment(
            file = file,
            aspect = settings.aspectRatio,
            quality = settings.videoQuality,
            hevcPreferred = true,
            location = gps.freshFix()?.let { it.first.toFloat() to it.second.toFloat() },
        ) { error, message ->
            scope.launch {
                val finalizedId = finalizeVideoDbRow()
                if (error) {
                    stopScreenOffTimer()
                    stopWatermarkTimer()
                    gps.stop()
                    execute(core.onFailure(message ?: "Video error"))
                    pipeline.release()
                } else {
                    if (finalizedId != null) uploadEnqueuer.enqueue(finalizedId)
                    val roll = pendingRoll
                    pendingRoll = false
                    execute(core.onVideoFinalized(roll))
                    if (core.state is CaptureState.Idle) {
                        sounds.stopRecording()
                        stopScreenOffTimer()
                        stopWatermarkTimer()
                        gps.stop()
                        pipeline.release()
                    }
                }
            }
        }
        if (result == null) {
            stopScreenOffTimer()
            // 段起动失败也是终态(core.onFailure 一律回 Idle)——不管是不是段 1,都要收尾计时器/GPS。
            stopWatermarkTimer()
            gps.stop()
            sounds.stopRecording()
            execute(core.onFailure("Camera unavailable"))
            pipeline.release()
            return false
        }
        currentVideoRecordId = recordId
        currentVideoFile = file
        currentVideoStartedAt = startedAt
        dao.insert(
            CaptureRecord(
                id = recordId, kind = "video", filePath = file.absolutePath, fileName = file.name,
                startedAt = startedAt, codec = result.codec, resolution = result.resolution,
                segmentIndex = cmd.segmentIndex, sessionId = cmd.sessionId, createdAt = startedAt,
                siteId = AppState.selectedSite.value?.id,
            )
        )
        startSegmentTimer(settings.segmentMinutes)
        if (cmd.segmentIndex == 1) {
            sounds.startRecording()
            startScreenOffTimer(settings.screenOffMinutes)
        }
        probe("video segment ${cmd.segmentIndex} started: ${file.name}")
        return true
    }

    /**
     * ~1Hz 刷水印:段 1 起,按当前设置读一次决定开关。关闭时直接清空叠加位图并不起定时器
     * (行为与 P2 完全一致——不叠加任何东西)。开启则每秒重建内容(用户名/时间/定位)→渲染→下发。
     */
    private fun startWatermarkTimer(settings: RecordingSettings) {
        watermarkTimer?.cancel()
        if (!settings.watermarkEnabled) {
            pipeline.setWatermarkBitmap(null)
            return
        }
        watermarkTimer = scope.launch {
            while (true) {
                val bmp = WatermarkRenderer.render(currentWatermarkContent(), widthPx = 640)
                pipeline.setWatermarkBitmap(bmp)
                delay(1000)
            }
        }
    }

    /**
     * 当前水印内容:用户名(有才加)+ 时间戳 + gps.freshFix()(超 30s 未刷新视为丢定位,降级占位——
     * 隧道/遮挡场景不拿冻结坐标配当下时间冒充实时位置;无定位区分"未授权"/"定位中…")。
     * epochMillis 默认戳章此刻(录像 1Hz 刷新用);单拍水印(stampPhotoWatermark)传快门时刻,
     * 保证证据时间戳=按下快门那刻而非之后解码/叠加处理的耗时之后。
     */
    private fun currentWatermarkContent(epochMillis: Long = System.currentTimeMillis()): WatermarkContent {
        val name = (AppState.loginState.value as? LoginState.LoggedIn)?.displayName
        val fix = gps.freshFix()
        val noFixText = if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) "Locating…" else "No location permission"
        return Watermark.build(
            userName = name,
            epochMillis = epochMillis,
            lat = fix?.first,
            lon = fix?.second,
            address = null, // P3:地址栏预留,不填
            zone = ZoneId.systemDefault(),
            noFixText = noFixText,
        )
    }

    /** 停水印刷新定时器 + 清空叠加位图(pipeline 相机丢失→重连会粘住 pendingWatermark,不清会重新冒出来)。 */
    private fun stopWatermarkTimer() {
        watermarkTimer?.cancel()
        watermarkTimer = null
        pipeline.setWatermarkBitmap(null)
    }

    private fun startScreenOffTimer(minutes: Int) {
        screenOffTimer?.cancel()
        if (minutes <= 0) return
        screenOffTimer = scope.launch {
            delay(minutes * 60_000L)
            AppState.screenOffRequest.value = true
        }
    }

    private fun stopScreenOffTimer() {
        screenOffTimer?.cancel()
        screenOffTimer = null
        AppState.screenOffRequest.value = false
    }

    private fun startSegmentTimer(minutes: Int) {
        segmentTimer?.cancel()
        segmentTimer = scope.launch {
            delay(minutes * 60_000L)
            execute(core.onSegmentTimerFired())
        }
    }

    private suspend fun finalizeVideoDbRow(): String? {
        val id = currentVideoRecordId ?: return null
        val file = currentVideoFile ?: return null
        val ended = System.currentTimeMillis()
        dao.finalize(id, ended, ended - currentVideoStartedAt, file.length())
        // GPS 轨迹落到这一段视频行;stop() 不清 track,即便本方法在 gps.stop() 之后跑也拿得到。
        gps.snapshotTrackJson()?.let { dao.updateGpsTrack(id, it) }
        scan(file.absolutePath)
        probe("video segment saved: ${file.name} (${file.length()} bytes)")
        currentVideoRecordId = null
        currentVideoFile = null
        return id
    }

    private suspend fun takePhoto(cmd: CaptureCommand.TakePhoto) {
        val settings = settingsStore.settings.first()
        val recordingVideo = core.state is CaptureState.RecordingVideo
        // 非录像态(Idle/录音)先确保会话 + 3A 收敛;录像态直接用现有会话。
        if (!recordingVideo) {
            runCatching { pipeline.prepareForPhoto(settings.aspectRatio, settings.videoQuality) }
                .onFailure { execute(core.onFailure("Camera unavailable")); return }
        }
        val file = storage.newFile(MediaStorage.Kind.PHOTO)
        // startedAt 紧邻 pipeline.takePhoto 前取——即快门时刻;水印证据时间戳用它而非之后戳章处理的耗时点。
        val startedAt = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        val ok = pipeline.takePhoto(file, jpegQuality(settings.photoQuality))
        if (ok) {
            // 照片精度设置:满 4:3 JPEG 落盘后按目标像素降采样(MAX=不降)。
            settings.photoResolution.targetPixels()?.let { downscaleJpeg(file, it, jpegQuality(settings.photoQuality)) }
            // 水印开关:降采样之后叠加(带宽按最终落盘图片宽走),赶在 DB 插入(记录最终 sizeBytes)和入队上传之前完成。
            if (settings.watermarkEnabled) stampPhotoWatermark(file, jpegQuality(settings.photoQuality), startedAt)
            dao.insert(
                CaptureRecord(
                    id = recordId, kind = "photo", filePath = file.absolutePath, fileName = file.name,
                    startedAt = startedAt, endedAt = startedAt, sizeBytes = file.length(),
                    codec = "jpeg", sessionId = cmd.sessionId, createdAt = startedAt,
                    siteId = AppState.selectedSite.value?.id,
                )
            )
            uploadEnqueuer.enqueue(recordId)
            scan(file.absolutePath)
            AppState.lastPhotoFlash.value = file.absolutePath
            sounds.shutter()
            notify(if (core.state is CaptureState.Idle) "Photo saved" else "Photo saved (recording continues)")
            probe("photo saved: ${file.name}")
        } else {
            notify("Photo failed"); vibrate(2)
        }
        // 录像中保留会话;否则(Idle/录音)拍完释放相机。
        if (core.state !is CaptureState.RecordingVideo && !pipeline.isRecording) pipeline.release()
    }

    /** 照片精度降采样(spec §2.7):JPEG 超目标像素则解码→按比例缩小→重压。IO 线程。 */
    private suspend fun downscaleJpeg(file: File, targetPixels: Long, quality: Int) = withContext(Dispatchers.IO) {
        runCatching {
            val orientation = runCatching { ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_ORIENTATION) }.getOrNull()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val srcPixels = bounds.outWidth.toLong() * bounds.outHeight
            if (srcPixels <= targetPixels || bounds.outWidth <= 0) return@runCatching
            val scale = kotlin.math.sqrt(targetPixels.toDouble() / srcPixels)
            val dstW = (bounds.outWidth * scale).toInt().coerceAtLeast(1)
            val dstH = (bounds.outHeight * scale).toInt().coerceAtLeast(1)
            val src = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching
            val scaled = Bitmap.createScaledBitmap(src, dstW, dstH, true)
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (scaled != src) scaled.recycle()
            src.recycle()
            // BitmapFactory 解码+重压会丢 EXIF(含相机写入的旋转 tag)——降采样后写回,避免照片显示歪。
            if (orientation != null) runCatching {
                ExifInterface(file.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
                    saveAttributes()
                }
            }
        }
    }

    /**
     * 照片水印:解码 → 若 EXIF orientation 隐含旋转(本机 takePhoto 写 JPEG_ORIENTATION=sensorOrientation,
     * 像素不转、tag 转,查看器里才正立)则先转正(Matrix.postRotate,90/180/270;镜像变体按其旋转分量处理,
     * 本机不镜像)→ Canvas 底部叠 WatermarkRenderer.render 出的水印带(按图片宽,此时"底部"=转正后真底部)
     * → 重压 JPEG(质量与拍照/降采样同一档)。转正过的图 tag 写回 NORMAL(1,像素已正);未转正维持原
     * 行为(原样回写 tag,absent 则不写)。IO 线程。失败整体吞掉(runCatching):没水印的照片也比丢照片强。
     */
    private suspend fun stampPhotoWatermark(file: File, quality: Int, epochMillis: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val orientationStr = runCatching { ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_ORIENTATION) }.getOrNull()
            val rotationDeg = exifRotationDeg(orientationStr?.toIntOrNull() ?: ExifInterface.ORIENTATION_NORMAL)
            val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching
            val src = if (rotationDeg != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rotationDeg.toFloat()) }
                val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                decoded.recycle() // 转正即产生第二张全尺寸位图——转正前那张立即回收
                upright
            } else decoded
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val wm = WatermarkRenderer.render(currentWatermarkContent(epochMillis), out.width)
            Canvas(out).drawBitmap(wm, 0f, (out.height - wm.height).toFloat(), null)
            FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (out != src) src.recycle()
            out.recycle()
            wm.recycle()
            val writeBack = if (rotationDeg != 0) "1" else orientationStr
            if (writeBack != null) runCatching {
                ExifInterface(file.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, writeBack)
                    saveAttributes()
                }
            }
        }.onFailure { probe("photo watermark failed: ${it.message}") }
    }

    /**
     * EXIF orientation tag → 需顺时针转正的角度(0/90/180/270)。TRANSPOSE/TRANSVERSE(镜像+旋转)
     * 按其旋转分量近似处理;纯镜像(FLIP_HORIZONTAL/FLIP_VERTICAL,无旋转分量)不属于本修复范围,
     * 落 else 走原行为——本机 takePhoto 只产生 ROTATE_90/180/270,不产生任何镜像 tag。
     */
    private fun exifRotationDeg(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    private suspend fun startAudio(cmd: CaptureCommand.StartAudio): Boolean {
        val file = storage.newFile(MediaStorage.Kind.AUDIO)
        val startedAt = System.currentTimeMillis()
        if (!audio.start(file)) {
            execute(core.onFailure("Audio recorder unavailable")); return false
        }
        val recordId = UUID.randomUUID().toString()
        currentAudioRecordId = recordId
        currentAudioFile = file
        dao.insert(
            CaptureRecord(
                id = recordId, kind = "audio", filePath = file.absolutePath, fileName = file.name,
                startedAt = startedAt, codec = "aac", sessionId = cmd.sessionId, createdAt = startedAt,
                siteId = AppState.selectedSite.value?.id,
            )
        )
        sounds.startRecording()
        probe("audio started: ${file.name}")
        return true
    }

    private suspend fun stopAudio() {
        val startedAt = (core.state as? CaptureState.RecordingAudio)?.startedAtMillis
        val stoppedCleanly = audio.stop()
        val id = currentAudioRecordId
        val file = currentAudioFile
        if (id != null && file != null) {
            val ended = System.currentTimeMillis()
            dao.finalize(id, ended, if (startedAt != null) ended - startedAt else 0L, file.length())
            if (stoppedCleanly) uploadEnqueuer.enqueue(id)
            scan(file.absolutePath)
            probe("audio saved: ${file.name}")
        }
        currentAudioRecordId = null
        currentAudioFile = null
        if (!stoppedCleanly) probe("audio stop reported error")
        // 录音期间若拍过照,相机会话可能残留——收尾释放;录像中不会走到这。
        if (!pipeline.isRecording) pipeline.release()
        sounds.stopRecording()
        execute(core.onAudioFinalized())
    }

    private fun vibrate(times: Int) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        val pattern = if (times == 1) longArrayOf(0, 80) else longArrayOf(0, 60, 80, 60)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun scan(path: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
}

private fun jpegQuality(quality: PhotoQuality): Int = if (quality == PhotoQuality.HIGH) 95 else 80

/** 照片精度 → 目标像素数;MAX=null(满 4:3,不降)。 */
private fun PhotoResolution.targetPixels(): Long? = when (this) {
    PhotoResolution.MAX -> null
    PhotoResolution.HIGH -> 3_000_000L
    PhotoResolution.STD -> 1_000_000L
}
