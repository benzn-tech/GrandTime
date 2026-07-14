# SP-Capture2-P2:相机层 Camera2+MediaCodec 对等重写 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **本计划分两部分**:**Part A(T1-T5)= 管线核心**,每个新组件建好后用 Diagnostics 屏已有的 "Run Capture2 probe" 临时按钮**真机验证**(像 P1/SP3b 那样先定生死);Part B(T6-T11)= 接入生产 `CaptureManager` + 替换 CameraX + 设置项 + 预览 + 真机对等验收。相机/GL/编码器类**无法 JVM 单测**(需硬件),故这些任务以"实现 + 真机 probe 验证"代替 TDD(纯逻辑任务 T1/T2 仍 TDD)。控制器负责真机执行(adb 装机、点 Diagnostics 按钮、`adb logcat -s CAP2PROBE`、拉回 mp4/jpg 判定)。

**Goal:** 把录制核心从 CameraX 重写为 Camera2 + MediaCodec 自控管线,达成 HEVC 默认(降级 H.264)、4:3/16:9 可切、全分辨率录像中拍照(满 5MP),并保住 SP3c 全部现有功能(分段/预览/息屏/手电/音频/灯语/上传)。

**Architecture:** Camera2 `CameraDevice` → `CameraCaptureSession` 固定 **2 路输出**:①一个我们自有的 GL `SurfaceTexture`(相机帧),②一个 JPEG `ImageReader`(拍照,满 5MP)。一个 EGL/GL 渲染器把相机外部纹理(OES)画到 MediaCodec HEVC 编码器输入 Surface(**始终**)与屏上预览 Surface(**挂了才画**)——预览挂/摘只是切 GL 目标,**永不动相机会话**,从而"预览挂摘/息屏不中断录像"(SP3c 硬要求)。每段一个 `MediaMuxer`(`setLocation` 写起点 GPS)。拍照 = 对 JPEG reader 发 `STILL_CAPTURE`(与录像并行,全分辨率)。`CaptureManager` 的状态机/DB/上传/灯/键编排**不变**,只把相机调用换成新 `Camera2Pipeline`,并**删除已被 P1 证明多余的抓帧(frame-grab)子系统**。

**Tech Stack:** Kotlin,Android framework only:`android.hardware.camera2`、`android.media.MediaCodec`/`MediaCodecList`/`MediaMuxer`/`ImageReader`、`android.opengl`(EGL14 + GLES20,外部 OES 纹理)、Compose(SurfaceView via AndroidView)。**不引原生库、不加 Gradle 依赖**(armeabi 约束)。

## Global Constraints

以下为 spec(`docs/superpowers/specs/2026-07-14-sp-capture2-camera2-pipeline-design.md`)与 P1 findings(`docs/superpowers/plans/.capture2-p1-findings.md`)的项目级硬约束,每个任务的要求都隐含包含本节:

- **全走 Android framework**:Camera2 / MediaCodec / MediaMuxer / ImageReader / OpenGL ES(EGL14+GLES20)。**不引任何原生库、不加任何新 Gradle 依赖**(设备 ABI 仅 armeabi 32 位;framework 无此约束)。
- **视频编码硬上限 = 1920×1088**(HW HEVC/AVC 同,P1 实测)。编码尺寸从相机 `StreamConfigurationMap` 实际支持尺寸里选,且 ≤ 1920×1088:**4:3 默认 = 1440×1080**,**16:9 = 1920×1080**。满 5MP 视频不可行(仅软编,弃)。
- **HEVC(`video/hevc`)默认**;`MediaCodecList` 查不到 / `createEncoderByType` / `configure` 抛异常 → 自动降 `video/avc`。DB `codec` 列写实际编码("hevc" 或 "h264")。
- **拍照走独立 JPEG `ImageReader`**(不过编码器),满传感器 4:3 尺寸(照片精度设置换算);**录像中拍照全分辨率可用**(P1 实测三流 + STILL_CAPTURE 满 5MP 成功)。
- **宽高比默认 4:3**(满传感器广角;P1 实测本机无 0.5x 超广角,4:3 满画幅即最宽)。设置项可切 16:9。
- **保住 SP3c 全部功能(回归即失败)**:分段视频滚动、**录像中拍照(改进为全分辨率)**、Preview 前台挂/摘**不中断录像**、**息屏后台录制**(OverlayGuard 维持进程 + FGS camera|microphone)、手电、录音(复用现有 `AudioRecorder`,不动)、物理键映射、**SP3b 物理灯语**(录像红/录音黄/待机蓝,由 `AppState.captureState` 驱动)、SP4 上传 + 打 `siteId`、系统提示音、`AppState.lastPhotoFlash` 快门闪现、Files 缩略图/上传角标。
- **`CaptureCore`/`CaptureState`/`CaptureCommand` 契约不变**——`CaptureCoreTest`(13 测)必须原样全绿。
- **媒体路径不变**:`MediaStorage.newFile(VIDEO)` → mp4,`resolution` 列写 "WxH"(如 "1440x1080")。
- **minSdk=targetSdk=33,compileSdk=35**。
- 每阶段特性分支 + SDD + 真机验收,过了再合 main。

## 现有契约(重写须保住,来自代码勘察)

- `CaptureManager`(`capture/CaptureManager.kt`)构造:`(context, scope, settingsStore, dao, notify:(String)->Unit, probe:(String)->Unit, uploadEnqueuer)`。`CoreService` 已按此构造,**不改构造签名**。
- `CaptureCore` 发的命令(不变):`StartVideoSegment(sessionId, segmentIndex)`、`StopVideo(rollToNext)`、`TakePhoto(sessionId)`、`StartAudio`/`StopAudio`、`ToggleTorch`、`CycleVolume`、`Vibrate(times)`、`Notify(text)`。
- `MediaStorage.Kind`:VIDEO→mp4 / AUDIO→m4a / PHOTO→jpg。`newFile(kind, startMillis)`。
- `CaptureRecord` 关键列:`id,kind,filePath,fileName,startedAt,endedAt,codec,resolution,segmentIndex,sessionId,createdAt,siteId,sizeBytes`;DAO:`insert/finalize(id,ended,durationMs,size)/updatePath/markMissing/listByUploadStatus`。
- `AppState`:`captureState: MutableStateFlow<CaptureState>`、`previewSurface`(**现为 `Preview.SurfaceProvider?`,本计划改为 `android.view.Surface?`**)、`screenOffRequest: MutableStateFlow<Boolean>`、`lastPhotoFlash: MutableStateFlow<String?>`、`selectedSite`、`mediaScope`。
- `RecordingScreen`(`ui/RecordingScreen.kt`)现用 CameraX `PreviewView` + `ScaleType.FIT_CENTER` 写 `AppState.previewSurface = previewView.surfaceProvider`;本计划改 `SurfaceView` + 手动 letterbox 写 `Surface`。
- `CoreService` SP3b 灯循环**轮询** `AppState.captureState.value`(不 collect),与相机层解耦——**不动**。
- CameraX 仅 5 文件引用:`capture/CameraSession.kt`(删)、`capture/VideoRecorder.kt`(删)、`capture/PhotoTaker.kt`(删)、`core/AppState.kt`(改类型)、`ui/RecordingScreen.kt`(改控件)。`TorchController`/`CaptureManager` 不直接 import androidx.camera,只调 `CameraSession`。
- 测试:相机 plumbing **零单测**;仅 `CaptureCoreTest`(纯,保绿)、`FrameOffsetTest`(测 `frameOffsetMicros`,**本计划随 frame-grab 一起删**)。

## File Structure

**新建**(`capture/camera2/`):
- `VideoSpec.kt` — 纯数据 + 尺寸/码率选择(`VideoSizeSelector`)。**唯一 JVM 可测的相机相关逻辑。**
- `SegmentRecorder.kt` — 单段 MediaCodec(HEVC→AVC 降级)+ MediaMuxer;暴露编码器输入 `Surface`;start/stop/drain;`setLocation`/`orientationHint`。
- `GlRecordPipeline.kt` — EGL14 + GLES20 外部 OES 纹理渲染器:相机 `SurfaceTexture` → 画到编码器 Surface(始终)+ 预览 Surface(挂了才画)。
- `Camera2Pipeline.kt` — 门面:`CameraDevice` + `CaptureSession([glSurfaceTexture, jpegReader])` + GL + SegmentRecorder + STILL_CAPTURE 拍照 + 手电。`CaptureManager` 只跟它打交道。

**修改**:
- `core/SettingsStore.kt` — 加 `AspectRatio` enum + 持久化。
- `core/AppState.kt` — `previewSurface: MutableStateFlow<android.view.Surface?>`。
- `capture/CaptureManager.kt` — 相机调用换 `Camera2Pipeline`;删 frame-grab;codec/resolution 取自管线。
- `capture/TorchController.kt` — Camera2(录像态经管线,空闲 `setTorchMode`)。
- `ui/RecordingScreen.kt` — `SurfaceView` + letterbox + 写 `Surface`。
- `ui/SettingsScreen.kt` — 加"录制宽高比"选项行。
- `app/build.gradle.kts` + `gradle/libs.versions.toml` — 移除 5 个 CameraX 依赖(T10,全部引用清除后)。
- `debug/Capture2Probe.kt` + `ui/DiagnosticsScreen.kt` — Part A 各任务加临时 probe 方法/按钮真机验证(T10 一并清理)。

**删除**(T10):`capture/CameraSession.kt`、`capture/VideoRecorder.kt`、`capture/PhotoTaker.kt`、`CaptureManager.kt` 内 `frameOffsetMicros`、`app/src/test/.../capture/FrameOffsetTest.kt`。

---

# Part A — 管线核心(真机 probe 验证)

### Task 1: 录制宽高比设置(`AspectRatio`)— TDD

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/core/SettingsStore.kt`
- Test: `app/src/test/java/com/benzn/grandtime/core/SettingsStoreTest.kt`(追加)

**Interfaces:**
- Consumes: 无(独立设置)。
- Produces: `enum class AspectRatio(val label: String) { RATIO_4_3("4:3"), RATIO_16_9("16:9") }`;`RecordingSettings.aspectRatio: AspectRatio`(默认 `RATIO_4_3`);`SettingsStore.setAspectRatio(value: AspectRatio)`。

- [ ] **Step 1: 写失败测试**——在 `SettingsStoreTest.kt` 追加(沿用该文件既有的内存 DataStore 构造模式;参照文件顶部 helper):

```kotlin
    @Test
    fun `aspect ratio defaults to 4-3`() = runTest {
        val store = SettingsStore(testDataStore())
        assertEquals(AspectRatio.RATIO_4_3, store.settings.first().aspectRatio)
    }

    @Test
    fun `aspect ratio roundtrips`() = runTest {
        val store = SettingsStore(testDataStore())
        store.setAspectRatio(AspectRatio.RATIO_16_9)
        assertEquals(AspectRatio.RATIO_16_9, store.settings.first().aspectRatio)
    }

    @Test
    fun `unknown stored aspect ratio falls back to 4-3`() = runTest {
        val ds = testDataStore()
        ds.edit { it[stringPreferencesKey("aspect_ratio")] = "RATIO_99_9" }
        assertEquals(AspectRatio.RATIO_4_3, SettingsStore(ds).settings.first().aspectRatio)
    }
```

> 注:若 `SettingsStoreTest.kt` 现无名为 `testDataStore()` 的 helper,用文件里已有的等价 DataStore 构造方式(与既有 `video quality roundtrips` 等测试一致);`stringPreferencesKey`/`edit` 已在既有测试导入。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.benzn.grandtime.core.SettingsStoreTest"`
Expected: FAIL(`aspectRatio` unresolved / `setAspectRatio` unresolved)。

- [ ] **Step 3: 实现**——`SettingsStore.kt`:

`enum class VideoQuality` 附近加:
```kotlin
enum class AspectRatio(val label: String) { RATIO_4_3("4:3"), RATIO_16_9("16:9") }
```
`RecordingSettings` 加字段(放末尾,保持既有字段不变):
```kotlin
data class RecordingSettings(
    val videoQuality: VideoQuality = VideoQuality.P1080,
    val segmentMinutes: Int = 5,
    val photoQuality: PhotoQuality = PhotoQuality.HIGH,
    val photoResolution: PhotoResolution = PhotoResolution.MAX,
    val screenOffMinutes: Int = 3,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
)
```
`companion object` 加 key:
```kotlin
        private val KEY_ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
```
`settings` map 里加解析(放 `screenOffMinutes` 之后):
```kotlin
            aspectRatio = prefs[KEY_ASPECT_RATIO]
                ?.let { name -> AspectRatio.entries.firstOrNull { it.name == name } }
                ?: AspectRatio.RATIO_4_3,
```
加 setter:
```kotlin
    suspend fun setAspectRatio(value: AspectRatio) {
        dataStore.edit { it[KEY_ASPECT_RATIO] = value.name }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.benzn.grandtime.core.SettingsStoreTest"`
Expected: PASS(含既有测试全绿)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/core/SettingsStore.kt app/src/test/java/com/benzn/grandtime/core/SettingsStoreTest.kt
git commit -m "feat(settings): add AspectRatio (4:3 default / 16:9) setting"
```

---

### Task 2: 编码尺寸/码率选择器(`VideoSizeSelector`)— TDD

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/camera2/VideoSpec.kt`
- Test: `app/src/test/java/com/benzn/grandtime/capture/camera2/VideoSizeSelectorTest.kt`

**Interfaces:**
- Consumes: `AspectRatio`(T1)、`VideoQuality`(既有)、`android.util.Size`。
- Produces:
  - `data class VideoSpec(val width: Int, val height: Int, val bitRate: Int, val orientationHint: Int)`
  - `data class VideoSize(val width: Int, val height: Int)`(纯 Kotlin 类型,替代 `android.util.Size`,保证选择器可 JVM 纯单测、不引 Robolectric;`Camera2Pipeline` 在边界处把 `getOutputSizes()` 的 `android.util.Size` map 成 `VideoSize`)
  - `object VideoSizeSelector { const val ENCODER_MAX_W=1920; const val ENCODER_MAX_H=1088; fun pickSize(aspect: AspectRatio, quality: VideoQuality, supported: List<VideoSize>): VideoSize; fun bitRateFor(quality: VideoQuality): Int }`

> **不用 `android.util.Size`**:它在默认 JVM 单测里被 android.jar 打桩(方法抛 "not mocked"),会逼引 Robolectric——违反"不加依赖"约束。故选择器用纯 `VideoSize`,测试纯 JUnit。

- [ ] **Step 1: 写失败测试**——`VideoSizeSelectorTest.kt`(用 P1 实测的真实支持尺寸列表):

```kotlin
package com.benzn.grandtime.capture.camera2

import com.benzn.grandtime.core.AspectRatio
import com.benzn.grandtime.core.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSizeSelectorTest {
    // P1 实测 F2SP 后置 cam0 支持的视频/预览尺寸(节选,含各宽高比)
    private val supported = listOf(
        VideoSize(2592, 1944), VideoSize(1920, 1440), VideoSize(1440, 1080), VideoSize(1280, 960),
        VideoSize(960, 720), VideoSize(640, 480),                       // 4:3
        VideoSize(2560, 1440), VideoSize(1920, 1080), VideoSize(1280, 720), VideoSize(640, 360), // 16:9
    )

    @Test fun `4-3 1080 picks 1440x1080 within encoder cap`() {
        assertEquals(VideoSize(1440, 1080), VideoSizeSelector.pickSize(AspectRatio.RATIO_4_3, VideoQuality.P1080, supported))
    }

    @Test fun `16-9 1080 picks 1920x1080`() {
        assertEquals(VideoSize(1920, 1080), VideoSizeSelector.pickSize(AspectRatio.RATIO_16_9, VideoQuality.P1080, supported))
    }

    @Test fun `4-3 720 picks 960x720`() {
        assertEquals(VideoSize(960, 720), VideoSizeSelector.pickSize(AspectRatio.RATIO_4_3, VideoQuality.P720, supported))
    }

    @Test fun `16-9 720 picks 1280x720`() {
        assertEquals(VideoSize(1280, 720), VideoSizeSelector.pickSize(AspectRatio.RATIO_16_9, VideoQuality.P720, supported))
    }

    @Test fun `4-3 480 picks 640x480`() {
        assertEquals(VideoSize(640, 480), VideoSizeSelector.pickSize(AspectRatio.RATIO_4_3, VideoQuality.P480, supported))
    }

    @Test fun `never exceeds encoder cap 1920x1088`() {
        val s = VideoSizeSelector.pickSize(AspectRatio.RATIO_4_3, VideoQuality.P1080, supported)
        assertTrue(s.width <= 1920 && s.height <= 1088)
    }

    @Test fun `bitrate decreases with lower quality`() {
        assertTrue(VideoSizeSelector.bitRateFor(VideoQuality.P1080) > VideoSizeSelector.bitRateFor(VideoQuality.P720))
        assertTrue(VideoSizeSelector.bitRateFor(VideoQuality.P720) > VideoSizeSelector.bitRateFor(VideoQuality.P480))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.benzn.grandtime.capture.camera2.VideoSizeSelectorTest"`
Expected: FAIL(类不存在)。

- [ ] **Step 3: 实现**——`VideoSpec.kt`:

```kotlin
package com.benzn.grandtime.capture.camera2

import com.benzn.grandtime.core.AspectRatio
import com.benzn.grandtime.core.VideoQuality

/** 一段录制的编码参数(尺寸/码率/方向)。 */
data class VideoSpec(
    val width: Int,
    val height: Int,
    val bitRate: Int,
    val orientationHint: Int,
)

/** 尺寸纯类型(替代 android.util.Size,保证选择器 JVM 纯单测)。 */
data class VideoSize(val width: Int, val height: Int)

/**
 * 从相机支持尺寸里选编码尺寸:精确匹配宽高比、≤ 硬编上限(1920×1088)、
 * 高度就近 quality 目标(优先 ≤ 目标的最大档,无则取 > 目标里最小档)。纯函数,JVM 可测。
 */
object VideoSizeSelector {
    const val ENCODER_MAX_W = 1920
    const val ENCODER_MAX_H = 1088

    private fun targetHeight(q: VideoQuality) = when (q) {
        VideoQuality.P1080 -> 1080
        VideoQuality.P720 -> 720
        VideoQuality.P480 -> 480
    }

    fun bitRateFor(q: VideoQuality) = when (q) {
        VideoQuality.P1080 -> 20_000_000
        VideoQuality.P720 -> 10_000_000
        VideoQuality.P480 -> 6_000_000
    }

    fun pickSize(aspect: AspectRatio, quality: VideoQuality, supported: List<VideoSize>): VideoSize {
        val (aw, ah) = when (aspect) {
            AspectRatio.RATIO_4_3 -> 4 to 3
            AspectRatio.RATIO_16_9 -> 16 to 9
        }
        val target = targetHeight(quality)
        val inCap = supported.filter { it.width <= ENCODER_MAX_W && it.height <= ENCODER_MAX_H }
        val exact = inCap.filter { it.width * ah == it.height * aw }.sortedByDescending { it.height }
        if (exact.isNotEmpty()) {
            return exact.firstOrNull { it.height <= target } ?: exact.last()
        }
        // 兜底:无精确宽高比档时,取上限内、高度最接近目标的任意档(理论上不该发生)。
        return inCap.minByOrNull { kotlin.math.abs(it.height - target) } ?: VideoSize(1280, 720)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.benzn.grandtime.capture.camera2.VideoSizeSelectorTest"`
Expected: PASS(7 测全绿)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/camera2/VideoSpec.kt app/src/test/java/com/benzn/grandtime/capture/camera2/VideoSizeSelectorTest.kt
git commit -m "feat(capture2): VideoSizeSelector — pick encode size/bitrate under 1920x1088 cap"
```

---

### Task 3: 单段编码器 `SegmentRecorder`(MediaCodec HEVC/AVC + MediaMuxer)— 真机 probe

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/camera2/SegmentRecorder.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt`(加临时 `probeSegmentRecorder()` 真机验证)

**Interfaces:**
- Consumes: `VideoSpec`(T2)。
- Produces:
  - `class SegmentRecorder`
  - `fun prepare(file: File, spec: VideoSpec, hevcPreferred: Boolean, location: Pair<Float, Float>?): Surface`(配置编码器+muxer,返回**编码器输入 Surface** 供 GL/相机画;不启动 drain)
  - `fun start()`(启动编码器 + drain 线程)
  - `fun stop()`(signalEndOfInputStream → drain 到 EOS → 关 muxer/codec;幂等)
  - `val actualCodec: String`(prepare 后可读:"hevc" 或 "h264")

> 关键:`createInputSurface()` 必须在 `configure()` 之后、`start()` 之前调。HEVC configure 抛异常 → 降级 AVC。drain 在独立线程,`stop()` 里 join。

- [ ] **Step 1: 实现 `SegmentRecorder.kt`**

```kotlin
package com.benzn.grandtime.capture.camera2

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * 单段视频编码:相机/GL 帧经输入 Surface → MediaCodec(HEVC 优先,失败降 AVC)→ MediaMuxer(mp4)。
 * 分段调度由 CaptureManager 定时器驱动(每段一个新 SegmentRecorder 实例)。
 */
class SegmentRecorder(private val probe: (String) -> Unit = {}) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var draining = false
    private var trackIndex = -1
    private var muxerStarted = false

    var actualCodec: String = ""  // prepare() 后才有值("hevc"/"h264")
        private set

    /** 配置编码器 + muxer,返回编码器输入 Surface。未启动 drain(留给 start())。 */
    fun prepare(file: File, spec: VideoSpec, hevcPreferred: Boolean, location: Pair<Float, Float>?): Surface {
        val enc = createEncoder(spec, hevcPreferred)
        codec = enc
        val surface = enc.createInputSurface()
        inputSurface = surface
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(spec.orientationHint)
            if (location != null) runCatching { setLocation(location.first, location.second) }
        }
        return surface
    }

    /** 尝试 HEVC;configure 失败(不支持/尺寸越界)→ 降 AVC。设 actualCodec。 */
    private fun createEncoder(spec: VideoSpec, hevcPreferred: Boolean): MediaCodec {
        fun build(mime: String): MediaCodec {
            val fmt = MediaFormat.createVideoFormat(mime, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(mime)
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                runCatching { c.release() } // 释放半配置的编码器,避免泄漏 HW codec
                throw e
            }
            return c
        }
        if (hevcPreferred) {
            try {
                val c = build("video/hevc"); actualCodec = "hevc"; return c
            } catch (e: Exception) {
                probe("HEVC 编码器建失败降 AVC: ${e.message}")
            }
        }
        val c = build("video/avc"); actualCodec = "h264"; return c
    }

    fun start() {
        val c = codec ?: error("prepare() first")
        c.start()
        draining = true
        drainThread = Thread { drainLoop() }.apply { name = "seg-drain"; start() }
    }

    private fun drainLoop() {
        val c = codec ?: return
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = m.addTrack(c.outputFormat); m.start(); muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && muxerStarted &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0
                    ) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        runCatching { m.writeSampleData(trackIndex, buf, info) }
                    }
                    runCatching { c.releaseOutputBuffer(idx, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> { if (!draining) break }
            }
        }
    }

    /** 结束段:EOS 信号 → drain 收到 EOS 排空尾帧 → 关 muxer/codec。幂等。 */
    fun stop() {
        if (codec == null) return
        runCatching { codec?.signalEndOfInputStream() }
        drainThread?.join(2500)              // 等 drain 收到 EOS 排空尾帧后自行退出
        if (drainThread?.isAlive == true) {
            draining = false                 // 兜底:EOS 未到时解开 drain 循环
            drainThread?.join(500)
            if (drainThread?.isAlive == true) probe("segment drain 线程未在超时内退出")
        }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null; muxer = null; inputSurface = null; drainThread = null
        muxerStarted = false; trackIndex = -1
    }
}
```

- [ ] **Step 2: 加临时真机 probe**——在 `Capture2Probe.kt` 加(直接用相机喂 SegmentRecorder 输入面,隔离验编码器+muxer,不涉 GL):

```kotlin
    /** Task3 验证:相机 → SegmentRecorder 输入面 → mp4(隔离测 HEVC/AVC+muxer+setLocation)。 */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun probeSegmentRecorder(): String {
        val dir = java.io.File("/sdcard/FieldSight/_probe").apply { mkdirs() }
        val out = java.io.File(dir, "probe_seg.mp4")
        val rec = com.benzn.grandtime.capture.camera2.SegmentRecorder { log(it) }
        val spec = com.benzn.grandtime.capture.camera2.VideoSpec(1440, 1080, 20_000_000, 90)
        val ht = android.os.HandlerThread("seg").apply { start() }
        val handler = android.os.Handler(ht.looper)
        var camera: android.hardware.camera2.CameraDevice? = null
        var session: android.hardware.camera2.CameraCaptureSession? = null
        try {
            val surface = rec.prepare(out, spec, hevcPreferred = true, location = -36.85f to 174.76f)
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val cam: android.hardware.camera2.CameraDevice = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cm.openCamera(backId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(c: android.hardware.camera2.CameraDevice) { cont.resume(c) {} }
                    override fun onDisconnected(c: android.hardware.camera2.CameraDevice) { c.close() }
                    override fun onError(c: android.hardware.camera2.CameraDevice, e: Int) { c.close(); if (cont.isActive) cont.cancel(RuntimeException("open err $e")) }
                }, handler)
            }
            camera = cam
            rec.start()
            val sess: android.hardware.camera2.CameraCaptureSession = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOf(surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) { cont.resume(s) {} }
                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) { if (cont.isActive) cont.cancel(RuntimeException("cfg fail")) }
                }, handler)
            }
            session = sess
            val req = cam.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD).apply { addTarget(surface) }.build()
            sess.setRepeatingRequest(req, null, handler)
            kotlinx.coroutines.delay(3000)
            sess.stopRepeating()
            rec.stop()
            return "OK codec=${rec.actualCodec} size=${out.length()} path=${out.absolutePath}"
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { rec.stop() }
            ht.quitSafely()
        }
    }
```
并在 `runAll()` 里 `enumerateFov()`/`enumerate()` 之后、`recordClip` 之前(或替换 recordClip 那两行)加:
```kotlin
        val seg = runCatching { probeSegmentRecorder() }.getOrElse { "seg 抛异常: ${it.javaClass.simpleName}: ${it.message}" }
        log("probeSegmentRecorder => $seg")
```

- [ ] **Step 3: 构建**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL(Dropbox 锁致 "Could not delete" 则重跑一次)。

- [ ] **Step 4: 真机验证**(控制器执行)——装机、进 Diagnostics 点 "Run Capture2 probe"、`adb logcat -s CAP2PROBE` 看 `probeSegmentRecorder => OK codec=hevc size=...`;`adb pull //sdcard/FieldSight/_probe/probe_seg.mp4` 拉回确认**可播**、编码=hevc、**位置元数据写入**(`©xyz` atom 含 ISO-6709)。**能出可播 mp4 = SegmentRecorder 绿灯**。记进 `.capture2-p1-findings.md` 的 P2 追加节。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/camera2/SegmentRecorder.kt app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt
git commit -m "feat(capture2): SegmentRecorder — HEVC/AVC MediaCodec + MediaMuxer per segment (device-verified)"
```

---

### Task 4: GL 渲染器 `GlRecordPipeline`(相机 OES 纹理 → 多目标 Surface)— 真机 probe

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/camera2/GlRecordPipeline.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt`(加临时 `probeGlRecord()`)

**Interfaces:**
- Consumes: `android.view.Surface`。
- Produces:
  - `class GlRecordPipeline`
  - `fun start(cameraW: Int, cameraH: Int, onCameraTextureReady: (SurfaceTexture) -> Unit)`(建 EGL 上下文 + OES 纹理 + 相机 `SurfaceTexture`(缓冲设 cameraW×cameraH),回调把该 SurfaceTexture 交给调用方去喂相机)
  - `fun addTarget(surface: Surface)` / `fun removeTarget(surface: Surface)`(线程安全增删 EGL window 目标——编码器面 + 预览面)
  - `fun release()`

> GL 上下文线程亲和:内部起一条 GL 线程 + Handler;相机 `SurfaceTexture.OnFrameAvailableListener` 里 post 到 GL 线程 → `updateTexImage` + 对每个目标 `makeCurrent`→draw→`swapBuffers`。目标增删也 post 到 GL 线程串行执行(避免并发建/毁 EGL surface)。

- [ ] **Step 1: 实现 `GlRecordPipeline.kt`**(EGL14 + GLES20 外部纹理透传;标准 Grafika 模式)

```kotlin
package com.benzn.grandtime.capture.camera2

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 相机外部 OES 纹理 → GL 透传绘制到 N 个目标 Surface(编码器输入面 + 可选预览面)。
 * 目标增删/绘制全在自有 GL 线程串行执行,故预览挂/摘不动相机会话、不中断编码器喂帧。
 */
class GlRecordPipeline {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTexId = 0
    private var program = 0
    private var uTexMatrix = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var cameraTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)
    private val targets = LinkedHashMap<Surface, EGLSurface>()

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val texQuad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    fun start(cameraW: Int, cameraH: Int, onCameraTextureReady: (SurfaceTexture) -> Unit) {
        thread = HandlerThread("gl-record").apply { start() }
        handler = Handler(thread.looper)
        handler.post {
            initEgl()
            oesTexId = createOesTexture()
            program = buildProgram()
            val st = SurfaceTexture(oesTexId).apply {
                setDefaultBufferSize(cameraW, cameraH)
                setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
            }
            cameraTexture = st
            onCameraTextureReady(st)
        }
    }

    fun addTarget(surface: Surface) = handler.post {
        if (targets.containsKey(surface)) return@post
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val win = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        targets[surface] = win
    }

    fun removeTarget(surface: Surface) = handler.post {
        targets.remove(surface)?.let { EGL14.eglDestroySurface(eglDisplay, it) }
    }

    private fun drawFrame() {
        val st = cameraTexture ?: return
        runCatching { st.updateTexImage() }
        st.getTransformMatrix(stMatrix)
        for ((_, win) in targets) {
            if (win == EGL14.EGL_NO_SURFACE) continue
            EGL14.eglMakeCurrent(eglDisplay, win, win, eglContext)
            val w = EGL14.eglQuerySurface(eglDisplay, win, EGL14.EGL_WIDTH, IntArray(1).also { it[0] = 0 }.let { intArrayOf(0) }, 0)
            val ww = IntArray(1); EGL14.eglQuerySurface(eglDisplay, win, EGL14.EGL_WIDTH, ww, 0)
            val hh = IntArray(1); EGL14.eglQuerySurface(eglDisplay, win, EGL14.EGL_HEIGHT, hh, 0)
            GLES20.glViewport(0, 0, ww[0], hh[0])
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texQuad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
            EGL14.eglSwapBuffers(eglDisplay, win)
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val cfgAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, cfgs, 0, 1, num, 0)
        eglConfig = cfgs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec4 aPosition; attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix; varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = (uTexMatrix * aTexCoord).xy; }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        aPosition = GLES20.glGetAttribLocation(p, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(p, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(p, "uTexMatrix")
        return p
    }

    fun release() {
        handler.post {
            for ((_, win) in targets) EGL14.eglDestroySurface(eglDisplay, win)
            targets.clear()
            cameraTexture?.release(); cameraTexture = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY   // 幂等:二次 release 时上面 if 为假,不重复销毁
            eglContext = EGL14.EGL_NO_CONTEXT
            pbuffer = EGL14.EGL_NO_SURFACE
            thread.quitSafely()
        }
    }
}
```

> 实现者注:`drawFrame()` 里查询 window 宽高的 `IntArray` 调用请精简为单次 `eglQuerySurface(EGL_WIDTH)`/`(EGL_HEIGHT)`(上面示例含一处冗余行,实现时删掉那行 `val w = ...` 只留 `ww`/`hh` 两次查询)。透传绘制铺满目标 window;真正的 letterbox 由预览控件尺寸(T8 按宽高比设 SurfaceView 尺寸)保证,编码器 window 尺寸=编码尺寸故无形变。

- [ ] **Step 2: 加临时 probe `probeGlRecord()`**——相机 → GL SurfaceTexture → GL 画到 SegmentRecorder 编码器面 → 3s → mp4(测 GL+编码器+相机全链路):

```kotlin
    /** Task4 验证:相机 → GL(OES)→ SegmentRecorder 编码器面 → mp4。测 GL 透传+编码。 */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun probeGlRecord(): String {
        val dir = java.io.File("/sdcard/FieldSight/_probe").apply { mkdirs() }
        val out = java.io.File(dir, "probe_gl.mp4")
        val rec = com.benzn.grandtime.capture.camera2.SegmentRecorder { log(it) }
        val spec = com.benzn.grandtime.capture.camera2.VideoSpec(1440, 1080, 20_000_000, 90)
        val encSurface = rec.prepare(out, spec, hevcPreferred = true, location = null)
        val gl = com.benzn.grandtime.capture.camera2.GlRecordPipeline()
        val ht = android.os.HandlerThread("glp").apply { start() }
        val handler = android.os.Handler(ht.looper)
        var camera: android.hardware.camera2.CameraDevice? = null
        var session: android.hardware.camera2.CameraCaptureSession? = null
        try {
            val stDeferred = kotlinx.coroutines.CompletableDeferred<android.graphics.SurfaceTexture>()
            gl.start(1440, 1080) { stDeferred.complete(it) }
            val camTex = stDeferred.await()
            gl.addTarget(encSurface)
            rec.start()
            val camInput = android.view.Surface(camTex)
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.first {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val cam: android.hardware.camera2.CameraDevice = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cm.openCamera(backId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(c: android.hardware.camera2.CameraDevice) { cont.resume(c) {} }
                    override fun onDisconnected(c: android.hardware.camera2.CameraDevice) { c.close() }
                    override fun onError(c: android.hardware.camera2.CameraDevice, e: Int) { c.close(); if (cont.isActive) cont.cancel(RuntimeException("open err $e")) }
                }, handler)
            }
            camera = cam
            val sess: android.hardware.camera2.CameraCaptureSession = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOf(camInput), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) { cont.resume(s) {} }
                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) { if (cont.isActive) cont.cancel(RuntimeException("cfg fail")) }
                }, handler)
            }
            session = sess
            val req = cam.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD).apply { addTarget(camInput) }.build()
            sess.setRepeatingRequest(req, null, handler)
            kotlinx.coroutines.delay(3000)
            sess.stopRepeating()
            gl.removeTarget(encSurface)
            rec.stop()
            gl.release()
            return "OK codec=${rec.actualCodec} size=${out.length()} path=${out.absolutePath}"
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            ht.quitSafely()
        }
    }
```
`runAll()` 里加 `log("probeGlRecord => ${runCatching { probeGlRecord() }.getOrElse { "抛异常: ${it.message}" }}")`。

- [ ] **Step 3: 构建** `./gradlew assembleDebug`。

- [ ] **Step 4: 真机验证**——`probe_gl.mp4` 拉回**可播、画面正常(GL 透传无花屏/错色)**、编码=hevc。**GL 链路出可播 mp4 = 绿灯**(GL 是本重写最难的一环,这步过了 P2 基本稳)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/camera2/GlRecordPipeline.kt app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt
git commit -m "feat(capture2): GlRecordPipeline — camera OES texture -> multi-target GL draw (device-verified)"
```

---

### Task 5: 相机管线门面 `Camera2Pipeline`(会话+GL+编码+拍照+手电)— 真机 probe

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/camera2/Camera2Pipeline.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt`(加 `probePipeline()`)

**Interfaces:**
- Consumes: `VideoSpec`/`VideoSizeSelector`(T2)、`SegmentRecorder`(T3)、`GlRecordPipeline`(T4)、`AspectRatio`/`VideoQuality`。
- Produces(`CaptureManager` 唯一相机入口):
  - `class Camera2Pipeline(context: Context, probe: (String) -> Unit)`
  - `val isRecording: Boolean`
  - `data class SegmentResult(val codec: String, val resolution: String)`
  - `suspend fun startSegment(file: File, aspect: AspectRatio, quality: VideoQuality, hevcPreferred: Boolean, location: Pair<Float, Float>?, onFinalized: (error: Boolean, message: String?) -> Unit): SegmentResult?` — 首段时开相机 + 建会话([glCameraSurface, jpegReader]),启 GL + SegmentRecorder;**同步返回** `SegmentResult`(codec/resolution,`prepare` 后即知,供 DB 立即入行),失败返回 `null`;`onFinalized`(error/message)稍后异步触发。
  - `fun stopSegment()` — GL 移除编码器目标 → SegmentRecorder.stop → 触发 onFinalized(异步)。
  - `suspend fun prepareForPhoto(aspect: AspectRatio, quality: VideoQuality)` — Idle/录音态拍照前:无会话则开相机+建会话([glCameraSurface, jpegReader]),等 ~400ms 让 AE/AF 收敛;已有会话(录像中)则 no-op。
  - `suspend fun takePhoto(file: File, jpegQuality: Int): Boolean` — 对 jpegReader 发 STILL_CAPTURE,落 JPEG;**录像中/空闲均可,满 5MP**。
  - `fun setPreviewSurface(surface: Surface?)` — GL addTarget/removeTarget 预览面(不动相机会话)。
  - `fun setTorch(on: Boolean)` — 会话活时改 repeating 请求 FLASH_MODE_TORCH;否则交给调用方走 CameraManager(见 T7)。
  - `suspend fun release()` — 关会话/相机/GL/jpegReader。

- [ ] **Step 1: 实现 `Camera2Pipeline.kt`**

```kotlin
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
import kotlinx.coroutines.suspendCancellableCoroutine

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

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var gl: GlRecordPipeline? = null
    private var cameraSurface: Surface? = null
    private var segment: SegmentRecorder? = null
    private var previewSurface: Surface? = null
    private var sensorOrientation = 90
    private var torchOn = false

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

    @SuppressLint("MissingPermission")
    private suspend fun openCameraIfNeeded(): CameraDevice {
        camera?.let { return it }
        val id = backId()
        sensorOrientation = cm().getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val cam: CameraDevice = suspendCancellableCoroutine { cont ->
            cm().openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { cont.resume(c) }
                override fun onDisconnected(c: CameraDevice) {
                    c.close(); if (camera == c) camera = null
                    runCatching { session?.close() }; session = null; probe("camera onDisconnected")
                }
                override fun onError(c: CameraDevice, e: Int) {
                    c.close(); if (camera == c) camera = null
                    runCatching { session?.close() }; session = null; probe("camera onError $e")
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
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(camSurface)
            set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }.build()
        runCatching { s.setRepeatingRequest(req, null, handler) }
    }

    suspend fun startSegment(
        file: File,
        aspect: AspectRatio,
        quality: VideoQuality,
        hevcPreferred: Boolean,
        location: Pair<Float, Float>?,
        onFinalized: (error: Boolean, message: String?) -> Unit,
    ): SegmentResult? {
        return try {
            val size = VideoSizeSelector.pickSize(aspect, quality, supportedVideoSizes(backId()))
            val spec = VideoSpec(size.width, size.height, VideoSizeSelector.bitRateFor(quality), sensorOrientation)
            ensureSession(spec)
            val rec = SegmentRecorder(probe)
            val encSurface = rec.prepare(file, spec, hevcPreferred, location)
            gl!!.addTarget(encSurface)
            rec.start()
            segment = rec
            onFinalizedCb = onFinalized
            currentEncSurface = encSurface
            SegmentResult(rec.actualCodec, "${size.width}x${size.height}")
        } catch (e: Exception) {
            probe("startSegment 失败: ${e.message}")
            null
        }
    }

    private var onFinalizedCb: ((Boolean, String?) -> Unit)? = null
    private var currentEncSurface: Surface? = null

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
        ensureSession(VideoSpec(size.width, size.height, VideoSizeSelector.bitRateFor(quality), sensorOrientation))
        kotlinx.coroutines.delay(400) // 让 AE/AF 收敛,避免首帧过暗/失焦
    }

    /** 拍照:对 jpegReader 发 STILL_CAPTURE(录像中也可,满 5MP)。 */
    suspend fun takePhoto(file: File, jpegQuality: Int): Boolean {
        val cam = camera ?: return false
        val s = session ?: return false
        val reader = jpegReader ?: return false
        return suspendCancellableCoroutine { cont ->
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
                reader.setOnImageAvailableListener(null, handler)
                if (cont.isActive) cont.resume(ok)
            }, handler)
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }.build()
            runCatching { s.capture(req, null, handler) }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    }

    /** 预览面挂/摘:只切 GL 目标,不动相机会话(录像不中断)。 */
    fun setPreviewSurface(surface: Surface?) {
        val glp = gl
        val old = previewSurface
        previewSurface = surface
        if (glp == null) return
        if (old != null && old != surface) glp.removeTarget(old)
        if (surface != null) glp.addTarget(surface)
    }

    /** 会话活时经 repeating 请求切手电;返回是否已处理(false=调用方走 CameraManager)。 */
    fun setTorch(on: Boolean): Boolean {
        torchOn = on
        return if (session != null) { applyRepeating(); true } else false
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
    }
}
```

> 设计说明:会话固定 2 路(GL 相机面 + JPEG reader),**永不为预览/编码重配会话**——编码器面是 GL 的绘制目标(非相机输出),预览面同理,故挂/摘/滚段都不动会话(P1 已证 3 路都行,这里相机侧只 2 路更稳)。段与段之间会话/GL/jpegReader 复用;`release()` 才全拆(Idle 或失败时 CaptureManager 调)。手电录像态走 repeating 的 FLASH_MODE_TORCH。

- [ ] **Step 2: 加 `probePipeline()`**——启一段 → 1s 后录像中拍照(满 5MP)→ 挂一个 dummy 预览(SurfaceTexture 假面)→ 再 1s → stopSegment → release。验:mp4 可播 + jpg 满 5MP + 无崩:

```kotlin
    suspend fun probePipeline(): String {
        val dir = java.io.File("/sdcard/FieldSight/_probe").apply { mkdirs() }
        val vid = java.io.File(dir, "probe_pipe.mp4")
        val pic = java.io.File(dir, "probe_pipe.jpg")
        val pipe = com.benzn.grandtime.capture.camera2.Camera2Pipeline(context) { log(it) }
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val res = pipe.startSegment(vid, com.benzn.grandtime.core.AspectRatio.RATIO_4_3, com.benzn.grandtime.core.VideoQuality.P1080, true, -36.85f to 174.76f) { _, _ -> done.complete(Unit) }
                ?: return "startSegment 失败"
            kotlinx.coroutines.delay(1000)
            val photoOk = pipe.takePhoto(pic, 95)
            val dummyTex = android.graphics.SurfaceTexture(0).apply { setDefaultBufferSize(1440, 1080) }
            pipe.setPreviewSurface(android.view.Surface(dummyTex))
            kotlinx.coroutines.delay(1200)
            pipe.stopSegment()
            done.await()
            pipe.release()
            return "OK codec=${res.codec} res=${res.resolution} vid=${vid.length()} photo=${if (photoOk) pic.length() else -1}"
        } finally {
            runCatching { pipe.release() }
        }
    }
```
`runAll()` 加 `log("probePipeline => ${runCatching { probePipeline() }.getOrElse { "抛异常: ${it.message}" }}")`。

- [ ] **Step 3: 构建** `./gradlew assembleDebug`。

- [ ] **Step 4: 真机验证**——`probe_pipe.mp4` 可播(录像中挂预览未中断)+ `probe_pipe.jpg` 拉回是**满 5MP(2592×1944)**且录像期间拍成 + logcat `OK codec=hevc res=1440x1080 photo=>0`。**全绿 = Part A 完成,管线核心真机验证通过**;记进 findings。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/camera2/Camera2Pipeline.kt app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt
git commit -m "feat(capture2): Camera2Pipeline facade — session+GL+encode+photo+torch (device-verified)"
```

---

# Part B — 接入生产 + 替换 CameraX + 真机对等验收

### Task 6: 重写 `CaptureManager` + `TorchController` 用 `Camera2Pipeline`;删 frame-grab

**Files:**
- Modify(整体重写相机调用): `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`
- Modify(改为 Camera2): `app/src/main/java/com/benzn/grandtime/capture/TorchController.kt`
- Delete: `app/src/test/java/com/benzn/grandtime/capture/FrameOffsetTest.kt`(随 `frameOffsetMicros` 一起删)

**Interfaces:**
- Consumes: `Camera2Pipeline`(T5,含 `startSegment`/`stopSegment`/`takePhoto`/`prepareForPhoto`/`setPreviewSurface`/`setTorch`/`isRecording`/`release`)、`AspectRatio`(T1)。
- Produces: `CaptureManager` 构造签名**不变**;`TorchController(context, pipeline: Camera2Pipeline)`。
- 前提:本任务后 `CameraSession`/`VideoRecorder`/`PhotoTaker`/`ServiceLifecycleOwner`/`frameOffsetMicros` 不再被引用(T9 删除)。`AppState.previewSurface` 本任务仍是 `Preview.SurfaceProvider?`——**T7 改类型**;为让 T6 独立编译,本任务的预览收集器**先注释/占位**,T7 接上真实 `Surface`(见下 Step 3 备注)。

> **重点**:P1 证明 Camera2 全分辨率录像中拍照可行,故**彻底删除** frame-grab 子系统:`PendingFrameGrab`、`pendingFrameGrab`、`initiateFrameGrab`、`performFrameGrab`、`frameOffsetMicros` 及其 import(`Bitmap` 仅保留给照片精度降采样,见下)。`takePhoto` 一律走 `pipeline.takePhoto`(任意状态、满分辨率)。

- [ ] **Step 1: 改 `TorchController.kt`**——空闲走 `CameraManager.setTorchMode`,录像态(管线会话活)走管线:

```kotlin
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
```

- [ ] **Step 2: 重写 `CaptureManager.kt`**——完整替换为(相机调用换管线,删 frame-grab,codec/resolution 取自管线;照片精度降采样保留):

```kotlin
package com.benzn.grandtime.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.benzn.grandtime.capture.camera2.Camera2Pipeline
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.PhotoQuality
import com.benzn.grandtime.core.PhotoResolution
import com.benzn.grandtime.core.SettingsStore
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

    private var segmentTimer: Job? = null
    private var screenOffTimer: Job? = null
    private var pendingRoll = false
    private var currentVideoRecordId: String? = null
    private var currentVideoFile: File? = null
    private var currentVideoStartedAt: Long = 0
    private var currentAudioRecordId: String? = null
    private var currentAudioFile: File? = null

    init {
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
        val result = pipeline.startSegment(
            file = file,
            aspect = settings.aspectRatio,
            quality = settings.videoQuality,
            hevcPreferred = true,
            location = null, // GPS 起点写入留 P3
        ) { error, message ->
            scope.launch {
                val finalizedId = finalizeVideoDbRow()
                if (error) {
                    stopScreenOffTimer()
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
                        pipeline.release()
                    }
                }
            }
        }
        if (result == null) { execute(core.onFailure("Camera unavailable")); return false }
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
        val startedAt = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()
        val ok = pipeline.takePhoto(file, jpegQuality(settings.photoQuality))
        if (ok) {
            // 照片精度设置:满 4:3 JPEG 落盘后按目标像素降采样(MAX=不降)。
            settings.photoResolution.targetPixels()?.let { downscaleJpeg(file, it, jpegQuality(settings.photoQuality)) }
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
        }
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
```

- [ ] **Step 3: 删 `FrameOffsetTest.kt`**

```bash
git rm app/src/test/java/com/benzn/grandtime/capture/FrameOffsetTest.kt
```

> 备注(预览收集器):上面 `init` 里 `combine(AppState.previewSurface, ...)` 的 `sp` 现类型是 `Preview.SurfaceProvider?`,而 `pipeline.setPreviewSurface` 要 `Surface?`——**T6 结束时此处会类型不符**。为让 T6 可独立编译验证,本任务先把该 collect 体写成 `pipeline.setPreviewSurface(null)`(占位,录像中不挂预览也不崩,只是没画面),并在 T7 把 `AppState.previewSurface` 改为 `Surface?` 后恢复为 `pipeline.setPreviewSurface(sp)`。**实现者:T6 里 collect 体先用 `{ _ -> pipeline.setPreviewSurface(null) }`;T7 改回 `{ sp -> pipeline.setPreviewSurface(sp) }`。**

- [ ] **Step 4: 构建 + 既有单测**

Run: `./gradlew testDebugUnitTest --tests "com.benzn.grandtime.capture.CaptureCoreTest"` → PASS(13 测,契约未变)。
Run: `./gradlew assembleDebug` → BUILD SUCCESSFUL(`CameraSession`/`VideoRecorder`/`PhotoTaker` 变为无引用但仍在,T9 删)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt app/src/main/java/com/benzn/grandtime/capture/TorchController.kt
git rm app/src/test/java/com/benzn/grandtime/capture/FrameOffsetTest.kt
git commit -m "refactor(capture): CaptureManager+TorchController use Camera2Pipeline; drop frame-grab"
```

---

### Task 7: `AppState.previewSurface: Surface?` + `RecordingScreen` SurfaceView 预览

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/core/AppState.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/ui/RecordingScreen.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`(恢复 T6 占位的预览 collect)

**Interfaces:**
- Consumes: `pipeline.setPreviewSurface(Surface?)`(T5/T6)。
- Produces: `AppState.previewSurface: MutableStateFlow<android.view.Surface?>`。

- [ ] **Step 1: 改 `AppState.kt`**——把
```kotlin
    val previewSurface = MutableStateFlow<androidx.camera.core.Preview.SurfaceProvider?>(null)
```
改为
```kotlin
    val previewSurface = MutableStateFlow<android.view.Surface?>(null)
```

- [ ] **Step 2: 恢复 `CaptureManager` 预览 collect**——把 T6 占位的 `{ _ -> pipeline.setPreviewSurface(null) }` 改回:
```kotlin
            }.distinctUntilChanged().collect { sp -> pipeline.setPreviewSurface(sp) }
```

- [ ] **Step 3: 改 `RecordingScreen.kt`**——用 `SurfaceView` 取代 CameraX `PreviewView`,按宽高比手动 letterbox,`SurfaceHolder` 回调写 `AppState.previewSurface`。替换文件顶部 `import androidx.camera.view.PreviewView` 及预览块:

删 `import androidx.camera.view.PreviewView`;加:
```kotlin
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.benzn.grandtime.core.AspectRatio
```
把 `previewView`/`DisposableEffect` 块替换为(`SurfaceHolder.Callback` 在 surface 建/毁时写/清 `AppState.previewSurface`;letterbox 用 `aspectRatio` 设 `SurfaceView` 尺寸,外层 `Box` 居中):
```kotlin
    val activity = remember(context) { context as? android.app.Activity }
    val surfaceView = remember {
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { AppState.previewSurface.value = h.surface }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { AppState.previewSurface.value = h.surface }
                override fun surfaceDestroyed(h: SurfaceHolder) { AppState.previewSurface.value = null }
            })
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            AppState.previewSurface.value = null
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
```
把承载预览的 `AndroidView(factory = { previewView }, ...)` 改为 `AndroidView(factory = { surfaceView }, ...)`,并把它包在按当前录制宽高比 letterbox 的容器里(读设置得到比例;用 `BoxWithConstraints` 计算 fit 尺寸):
```kotlin
    val settingsStore = remember { SettingsStore(context.settingsDataStore) }
    val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = RecordingSettings())
    val ratio = if (settings.aspectRatio == AspectRatio.RATIO_16_9) 16f / 9f else 4f / 3f
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 竖屏容器里按录制宽高比 FIT_CENTER(等价旧 PreviewView.FIT_CENTER,上下留黑)
        val cw = maxWidth
        val ch = maxHeight
        val fitH = minOf(ch, cw / ratio)
        val fitW = fitH * ratio
        AndroidView(factory = { surfaceView }, modifier = Modifier.width(fitW).height(fitH))
    }
```
> 实现者:用文件里既有的 import(`androidx.compose.foundation.layout.*` 已含 `BoxWithConstraints`/`width`/`height`;`SettingsStore`/`settingsDataStore`/`RecordingSettings` 从 `com.benzn.grandtime.core` 导入;`collectAsStateWithLifecycle` 已导入)。保留既有 `LaunchedEffect(screenOff)` 熄屏逻辑与录制按钮/其它 UI 不变——仅替换预览控件与其容器。

- [ ] **Step 4: 构建**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL,且 `app/src/main` 内 `grep -r androidx.camera` 只剩 `CameraSession/VideoRecorder/PhotoTaker`(T9 删)——`AppState`/`RecordingScreen` 已无 CameraX。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/core/AppState.kt app/src/main/java/com/benzn/grandtime/ui/RecordingScreen.kt app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt
git commit -m "feat(ui): Camera2 preview via SurfaceView + aspect-ratio letterbox; AppState.previewSurface = Surface"
```

---

### Task 8: SettingsScreen 加"录制宽高比"选项行

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: `AspectRatio`(T1)、`SettingsStore.setAspectRatio`(T1)。

- [ ] **Step 1: 加宽高比选项行**——在 `SettingsScreen.kt` 的 RECORDING 区(现有 Video quality/Segment length/Photo quality/Photo resolution 行的同款控件)加一行 "Aspect ratio",值 `settings.aspectRatio.label`(4:3 / 16:9),点开在 `AspectRatio.entries` 间循环/弹选,回调 `scope.launch { settingsStore.setAspectRatio(next) }`。**沿用该文件里既有设置行的完全相同组件与交互模式**(如既有 Video quality 行怎么写就怎么写,仅换数据源为 `AspectRatio`)。

> 实现者:打开 `SettingsScreen.kt`,定位既有 `Photo resolution` 那一行的实现(它在 `PhotoResolution.entries` 间切换),照抄一份改成 `AspectRatio`(label "Aspect ratio",选项 `AspectRatio.entries`,setter `setAspectRatio`)。放在 RECORDING 区内(Video quality 之后即可)。

- [ ] **Step 2: 构建 + 手动核对**

Run: `./gradlew assembleDebug` → SUCCESSFUL。
装机进 Settings → RECORDING,确认多出 "Aspect ratio" 行、点击能在 4:3/16:9 间切换并持久化(杀进程重进仍是所选)。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/ui/SettingsScreen.kt
git commit -m "feat(settings-ui): add Aspect ratio (4:3 / 16:9) option row"
```

---

### Task 9: 移除 CameraX + 删旧相机层 + 清理探针

**Files:**
- Delete: `app/src/main/java/com/benzn/grandtime/capture/CameraSession.kt`、`capture/VideoRecorder.kt`、`capture/PhotoTaker.kt`、`capture/ServiceLifecycleOwner.kt`
- Delete: `app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt`(临时探针,连同 Diagnostics 临时按钮)
- Modify: `app/src/main/java/com/benzn/grandtime/ui/DiagnosticsScreen.kt`(去掉 "Run Capture2 probe" 按钮及相关 import/scope)
- Modify: `app/build.gradle.kts`(删 5 个 camera 依赖行)
- Modify: `gradle/libs.versions.toml`(删 5 个 camera library 定义 + `camerax` version;若无其它引用)

**Interfaces:** 无新增。前提:T6/T7 后这些文件已无引用。

- [ ] **Step 1: 删旧相机层与探针**

```bash
git rm app/src/main/java/com/benzn/grandtime/capture/CameraSession.kt \
       app/src/main/java/com/benzn/grandtime/capture/VideoRecorder.kt \
       app/src/main/java/com/benzn/grandtime/capture/PhotoTaker.kt \
       app/src/main/java/com/benzn/grandtime/capture/ServiceLifecycleOwner.kt \
       app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt
```
> 若 `ServiceLifecycleOwner` 仍被别处引用(grep 确认),则保留;经勘察它只服务 CameraX 绑定,应可删。

- [ ] **Step 2: 去 Diagnostics 临时按钮**——在 `DiagnosticsScreen.kt` 删掉 `OutlinedButton(... "Run Capture2 probe (debug)" ...)` 整块 + 不再需要的 `context`/`scope`/`import kotlinx.coroutines.launch`(若仅探针用)。其余 Diagnostics(事件日志/按键行)不动。

- [ ] **Step 3: 删 CameraX 依赖**——`app/build.gradle.kts` 删这 5 行:
```kotlin
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.camera.view)
```
`gradle/libs.versions.toml` 删 `camera-core/camera-camera2/camera-lifecycle/camera-video/camera-view` 5 个 `[libraries]` 定义与 `camerax = "1.4.1"`(确认无其它引用)。

- [ ] **Step 4: 全量构建 + 单测 + 依赖核对**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL;全部单测 PASS(`CaptureCoreTest` 等)。
核对:`grep -r "androidx.camera" app/src` **零命中**(CameraX 彻底移除)。

- [ ] **Step 5: Commit**

```bash
git add -A app/src app/build.gradle.kts gradle/libs.versions.toml
git commit -m "chore(capture): remove CameraX (deps + CameraSession/VideoRecorder/PhotoTaker) + probe scaffolding"
```

---

### Task 10: 真机对等验收 + tag

**Files:** 无代码(验收);更新 `docs/superpowers/plans/.capture2-p1-findings.md` 追加 P2 验收结果。

**Interfaces:** 无。

- [ ] **Step 1: 真机装机全量验收**(控制器执行,对照 SP3c 九项 + 新增项;失败→回对应任务修):
  1. **物理键录像**:短按录像键 → 录制起(红灯闪)、Home 卡显示录制态、系统提示音;再按停 → 待机(蓝灯)。
  2. **分段滚动**:设 Segment=1min,录 >1min → 生成多段 mp4,段间无丢(Files 里连续)。
  3. **HEVC 可播**:拉回录制 mp4 → 编码=hevc、**能播**;上传后网页端(VAD 转 H.264 预览)可播。
  4. **4:3 / 16:9 切换**:Settings 切 4:3 → 录出 1440×1080;切 16:9 → 1920×1080;预览按比例 letterbox 正确(所见即所录)。
  5. **全分辨率录像中拍照**(新解锁):1080P 录像中按拍照键 → 落一张**满 5MP**照片、录像不断、快门音 + `lastPhotoFlash` 闪现;Files 出现该照片带上传角标。
  6. **预览挂/摘不中断录像**:录像中进/出 RecordingScreen(挂/摘预览)→ 录像持续、mp4 无损。
  7. **息屏后台录制**:录像中触发熄屏(或等 screen-off 定时)→ 屏灭但录制继续(进程可见 + FGS),亮屏后预览恢复。
  8. **手电**:空闲切手电亮/灭;录像中切手电亮/灭(经 repeating 请求),录像不断。
  9. **录音**:录音键起停 → m4a 落盘(黄灯闪);录音中拍照可用(满 5MP)。
  10. **上传打 siteId**:选工地后录制/拍照 → 落 fieldsight-test 桶 + recordings 表带 siteId;Files 角标转已传。
  11. **SP3b 灯语**:录像红 / 录音黄 / 待机蓝,与状态一致。
  12. **照片精度**:Settings 切 High(3MP)/Standard(1MP)→ 拍照文件像素随之降;Max=满 5MP。
- [ ] **Step 2: 记录验收结果**——把逐项结果写进 `.capture2-p1-findings.md` 的 "P2 验收" 节(过/修了什么)。
- [ ] **Step 3: Commit + tag**

```bash
git add docs/superpowers/plans/.capture2-p1-findings.md
git commit -m "docs: SP-Capture2-P2 device acceptance results"
git tag sp-capture2-p2-accepted
```

---

## Self-Review(写完计划的自查,已执行)

**1. Spec 覆盖**:HEVC 默认+降级(T3/T5/T6)、4:3/16:9 设置(T1/T8)+ 编码尺寸(T2/T5)、全分辨率录像中拍照(T5/T6,删 frame-grab)、setLocation GPS(T3 支持;P2 传 null,GPS 采集留 P3——spec §6 P3 = 水印+GPS,一致)、预览挂摘不中断(GL 目标切换 T4/T5/T6/T7)、息屏后台(T7 保留 FLAG_KEEP_SCREEN_ON 逻辑 + FGS 不变)、SP3c 全功能(T10 逐项验收)。水印(P3)不在本计划,符合 spec 分期。
**2. Placeholder 扫描**:无 TBD/TODO;device 任务以真机 probe 验证代替 JVM 单测(已在计划头声明,诚实)。
**3. 类型一致**:`Camera2Pipeline.startSegment(...): SegmentResult?` + `onFinalized(error,message)`(T5/T6 一致);`setPreviewSurface(Surface?)`(T5/T6/T7 一致);`AppState.previewSurface: Surface?`(T7);`TorchController(context, pipeline)`(T6)。`VideoSizeSelector.pickSize/bitRateFor`、`VideoSpec(width,height,bitRate,orientationHint)`(T2/T3/T5 一致)。
**已知 scope 决策**(非遗漏,显式记录):①GPS/水印留 P3;②照片精度 HIGH/STD 用落盘后降采样实现(T6 `downscaleJpeg`),MAX=满 5MP;③预览 letterbox 手动实现取代 CameraX FIT_CENTER(T7)。
