package com.benzn.grandtime.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.PhotoQuality
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.VideoQuality
import com.benzn.grandtime.db.CaptureRecord
import com.benzn.grandtime.db.CaptureRecordDao
import com.benzn.grandtime.keymap.KeyAction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
) {
    private val core = CaptureCore(clock = System::currentTimeMillis, newId = { UUID.randomUUID().toString() })
    private val lifecycleOwner = ServiceLifecycleOwner()
    private val session = CameraSession(context, lifecycleOwner)
    private val video = VideoRecorder(context)
    private val photo = PhotoTaker(context)
    private val audio = AudioRecorder(context)
    private val torch = TorchController(context, session)
    private val volume = VolumeCycler(context)
    private val storage = MediaStorage({ context.getExternalFilesDirs(null).toList() })

    private var segmentTimer: Job? = null
    private var pendingRoll = false
    private var currentVideoRecordId: String? = null
    private var currentVideoFile: File? = null
    private var currentVideoStartedAt: Long = 0
    private var currentAudioRecordId: String? = null
    private var currentAudioFile: File? = null

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
        if (video.isRecording) video.stop()
        if (audio.isRecording) audio.stop()
        lifecycleOwner.destroy()
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
            )
        )
        video.startSegment(videoCapture, file) { error, message ->
            scope.launch {
                finalizeVideoDbRow()
                if (error) {
                    execute(core.onFailure(message ?: "Video error"))
                    session.unbind()
                } else {
                    val roll = pendingRoll
                    pendingRoll = false
                    execute(core.onVideoFinalized(roll))
                    if (core.state is CaptureState.Idle) session.unbind()
                }
            }
        }
        startSegmentTimer(settings.segmentMinutes)
        probe("video segment ${cmd.segmentIndex} started: ${file.name}")
        return true
    }

    private fun startSegmentTimer(minutes: Int) {
        segmentTimer?.cancel()
        segmentTimer = scope.launch {
            delay(minutes * 60_000L)
            execute(core.onSegmentTimerFired())
        }
    }

    private suspend fun finalizeVideoDbRow() {
        val id = currentVideoRecordId ?: return
        val file = currentVideoFile ?: return
        val ended = System.currentTimeMillis()
        dao.finalize(id, ended, ended - currentVideoStartedAt, file.length())
        probe("video segment saved: ${file.name} (${file.length()} bytes)")
        currentVideoRecordId = null
        currentVideoFile = null
    }

    private suspend fun takePhoto(cmd: CaptureCommand.TakePhoto) {
        val settings = settingsStore.settings.first()
        val recordingVideo = core.state is CaptureState.RecordingVideo
        val imageCapture = when {
            recordingVideo -> session.imageCapture ?: run {
                notify("Photo during video not supported on this device")
                vibrate(2)
                return
            }
            session.imageCapture != null -> session.imageCapture!!
            else -> try {
                session.bindForPhoto(jpegQuality(settings.photoQuality))
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
                        )
                    )
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
            )
        )
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
            probe("audio saved: ${file.name}")
        }
        currentAudioRecordId = null
        currentAudioFile = null
        if (!stoppedCleanly) probe("audio stop reported error")
        // 录音专属绑定不存在(MediaRecorder 不占 CameraX),但录音期间若拍过照,
        // 相机绑定会残留到这里——收尾时兜底释放;video 仍在录时保持绑定,no-op 安全。
        if (!video.isRecording) session.unbind()
        execute(core.onAudioFinalized())
    }

    private fun vibrate(times: Int) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        val pattern = if (times == 1) longArrayOf(0, 80) else longArrayOf(0, 60, 80, 60)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}

private fun VideoQuality.resolutionString(): String = when (this) {
    VideoQuality.P1080 -> "1920x1080"
    VideoQuality.P720 -> "1280x720"
    VideoQuality.P480 -> "854x480"
}

private fun jpegQuality(quality: PhotoQuality): Int = if (quality == PhotoQuality.HIGH) 95 else 80
