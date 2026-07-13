package com.benzn.grandtime.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.PhotoQuality
import com.benzn.grandtime.core.PhotoResolution
import com.benzn.grandtime.core.RecordingSettings
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.VideoQuality
import com.benzn.grandtime.db.CaptureRecord
import com.benzn.grandtime.db.CaptureRecordDao
import com.benzn.grandtime.keymap.KeyAction
import com.benzn.grandtime.upload.UploadEnqueuer
import java.io.File
import java.io.FileOutputStream
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
 * 命令执行器:CaptureCore 决策,这里做真事(绑相机/录制/DB/震动/通知)。
 * 全部在 scope(Service 主线程 lifecycleScope)串行执行。
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
    private val lifecycleOwner = ServiceLifecycleOwner()
    private val session = CameraSession(context, lifecycleOwner)
    private val video = VideoRecorder(context)
    private val photo = PhotoTaker(context)
    private val audio = AudioRecorder(context)
    private val torch = TorchController(context, session)
    private val volume = VolumeCycler(context)
    private val storage = MediaStorage({ MediaStorage.publicRoot(context) }, scopeProvider = { AppState.mediaScope.value })
    private val sounds = CaptureSounds()

    private var segmentTimer: Job? = null
    private var screenOffTimer: Job? = null
    private var pendingRoll = false
    private var currentVideoRecordId: String? = null
    private var currentVideoFile: File? = null
    private var currentVideoStartedAt: Long = 0
    private var currentAudioRecordId: String? = null
    private var currentAudioFile: File? = null

    init {
        // 首启一次性迁移:旧的 app 私有目录媒体 → 公共存储(spec §3),迁移成功的行更新 DB 路径。
        // 遍历全部外置卷(非仅默认卷 0)——SP3a 曾优先写 SD(volumes[1]),旧私有文件可能
        // 落在任意一个卷下,只迁默认卷会让 SD 上的旧文件在公共存储里"隐形"(数据可见性缺口)。
        scope.launch(Dispatchers.IO) {
            val oldRoots = context.getExternalFilesDirs(null).filterNotNull()
            val newRoot = MediaStorage.publicRoot(context)
            for (oldRoot in oldRoots) {
                val migrator = MediaMigrator(
                    oldRoot = oldRoot,
                    newRoot = newRoot,
                ) { oldPath, newFile -> scope.launch { dao.updatePath(oldPath, newFile.absolutePath) } }
                runCatching { migrator.migrate() }
            }
        }
        // 预览 surface 收发:录像态且 UI 提供了 surface 才 attach;否则(切后台/回 Idle)detach。
        // distinctUntilChanged 防抖:每次段滚动 captureState 都会重新 emit 一个新的
        // RecordingVideo(segmentIndex+1) 实例,若不去重,同一 surface 会被反复 attachPreview,
        // 在 CameraSession 里越叠越多 Preview 用例。这里只关心"是否处于录像态"这一维度的变化。
        scope.launch {
            combine(
                AppState.previewSurface,
                AppState.captureState,
            ) { sp, state -> sp.takeIf { state is CaptureState.RecordingVideo } }
                .distinctUntilChanged()
                .collect { sp ->
                    if (sp != null) {
                        runCatching { session.attachPreview(sp) }
                    } else {
                        session.detachPreview()
                    }
                }
        }
    }

    /** 720P/1080P 录像中拍照的抓帧请求(spec §2.5)。单挂起槽:非 null 时新请求一律防抖拒绝。 */
    private data class PendingFrameGrab(
        val photoId: String,
        val sessionId: String,
        val keypressMillis: Long,
        val segFile: File,
        val segStart: Long,
        val jpegQuality: Int,
        val resolution: String,
    )
    private var pendingFrameGrab: PendingFrameGrab? = null

    val handledActions: Set<KeyAction> = setOf(
        KeyAction.START_STOP_VIDEO,
        KeyAction.TAKE_PHOTO,
        KeyAction.START_STOP_AUDIO,
        KeyAction.TOGGLE_TORCH,
        KeyAction.ADJUST_VOLUME,
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
        if (video.isRecording) video.stop()
        if (audio.isRecording) audio.stop()
        pendingFrameGrab = null
        sounds.release()
        lifecycleOwner.destroy()
        // 收尾兜底:防止 screenOffRequest 停留在 true,下次进入 RecordingScreen 时
        // LaunchedEffect(screenOff) 读到陈旧的 true 而误判"该熄屏"。
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
        // 命令批次中若 Start* 内部走到 core.onFailure(嵌套 execute 已把状态收回 Idle),
        // 必须中止本批剩余命令——否则紧随其后的 Vibrate(1)/Notify("Recording ...")
        // 会覆盖失败提示,留下"正在录制"通知却其实并未开始录制。
        for (cmd in commands) {
            val ok = when (cmd) {
                is CaptureCommand.StartVideoSegment -> startVideoSegment(cmd)
                is CaptureCommand.StopVideo -> {
                    segmentTimer?.cancel()
                    pendingRoll = cmd.rollToNext
                    video.stop()
                    true
                }
                is CaptureCommand.TakePhoto -> { takePhoto(cmd); true }
                is CaptureCommand.StartAudio -> startAudio(cmd)
                CaptureCommand.StopAudio -> { stopAudio(); true }
                CaptureCommand.ToggleTorch -> {
                    torch.toggle()
                    probe("torch ${if (torch.torchOn) "on" else "off"}")
                    true
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
        val videoCapture = session.videoCapture
            ?: try {
                session.bindForVideo(settings.videoQuality, jpegQuality(settings.photoQuality))
            } catch (e: Exception) {
                execute(core.onFailure("Camera unavailable")); return false
            }
        val file = storage.newFile(MediaStorage.Kind.VIDEO)
        val startedAt = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        currentVideoRecordId = recordId
        currentVideoFile = file
        currentVideoStartedAt = startedAt
        dao.insert(
            CaptureRecord(
                id = recordId, kind = "video", filePath = file.absolutePath, fileName = file.name,
                startedAt = startedAt, codec = "h264", resolution = settings.videoQuality.resolutionString(),
                segmentIndex = cmd.segmentIndex, sessionId = cmd.sessionId, createdAt = startedAt,
                siteId = AppState.selectedSite.value?.id,
            )
        )
        video.startSegment(videoCapture, file) { error, message ->
            scope.launch {
                val finalizedId = finalizeVideoDbRow()
                // 抓帧请求登记的段与本次 finalize 的段匹配才消费——防止跨段串号。
                val grab = pendingFrameGrab
                if (grab != null && grab.segFile == file) {
                    try {
                        performFrameGrab(grab)
                    } finally {
                        pendingFrameGrab = null
                    }
                }
                if (error) {
                    pendingFrameGrab = null // 会话失败,后续不再有段 finalize——不留孤儿挂起槽
                    stopScreenOffTimer()
                    execute(core.onFailure(message ?: "Video error"))
                    session.unbind()
                } else {
                    if (finalizedId != null) uploadEnqueuer.enqueue(finalizedId)
                    val roll = pendingRoll
                    pendingRoll = false
                    execute(core.onVideoFinalized(roll))
                    if (core.state is CaptureState.Idle) {
                        sounds.stopRecording()
                        stopScreenOffTimer()
                        session.unbind()
                    }
                }
            }
        }
        startSegmentTimer(settings.segmentMinutes)
        if (cmd.segmentIndex == 1) {
            sounds.startRecording()
            startScreenOffTimer(settings.screenOffMinutes)
        }
        probe("video segment ${cmd.segmentIndex} started: ${file.name}")
        return true
    }

    /** 录像段 1 起计时,满 N 分钟置 AppState.screenOffRequest=true(spec §2.7);0=Never 不计。 */
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

    /** Finalizes the DB row for the just-completed segment; returns its id for upload enqueue (null if none was pending). */
    private suspend fun finalizeVideoDbRow(): String? {
        val id = currentVideoRecordId ?: return null
        val file = currentVideoFile ?: return null
        val ended = System.currentTimeMillis()
        dao.finalize(id, ended, ended - currentVideoStartedAt, file.length())
        scan(file.absolutePath)
        probe("video segment saved: ${file.name} (${file.length()} bytes)")
        currentVideoRecordId = null
        currentVideoFile = null
        return id
    }

    private suspend fun takePhoto(cmd: CaptureCommand.TakePhoto) {
        val settings = settingsStore.settings.first()
        val recordingVideo = core.state is CaptureState.RecordingVideo
        val imageCapture = when {
            // 720P/1080P 录像中(session.imageCapture == null,双绑不可用)= 抓帧路径(spec §2.5)。
            // 480P 双绑(imageCapture != null)与 Idle/录音中拍照维持原传感器 JPEG 路径,不受影响。
            recordingVideo -> session.imageCapture ?: run {
                initiateFrameGrab(cmd, settings)
                return
            }
            session.imageCapture != null -> session.imageCapture!!
            else -> try {
                session.bindForPhoto(jpegQuality(settings.photoQuality), settings.photoResolution.targetPixels())
            } catch (e: Exception) {
                execute(core.onFailure("Camera unavailable")); return
            }
        }
        val file = storage.newFile(MediaStorage.Kind.PHOTO)
        val startedAt = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        photo.take(imageCapture, file) { success ->
            scope.launch {
                if (success) {
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
                    notify("Photo failed")
                    vibrate(2)
                }
                // Idle 或 RecordingAudio 后都应释放相机(录音不占相机);
                // 只有仍在录像(RecordingVideo,双用例共用绑定)才保留。
                if (core.state !is CaptureState.RecordingVideo && !video.isRecording) session.unbind()
            }
        }
    }

    /**
     * 720P/1080P 录像中拍照 = 抓帧(spec §2.5)。记录按键时刻与当前段,强制滚段
     * (等价 onSegmentTimerFired 的 StopVideo(rollToNext=true));段 finalize 回调里
     * (startVideoSegment)真正抽帧,录像无感续录。
     */
    private fun initiateFrameGrab(cmd: CaptureCommand.TakePhoto, settings: RecordingSettings) {
        if (pendingFrameGrab != null) {
            vibrate(2)
            notify("Capturing, please wait")
            probe("frame-grab rejected: previous grab still pending")
            return
        }
        val segFile = currentVideoFile
        if (segFile == null) {
            // 防御性兜底:理论上 RecordingVideo 状态下段文件必存在;真出现即视为不支持。
            notify("Photo during video not supported on this device")
            vibrate(2)
            return
        }
        pendingFrameGrab = PendingFrameGrab(
            photoId = UUID.randomUUID().toString(),
            sessionId = cmd.sessionId,
            keypressMillis = System.currentTimeMillis(),
            segFile = segFile,
            segStart = currentVideoStartedAt,
            jpegQuality = jpegQuality(settings.photoQuality),
            resolution = settings.videoQuality.resolutionString(),
        )
        probe("frame-grab requested: rolling segment early")
        segmentTimer?.cancel()
        pendingRoll = true
        video.stop()
    }

    /** IO 线程抽帧压 JPEG 落盘 + 写 DB 行;调用方已把匹配的 pendingFrameGrab 置 null。 */
    private suspend fun performFrameGrab(grab: PendingFrameGrab) {
        val out = storage.newFile(MediaStorage.Kind.PHOTO, grab.keypressMillis)
        val ok = withContext(Dispatchers.IO) {
            try {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(grab.segFile.absolutePath)
                    val offsetUs = frameOffsetMicros(grab.keypressMillis, grab.segStart)
                    val frame = retriever.getFrameAtTime(offsetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: return@withContext false
                    FileOutputStream(out).use { stream ->
                        frame.compress(Bitmap.CompressFormat.JPEG, grab.jpegQuality, stream)
                    }
                    frame.recycle()
                }
                true
            } catch (e: Exception) {
                false
            }
        }
        if (ok) {
            dao.insert(
                CaptureRecord(
                    id = grab.photoId, kind = "photo", filePath = out.absolutePath, fileName = out.name,
                    startedAt = grab.keypressMillis, endedAt = grab.keypressMillis, sizeBytes = out.length(),
                    codec = "frame-grab", resolution = grab.resolution, sessionId = grab.sessionId,
                    createdAt = grab.keypressMillis,
                    siteId = AppState.selectedSite.value?.id,
                )
            )
            uploadEnqueuer.enqueue(grab.photoId)
            scan(out.absolutePath)
            AppState.lastPhotoFlash.value = out.absolutePath
            sounds.shutter()
            notify("Photo saved (recording continues)")
            probe("frame-grab saved: ${out.name}")
        } else {
            notify("Photo capture failed")
            vibrate(2)
            probe("frame-grab failed")
        }
    }

    private suspend fun startAudio(cmd: CaptureCommand.StartAudio): Boolean {
        val file = storage.newFile(MediaStorage.Kind.AUDIO)
        val startedAt = System.currentTimeMillis()
        if (!audio.start(file)) {
            execute(core.onFailure("Audio recorder unavailable"))
            return false
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
            // #5: only enqueue for upload on a clean stop — an unclean MediaRecorder.stop()
            // (stoppedCleanly == false) can leave a truncated/corrupt file; the DB row is still
            // finalized (so it shows up locally) but we don't ship a broken file to the backend.
            if (stoppedCleanly) uploadEnqueuer.enqueue(id)
            scan(file.absolutePath)
            probe("audio saved: ${file.name}")
        }
        currentAudioRecordId = null
        currentAudioFile = null
        if (!stoppedCleanly) probe("audio stop reported error")
        // 录音专属绑定不存在(MediaRecorder 不占 CameraX),但录音期间若拍过照,
        // 相机绑定会残留到这里——收尾时兜底释放;video 仍在录时保持绑定,no-op 安全。
        if (!video.isRecording) session.unbind()
        sounds.stopRecording()
        execute(core.onAudioFinalized())
    }

    private fun vibrate(times: Int) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        val pattern = if (times == 1) longArrayOf(0, 80) else longArrayOf(0, 60, 80, 60)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /** 落盘文件纳入系统媒体库(spec §3),各 finalize/insert 点后调用。 */
    private fun scan(path: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
}

private fun VideoQuality.resolutionString(): String = when (this) {
    VideoQuality.P1080 -> "1920x1080"
    VideoQuality.P720 -> "1280x720"
    VideoQuality.P480 -> "854x480"
}

private fun jpegQuality(quality: PhotoQuality): Int = if (quality == PhotoQuality.HIGH) 95 else 80

/** 照片精度(spec §2.7)→ CameraSession.bindForPhoto 的目标像素数;MAX=null(不限,满传感器)。 */
private fun PhotoResolution.targetPixels(): Long? = when (this) {
    PhotoResolution.MAX -> null
    PhotoResolution.HIGH -> 3_000_000L
    PhotoResolution.STD -> 1_000_000L
}

/**
 * 抓帧偏移(微秒)= (按键时刻 − 段起始) 钳制非负,换算 MediaMetadataRetriever 的微秒单位
 * (spec §2.5)。段起点之前按下(时钟偏差)归零,不取负偏移。纯函数,JVM 可测。
 */
internal fun frameOffsetMicros(keypressMillis: Long, segStart: Long): Long =
    (keypressMillis - segStart).coerceAtLeast(0) * 1000
