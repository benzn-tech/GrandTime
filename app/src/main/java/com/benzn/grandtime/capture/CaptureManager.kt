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
import com.benzn.grandtime.sitevoice.MicHandover
import com.benzn.grandtime.upload.UploadEnqueuer
import com.benzn.grandtime.upload.uploadRequiresUnmetered
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
        override fun enqueue(
            recordId: String,
            initialDelaySeconds: Long,
            requireUnmetered: Boolean,
            replace: Boolean,
        ) {}
    },
    // Defaults to a no-op for the same reason uploadEnqueuer does: the JVM unit
    // tests construct a CaptureManager without WorkManager, and a real enqueuer
    // there would need an Android context.
    private val sessionCloseEnqueuer: com.benzn.grandtime.upload.SessionCloseEnqueuer =
        object : com.benzn.grandtime.upload.SessionCloseEnqueuer {
            override fun enqueue(sessionId: String, endedAtMillis: Long) {}
        },
) : MicHandover {
    private val core = CaptureCore(clock = System::currentTimeMillis, newId = { ChunkNaming.sessionId(UUID.randomUUID().toString()) })
    private val pipeline = Camera2Pipeline(context, probe)
    private val audio = AudioRecorder(context)
    private val torch = TorchController(context, pipeline)
    private val volume = VolumeCycler(context)
    private val storage = MediaStorage({ MediaStorage.publicRoot(context) }, scopeProvider = { AppState.mediaScope.value })
    private val sounds = CaptureSounds(context)
    private val gps = GpsTracker(context)
    private val sessionsApi = com.benzn.grandtime.net.SessionsApiClient(com.benzn.grandtime.BuildConfig.ORG_API_BASE_URL)

    private var segmentTimer: Job? = null
    private var watermarkTimer: Job? = null
    private var pendingStopReason: StopReason = StopReason.ROLLOVER
    /** True while Site-voice is borrowing the mic. Read by startVideoSegment so a segment rollover
     *  during a handover starts the next segment with its audio paused (silent) until end(). */
    @Volatile private var handoverActive = false
    private var currentVideoRecordId: String? = null
    private var currentVideoFile: File? = null
    private var currentVideoStartedAt: Long = 0
    /** Next audio segment index to use on resume — set by [pauseAudio] from [AudioRecorder.lastSegmentIndex]
     *  right after [AudioRecorder.stop] finalizes the last segment, so a resumed session's chunk keys
     *  never collide with the ones already uploaded before the pause. */
    private var audioResumeIndex: Int = 1

    init {
        // 相机死亡(被抢占/热降频/HAL 错):录像态收尾落库+停计时器/音效,退出录像态。
        pipeline.onCameraLost = {
            scope.launch {
                if (core.state is CaptureState.RecordingVideo) {
                    val wifiOnly = settingsStore.settings.first().videoUploadWifiOnly
                    finalizeVideoDbRow()?.let {
                        uploadEnqueuer.enqueue(it, requireUnmetered = uploadRequiresUnmetered("video", wifiOnly))
                    }
                    stopWatermarkTimer()
                    gps.stop()
                    sounds.stopRecording()
                    val endingSessionId = (core.state as? CaptureState.RecordingVideo)?.sessionId
                    execute(core.onFailure("Camera lost — recording stopped"))
                    if (endingSessionId != null) fireSessionClose(endingSessionId, System.currentTimeMillis())
                } else if (core.state is CaptureState.PausedVideo) {
                    // Camera lost in the narrow race before pause-cleanup released it (#3 releases the
                    // camera on pause): no live segment to finalize — clean up + close the session (idle).
                    val endingSessionId = (core.state as? CaptureState.PausedVideo)?.sessionId
                    stopWatermarkTimer()
                    gps.stop()
                    sounds.stopRecording()
                    execute(core.onFailure("Camera lost — recording stopped"))
                    if (endingSessionId != null) fireSessionClose(endingSessionId, System.currentTimeMillis())
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
        // Someone else ended the meeting. Stop here too, then ask about resuming.
        scope.launch {
            AppState.meetingEndedElsewhere.collect { onMeetingEndedElsewhere() }
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
        KeyAction.START_STOP_VIDEO, KeyAction.END_VIDEO, KeyAction.TAKE_PHOTO, KeyAction.START_STOP_AUDIO,
        KeyAction.END_AUDIO, KeyAction.TOGGLE_TORCH, KeyAction.ADJUST_VOLUME,
    )

    fun handle(action: KeyAction) {
        scope.launch {
            if (!preflight(action)) return@launch
            execute(core.onAction(action))
        }
    }

    fun shutdown() {
        segmentTimer?.cancel()
        stopWatermarkTimer()
        gps.stop()
        if (pipeline.isRecording) pipeline.stopSegment()
        if (audio.isRecording) audio.stop()
        sounds.release()
        scope.launch { pipeline.release() }
    }

    override suspend fun begin(): Boolean {
        // No video segment running → nothing to borrow; Site-voice records normally.
        if (core.state !is CaptureState.RecordingVideo) return true
        handoverActive = true
        pipeline.pauseSegmentAudio()
        return true
    }

    override suspend fun end() {
        if (!handoverActive) return
        handoverActive = false
        val ok = pipeline.resumeSegmentAudio()
        if (!ok) probe("mic handover: resume failed, segment audio stays silent")
    }

    /**
     * Who is recording this, stamped at capture time.
     *
     * Ownership used to be assigned at sign-in by claiming every unowned row,
     * which on a device that rotates between clients handed one client's
     * pending recordings to the next. Null here means nobody was signed in;
     * that row is then uploadable by nobody, which is the correct outcome —
     * guessing an owner is what caused the leak.
     */
    private fun currentAuthorSub(): String? =
        (AppState.loginState.value as? LoginState.LoggedIn)?.authorSub

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
                    pendingStopReason = cmd.reason
                    pipeline.stopSegment()
                    true
                }
                is CaptureCommand.EndPausedSession -> { endPausedSession(cmd.sessionId); true }
                is CaptureCommand.TakePhoto -> { takePhoto(cmd); true }
                is CaptureCommand.StartAudio -> startAudio(cmd)
                CaptureCommand.PauseAudio -> { pauseAudio(); true }
                is CaptureCommand.ResumeAudio -> resumeAudio(cmd)
                is CaptureCommand.EndAudio -> { endAudio(cmd); true }
                is CaptureCommand.EndPausedAudio -> { endPausedAudio(cmd); true }
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

    /**
     * The group this recording belongs to, resolved once at record-start.
     *
     * Held rather than re-read per segment: a session emits a segment every 30s,
     * and re-reading would let the one segment that happens to cross the expiry
     * boundary carry a different group id than its siblings — splitting a single
     * recording across two meetings. null = solo recording.
     */
    @Volatile private var activeGroupId: String? = null

    /**
     * Another device answered "the meeting has ended".
     *
     * Guarded on still being in a group, because the signal arrives on EVERY
     * chunk uploaded after the end — several per device — and acting twice would
     * stop a recording the user has since restarted.
     *
     * The group is cleared BEFORE stopping, so the stop cannot be attributed to
     * the meeting that just ended, and so a recording started immediately
     * afterwards is a fresh solo session. Post-meeting audio must never land in
     * the meeting.
     */
    private suspend fun onMeetingEndedElsewhere() {
        if (AppState.pendingGroup.value == null) return
        AppState.pendingGroup.value = null
        meetingPromptJob?.cancel()
        AppState.meetingExitPrompt.value = false
        when (core.state) {
            is CaptureState.RecordingVideo, is CaptureState.PausedVideo ->
                execute(core.onAction(KeyAction.END_VIDEO))
            is CaptureState.RecordingAudio, is CaptureState.PausedAudio ->
                execute(core.onAction(KeyAction.END_AUDIO))
            CaptureState.Idle -> Unit
        }
        // The ENDED line, not the asking one: the meeting is already over,
        // and the screen behind this is offering a fresh recording rather
        // than asking about the meeting.
        runCatching { meetingPromptSound.playEnded() }
        // Asked rather than assumed: ending a meeting is not finishing work.
        AppState.meetingResumePrompt.value = true
    }

    private var meetingPromptJob: Job? = null
    private val meetingPromptSound by lazy { MeetingPromptSound(context) }

    /**
     * Ask, 20s after this stop, whether the meeting has ended — the backstop for
     * everyone simply forgetting.
     *
     * Cancelled by the next start, so an immediate restart never gets asked; and
     * re-checked at fire time, because the twenty seconds are exactly when the
     * answer stops being wanted (see [MeetingPrompt.shouldAsk]).
     */
    private fun scheduleMeetingPrompt() {
        meetingPromptJob?.cancel()
        if (AppState.pendingGroup.value == null) return
        meetingPromptJob = scope.launch {
            delay(MeetingPrompt.DELAY_MILLIS)
            val recording = core.state !is CaptureState.Idle
            if (!MeetingPrompt.shouldAsk(AppState.pendingGroup.value, recording, System.currentTimeMillis())) return@launch
            AppState.meetingExitPrompt.value = true
            runCatching {
                if (!meetingPromptSound.play()) probe("meeting prompt: no voice line bundled, vibrated instead")
            }
        }
    }

    /** Best-effort session_open — fire-and-forget, never blocks capture. No-op if not logged in. */
    private fun fireSessionOpen(sessionId: String, kind: String, startedAtMillis: Long) {
        // A new recording answers the question by itself: they are not done.
        meetingPromptJob?.cancel()
        AppState.meetingExitPrompt.value = false
        val groupId = GroupExit.activeGroupId(AppState.pendingGroup.value, startedAtMillis)
        activeGroupId = groupId
        // Everything (incl. the `as GrandTimeApp` cast) runs inside the IO launch + runCatching so
        // nothing can throw onto the capture coroutine — this fires at record-start before the
        // segment is even opened (Opus review: keep the capture path crash-proof).
        scope.launch(Dispatchers.IO) {
            runCatching {
                val app = context.applicationContext as com.benzn.grandtime.GrandTimeApp
                val siteId = AppState.selectedSite.value?.id
                val token = app.authManager.freshIdToken() ?: return@launch
                sessionsApi.open(token, sessionId, startedAtMillis, kind, siteId, groupId)
            }
        }
    }

    /**
     * End of a session. A deliberate End ("end") goes through WorkManager; an
     * incidental stop ("idle") keeps the old fire-and-forget call.
     *
     * The split is deliberate. "end" is the signal that makes the confirmation
     * email arrive in about a minute instead of waiting out the 15-minute idle
     * inference, and over ten days it landed 8 times in 28 -- one blip lost it,
     * with no retry and nothing logged. "idle" carries no such promise: it is a
     * hint that the recorder stopped, the backend infers the same thing from
     * silence anyway, and giving it a durable queue would spend retries on a
     * message whose absence costs nothing.
     */
    private fun fireSessionClose(sessionId: String, endedAtMillis: Long, intent: String = "idle") {
        activeGroupId = null
        // Restart the expiry clock. The group deliberately OUTLIVES the recording —
        // a meeting where everyone stops to walk to the next building is still one
        // meeting, and expiring here would force a pointless re-scan. What ends the
        // group is the user answering the prompt, or 15 min of nothing.
        AppState.pendingGroup.value?.let {
            AppState.pendingGroup.value = it.copy(heldSinceMillis = endedAtMillis)
        }
        scheduleMeetingPrompt()
        if (intent == "end") {
            // Durable, and gated on this session's chunks being up: grace is 0 for
            // a deliberate End, so a close that overtakes the uploads finalizes a
            // transcript that is still arriving. See SessionCloseWorker.
            sessionCloseEnqueuer.enqueue(sessionId, endedAtMillis)
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val app = context.applicationContext as com.benzn.grandtime.GrandTimeApp
                val token = app.authManager.freshIdToken() ?: return@launch
                sessionsApi.close(token, sessionId, endedAtMillis, intent)
            }
        }
    }

    /** Deliberate End of a PausedVideo session: no live segment to finalize (already stopped by the
     *  preceding PAUSE), so just release resources and close with intent="end" directly. */
    private fun endPausedSession(sessionId: String) {
        stopWatermarkTimer(); gps.stop(); sounds.stopRecording()
        scope.launch { pipeline.release() }
        fireSessionClose(sessionId, System.currentTimeMillis(), "end")
    }

    private suspend fun startVideoSegment(cmd: CaptureCommand.StartVideoSegment): Boolean {
        val settings = settingsStore.settings.first()
        val file = storage.newFile(MediaStorage.Kind.VIDEO)
        val startedAt = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        // GPS + watermark (re)start on a COLD camera start — segment 1, OR resume after a power-saving
        // pause released the camera. A rollover reuses the live session (hasSession true) and keeps them
        // running, so don't restart on rollover. (GpsTracker.start() is a no-op without permission.)
        val cameraWasClosed = !pipeline.hasSession
        if (cameraWasClosed) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) gps.start()
            startWatermarkTimer(settings)
        }
        // session_open only at the true session start (segment 1) — resume must NOT re-open it.
        if (cmd.segmentIndex == 1) {
            fireSessionOpen(cmd.sessionId, kind = "video", startedAtMillis = startedAt)
        }
        val result = pipeline.startSegment(
            file = file,
            aspect = settings.aspectRatio,
            quality = settings.videoQuality,
            hevcPreferred = true,
            location = gps.freshFix()?.let { it.first.toFloat() to it.second.toFloat() },
            startAudioPaused = handoverActive, // rollover mid-handover → new segment records silent
        ) { error, message ->
            scope.launch {
                val finalizedId = finalizeVideoDbRow()
                if (error) {
                    stopWatermarkTimer()
                    gps.stop()
                    val endingSessionId = (core.state as? CaptureState.RecordingVideo)?.sessionId
                    execute(core.onFailure(message ?: "Video error"))
                    if (endingSessionId != null) fireSessionClose(endingSessionId, System.currentTimeMillis())
                    pipeline.release()
                } else {
                    if (finalizedId != null) {
                        val wifiOnly = settingsStore.settings.first().videoUploadWifiOnly
                        uploadEnqueuer.enqueue(finalizedId, requireUnmetered = uploadRequiresUnmetered("video", wifiOnly))
                    }
                    val reason = pendingStopReason
                    pendingStopReason = StopReason.ROLLOVER
                    val endingSessionId = (core.state as? CaptureState.RecordingVideo)?.sessionId
                    execute(core.onVideoFinalized(reason))
                    // reason != ROLLOVER guard: on a rollover whose NEXT segment then fails to start, the
                    // nested startVideoSegment failure path already reached Idle + fired close; guarding
                    // here prevents a second close for the same session (Opus review). PAUSE lands in
                    // PausedVideo (not Idle) so it never reaches this branch — pipeline/GPS stay live for
                    // a fast resume.
                    if (reason != StopReason.ROLLOVER && core.state is CaptureState.Idle) {
                        sounds.stopRecording()
                        stopWatermarkTimer()
                        gps.stop()
                        pipeline.release()
                        if (reason == StopReason.END && endingSessionId != null) {
                            fireSessionClose(endingSessionId, System.currentTimeMillis(), "end")
                        }
                    } else if (reason == StopReason.PAUSE && core.state is CaptureState.PausedVideo) {
                        // #3 power-saving pause: release the camera + GPS + watermark so the device can
                        // deep-sleep while paused (the wakelock is already released for PausedVideo).
                        // Resume cold-opens the camera and restarts GPS/watermark; the session stays open.
                        stopWatermarkTimer()
                        gps.stop()
                        pipeline.release()
                    }
                }
            }
        }
        if (result == null) {
            // 段起动失败也是终态(core.onFailure 一律回 Idle)——不管是不是段 1,都要收尾计时器/GPS。
            stopWatermarkTimer()
            gps.stop()
            sounds.stopRecording()
            val endingSessionId = (core.state as? CaptureState.RecordingVideo)?.sessionId
            execute(core.onFailure("Camera unavailable"))
            if (endingSessionId != null) fireSessionClose(endingSessionId, System.currentTimeMillis())
            pipeline.release()
            return false
        }
        // Close the rollover race (SegmentRecorder review): if Site-voice began a handover during
        // this segment's async startup — after startAudioPaused was already sampled — the new segment
        // could have opened its real mic despite an active borrow. Re-assert the pause now that the
        // segment is live. Idempotent; no-op when no handover is active. Both run on the same Main
        // dispatcher, so this observes any begin() that interleaved across startSegment's suspend.
        if (handoverActive) pipeline.pauseSegmentAudio()
        currentVideoRecordId = recordId
        currentVideoFile = file
        currentVideoStartedAt = startedAt
        dao.insert(
            CaptureRecord(
                id = recordId, kind = "video", filePath = file.absolutePath, fileName = file.name,
                startedAt = startedAt, codec = result.codec, resolution = result.resolution,
                segmentIndex = cmd.segmentIndex, sessionId = cmd.sessionId, createdAt = startedAt,
                groupId = activeGroupId,
                siteId = AppState.selectedSite.value?.id, authorSub = currentAuthorSub(),
            )
        )
        startSegmentTimer(settings.segmentSeconds)
        if (cmd.segmentIndex == 1) {
            sounds.startRecording()
        }
        // A cold camera (re)open resets Camera2Pipeline's internal torch flag to off; re-assert the
        // operator's torch state (TorchController is the source of truth) so a torch left ON before a
        // power-saving pause relights on resume, instead of needing a spurious double-toggle.
        if (cameraWasClosed && torch.torchOn) pipeline.setTorch(true)
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
     * 当前水印内容:用户名(有才加)+ 时间戳 + GPS 坐标行(优先 gps.freshFix(),超 30s 未刷新则回退到
     * gps.agedFix() 在 GPS_FALLBACK_WINDOW_MS 内的最后一次定位并标注age——室内常见:GNSS 弱信号+ROM
     * 无网络定位,不这样会一直挂"Locating…";回退坐标必标 age,绝不冒充实时位置)+无定位区分
     * "未授权"/"定位中…"。epochMillis 默认戳章此刻(录像 1Hz 刷新用);单拍水印(stampPhotoWatermark)
     * 传快门时刻,保证证据时间戳=按下快门那刻而非之后解码/叠加处理的耗时之后。
     */
    private fun currentWatermarkContent(epochMillis: Long = System.currentTimeMillis()): WatermarkContent {
        val name = (AppState.loginState.value as? LoginState.LoggedIn)?.displayName
        val g = gpsWatermark(gps.freshFix(), gps.agedFix())
        val noFixText = if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) "Locating…" else "No location permission"
        val site = AppState.selectedSite.value
        // Line 4 prefers the site's street address (indoor location anchor); falls back to the
        // site name when no address is on file, so existing sites without an address keep working.
        val siteLine = site?.address?.takeIf { it.isNotBlank() } ?: site?.name?.takeIf { it.isNotBlank() }
        return Watermark.build(
            userName = name,
            epochMillis = epochMillis,
            lat = g.lat,
            lon = g.lon,
            address = siteLine,
            zone = ZoneId.systemDefault(),
            noFixText = noFixText,
            fixAgeLabel = g.ageLabel,
        )
    }

    /** 停水印刷新定时器 + 清空叠加位图(pipeline 相机丢失→重连会粘住 pendingWatermark,不清会重新冒出来)。 */
    private fun stopWatermarkTimer() {
        watermarkTimer?.cancel()
        watermarkTimer = null
        pipeline.setWatermarkBitmap(null)
    }

    private fun startSegmentTimer(seconds: Int) {
        segmentTimer?.cancel()
        segmentTimer = scope.launch {
            delay(seconds * 1000L)
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
                    groupId = activeGroupId,
                    siteId = AppState.selectedSite.value?.id, authorSub = currentAuthorSub(),
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
        // 只有活跃录像态保留相机会话;否则(Idle/录音/暂停)拍完释放相机——暂停期(#3 省电)要让相机
        // 关着,若拍照重开了会话,拍完必须再释放,否则 resume 会误判"相机没冷启"而不重启 GPS/水印。
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
     * 照片水印:解码 → 若 EXIF orientation 隐含旋转则先转正(Matrix.postRotate,90/180/270;镜像变体按其旋转分量处理,
     * 本机不镜像)→ Canvas 底部叠 WatermarkRenderer.render 出的水印带(按图片宽,此时"底部"=转正后真底部)
     * → 重压 JPEG(质量与拍照/降采样同一档)。转正过的图 tag 写回 NORMAL(1,像素已正);未转正维持原
     * 行为(原样回写 tag,absent 则不写)。IO 线程。失败整体吞掉(runCatching):没水印的照片也比丢照片强。
     *
     * 这段原本写着"本机 takePhoto 像素不转、tag 转",那是错的,并且正是照片被转 90°、水印跑到右侧的原因。
     * 实测 prod 上一张真实照片:存储像素 1944x2592(竖),EXIF Orientation = 0(连合法值都不是,合法是 1..8)。
     * 传感器 5MP 4:3 正立应是 2592x1944,所以本机 HAL 是把 JPEG_ORIENTATION **烤进像素**、不写可用 tag。
     * 于是这里读不到旋转 → 不转正 → 把水印盖在一张已经被转过的竖图底部 = 真实场景的右边缘。
     * 修法在 Camera2Pipeline:photoJpegOrientation = 0(与 videoOrientationHint 同理)。此处逻辑保持不变——
     * 它对"真的写了 tag"的设备仍然正确,只是在本机不会触发。
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

    /**
     * Rolling audio segments (like video): AudioRecorder self-rolls on its worker thread into
     * fixed-length segment files without ever stopping the mic, seeding each new segment with the
     * last ~2s of the previous one (overlapBytesFor) so a sentence crossing a boundary lands in
     * both files. Every finalized segment surfaces via onSegment (worker thread) — this closure
     * only does scope.launch{...} and storage.newFile(...), both safe off the main thread — and
     * gets its own DB row + upload enqueue at finalization time (see onAudioSegmentFinalized),
     * unlike the old single-file path that pre-inserted one row upfront.
     */
    private suspend fun startAudio(cmd: CaptureCommand.StartAudio): Boolean {
        val settings = settingsStore.settings.first()
        val segBytes = segmentBytesFor(settings.segmentSeconds)
        val overlap = overlapBytesFor(2)
        val first = storage.newFile(MediaStorage.Kind.AUDIO)
        val sessionId = cmd.sessionId
        val started = audio.start(
            file = first,
            segmentBytes = segBytes,
            overlapBytes = overlap,
            startIndex = 1, // segment 1 of a brand-new session
            nextFile = { storage.newFile(MediaStorage.Kind.AUDIO) },
            onSegment = { seg -> scope.launch { onAudioSegmentFinalized(seg, sessionId) } },
        )
        if (!started) {
            execute(core.onFailure("Audio recorder unavailable")); return false
        }
        sounds.startRecording()
        probe("audio started: ${first.name}")
        fireSessionOpen(sessionId, kind = "audio", startedAtMillis = System.currentTimeMillis())
        return true
    }

    /** Runs on [scope] (not the AudioRecorder worker thread — the onSegment closure only hops
     *  here via scope.launch, per the worker-thread-safety contract). Inserts a complete DB row
     *  for the just-finalized segment (kept out of startAudio so crash recovery still applies:
     *  an interrupted final segment leaves an orphan .pcm that AudioRecoverer/FilesReconciler
     *  turns into a row on next launch, same as before) and enqueues its upload. */
    private suspend fun onAudioSegmentFinalized(seg: AudioSegment, sessionId: String) {
        val id = UUID.randomUUID().toString()
        dao.insert(
            CaptureRecord(
                id = id, kind = "audio", filePath = seg.file.absolutePath, fileName = seg.file.name,
                startedAt = seg.startedAtMs, endedAt = seg.endedAtMs, durationMs = seg.endedAtMs - seg.startedAtMs,
                sizeBytes = seg.file.length(), codec = "wav", segmentIndex = seg.index, sessionId = sessionId,
                groupId = activeGroupId,
                siteId = AppState.selectedSite.value?.id, authorSub = currentAuthorSub(), createdAt = seg.startedAtMs,
            )
        )
        uploadEnqueuer.enqueue(id)
        scan(seg.file.absolutePath)
        probe("audio segment ${seg.index} saved: ${seg.file.name} (${seg.file.length()} bytes)")
    }

    /** Pause: stop the recorder — AudioRecorder.stop() finalizes the last (in-flight) segment
     *  synchronously before returning — and remember the NEXT segment index for resume, so a
     *  resumed session's chunk keys never collide with the ones already saved. CaptureCore already
     *  moved the state to PausedAudio synchronously in onAction; no session_close here (the session
     *  stays open across a pause, mirroring the video pause). */
    private suspend fun pauseAudio() {
        val ok = audio.stop()
        if (!ok) probe("audio stop reported error")
        audioResumeIndex = audio.lastSegmentIndex + 1
        // 暂停期间若拍过照,相机会话可能残留——收尾释放;录像中不会走到这。
        if (!pipeline.isRecording) pipeline.release()
        // Pause, not stop: the session stays open and a resume continues it.
        sounds.stopRecording(speak = false)
    }

    /** Resume: restart the recorder continuing the SAME session at the next segment index — no
     *  session_open (the session never closed on pause). */
    private suspend fun resumeAudio(cmd: CaptureCommand.ResumeAudio): Boolean {
        val settings = settingsStore.settings.first()
        val segBytes = segmentBytesFor(settings.segmentSeconds)
        val overlap = overlapBytesFor(2)
        val first = storage.newFile(MediaStorage.Kind.AUDIO)
        val sessionId = cmd.sessionId
        val started = audio.start(
            file = first,
            segmentBytes = segBytes,
            overlapBytes = overlap,
            startIndex = audioResumeIndex,
            nextFile = { storage.newFile(MediaStorage.Kind.AUDIO) },
            onSegment = { seg -> scope.launch { onAudioSegmentFinalized(seg, sessionId) } },
        )
        if (!started) {
            // The session was already open (opened at the original start, not here) — a failed resume
            // abandons it, so close it out instead of leaving a phantom open session server-side.
            execute(core.onFailure("Audio recorder unavailable"))
            fireSessionClose(sessionId, System.currentTimeMillis())
            return false
        }
        sounds.startRecording()
        probe("audio resumed: ${first.name} (segment $audioResumeIndex)")
        return true
    }

    /** Deliberate End while recording: stop the recorder + close the session with intent="end". */
    private suspend fun endAudio(cmd: CaptureCommand.EndAudio) {
        val ok = audio.stop()
        if (!ok) probe("audio stop reported error")
        // 录音期间若拍过照,相机会话可能残留——收尾释放;录像中不会走到这。
        if (!pipeline.isRecording) pipeline.release()
        sounds.stopRecording()
        fireSessionClose(cmd.sessionId, System.currentTimeMillis(), "end")
    }

    /** Deliberate End of a PausedAudio session: no recorder to stop (already stopped on pause) —
     *  just release any leftover camera + close with intent="end" directly. */
    private suspend fun endPausedAudio(cmd: CaptureCommand.EndPausedAudio) {
        if (!pipeline.isRecording) pipeline.release()
        fireSessionClose(cmd.sessionId, System.currentTimeMillis(), "end")
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
