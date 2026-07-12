# SP3c 采集可见性与录制 UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 录制文件写公共 `/sdcard/FieldSight/device/`(安卓 Files/相册可见)、前台录像全屏预览、缩略图网格、系统提示音、录制中自动熄屏保电、H.265 编码、照片精度设置。

**Architecture:** MediaStorage 根改公共目录(SD 优先)+ MANAGE_EXTERNAL_STORAGE;CameraSession 增可选 Preview 用例(前台 attach/后台 detach,不动 VideoCapture 绑定);RecordingScreen 由 captureState 驱动导航;CaptureManager 增提示音/媒体扫描/迁移/熄屏计时。纯逻辑(存储/迁移/设置/帧偏移)JVM 单测,相机/UI 真机验收。

**Tech Stack:** 既有栈 + Coil 2.7(coil-compose + coil-video)+ MediaActionSound/MediaScannerConnection(框架内置,无依赖)。

Spec(唯一事实来源):`docs/superpowers/specs/2026-07-12-sp3c-capture-ux-design.md`(v2)

## Global Constraints

- 工作分支:`feature/sp3c-capture-ux`(自 main 切出;控制器创建)
- 北极星:省电、续航、最大录制时长——技术选择向此靠(H.265、按需预览、熄屏)
- 存储根 = 可移动 SD 卷(若存在)否则 `/sdcard`(Environment.getExternalStorageDirectory);
  下 `FieldSight/device/{video,audio,photo}`;命名 `VID_/AUD_/IMG__yyyyMMdd_HHmmss.<ext>`
  (SP2 登录后 device→用户层、前缀换用户名——**本期不做**)
- 写文件前 `Environment.isExternalStorageManager()` 必须为真(MANAGE_EXTERNAL_STORAGE);否则拒绝+提示
- 视频编码目标 H.265/HEVC(Task 11 验证/尽力;CameraX 1.4 若不可强制则记录实况并报控制器)
- 音频 AAC/M4A 128kbps;照片 JPEG;photo_resolution = MAX(满传感器)/HIGH/STD
- 提示音 MediaActionSound(START/STOP_VIDEO_RECORDING、SHUTTER_CLICK)
- 自动熄屏:录像态起 screen_off_minutes(默认 3;1/3/5/Never)后释放屏幕常亮,录像后台续;唤醒回预览
- 逻辑层 hardware/keymap/auth/boot + CaptureCore 状态机不动
- gradle 前缀:`export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`;真机 `-s F2S202503103054` + `export ANDROID_SERIAL=F2S202503103054`(模拟器不可用);adb 路径 `C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe`
- Dropbox 文件锁:重跑一次;持续 → BLOCKED
- 每 Task 一 commit,footer:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014TCKsgA4JjaWbbFD4zY7zh
```

---

### Task 1: 依赖 + MANAGE 权限(构建门)

**Files:**
- Modify: `gradle/libs.versions.toml`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`

- [ ] **Step 1: `gradle/libs.versions.toml` 增补**

`[versions]` 加 `coil = "2.7.0"`。`[libraries]` 加:

```toml
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
coil-video = { group = "io.coil-kt", name = "coil-video", version.ref = "coil" }
```

- [ ] **Step 2: `app/build.gradle.kts` dependencies 加**

```kotlin
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
```

- [ ] **Step 3: Manifest `<manifest>` 根下加**

```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

- [ ] **Step 4: 构建门**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && cd "C:/Users/camil/Dropbox/GrandTime" && ./gradlew assembleDebug && ./gradlew test
```

Expected: 双 BUILD SUCCESSFUL。

- [ ] **Step 5: Commit** — `git add -A gradle app && git commit -m "build: Coil thumbnails deps and MANAGE_EXTERNAL_STORAGE permission"`

---

### Task 2: SettingsStore 增 photo_resolution + screen_off_minutes(TDD)

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/core/SettingsStore.kt`
- Modify: `app/src/test/java/com/benzn/grandtime/core/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `enum PhotoResolution(label){MAX("Max (5MP)"),HIGH("High (3MP)"),STD("Standard (1MP)")}`;
  `RecordingSettings` 增 `photoResolution=MAX`、`screenOffMinutes=3`;
  `SettingsStore`:`SCREEN_OFF_OPTIONS=listOf(1,3,5,0)`(0=Never)、`setPhotoResolution`、`setScreenOffMinutes`;
  keys `"photo_resolution"`、`"screen_off_minutes"`;非法值回落默认。

- [ ] **Step 1: 追加失败测试到 `SettingsStoreTest.kt`**

```kotlin
    @Test
    fun `photo resolution and screen off default and roundtrip`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertEquals(PhotoResolution.MAX, store.settings.first().photoResolution)
        assertEquals(3, store.settings.first().screenOffMinutes)
        store.setPhotoResolution(PhotoResolution.STD)
        store.setScreenOffMinutes(0)
        assertEquals(PhotoResolution.STD, store.settings.first().photoResolution)
        assertEquals(0, store.settings.first().screenOffMinutes)
    }

    @Test
    fun `invalid photo resolution and screen off fall back`() = runTest(UnconfinedTestDispatcher()) {
        val (store, ds) = newStore()
        ds.edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("photo_resolution")] = "BOGUS"
            it[androidx.datastore.preferences.core.intPreferencesKey("screen_off_minutes")] = 42
        }
        assertEquals(PhotoResolution.MAX, store.settings.first().photoResolution)
        assertEquals(3, store.settings.first().screenOffMinutes)
    }

    @Test
    fun `setScreenOffMinutes rejects values outside options`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setScreenOffMinutes(2) }
        }
    }
```

(若测试文件缺 `runBlocking`/`assertThrows` import,补 `import kotlinx.coroutines.runBlocking`、`import org.junit.Assert.assertThrows`。)

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 改 `SettingsStore.kt`**

`PhotoQuality` 下加:

```kotlin
enum class PhotoResolution(val label: String) { MAX("Max (5MP)"), HIGH("High (3MP)"), STD("Standard (1MP)") }
```

`RecordingSettings` 加两字段:

```kotlin
data class RecordingSettings(
    val videoQuality: VideoQuality = VideoQuality.P1080,
    val segmentMinutes: Int = 5,
    val photoQuality: PhotoQuality = PhotoQuality.HIGH,
    val photoResolution: PhotoResolution = PhotoResolution.MAX,
    val screenOffMinutes: Int = 3,
)
```

companion 加 keys 与选项:

```kotlin
        val SCREEN_OFF_OPTIONS = listOf(1, 3, 5, 0) // 0 = Never
        private val KEY_PHOTO_RESOLUTION = stringPreferencesKey("photo_resolution")
        private val KEY_SCREEN_OFF_MINUTES = intPreferencesKey("screen_off_minutes")
```

`settings` map 加两行:

```kotlin
            photoResolution = prefs[KEY_PHOTO_RESOLUTION]
                ?.let { name -> PhotoResolution.entries.firstOrNull { it.name == name } }
                ?: PhotoResolution.MAX,
            screenOffMinutes = prefs[KEY_SCREEN_OFF_MINUTES]?.takeIf { it in SCREEN_OFF_OPTIONS } ?: 3,
```

setter 加:

```kotlin
    suspend fun setPhotoResolution(value: PhotoResolution) {
        dataStore.edit { it[KEY_PHOTO_RESOLUTION] = value.name }
    }

    suspend fun setScreenOffMinutes(value: Int) {
        require(value in SCREEN_OFF_OPTIONS) { "screen off minutes must be one of $SCREEN_OFF_OPTIONS" }
        dataStore.edit { it[KEY_SCREEN_OFF_MINUTES] = value }
    }
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: photo resolution and auto screen-off settings"`

---

### Task 3: MediaStorage 改公共目录(TDD)

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/MediaStorage.kt`
- Modify: `app/src/test/java/com/benzn/grandtime/capture/MediaStorageTest.kt`

**Interfaces:**
- Produces: `MediaStorage(rootProvider: () -> File, clock)`——构造参数由「卷列表」改为「公共根目录提供者」;
  `mediaDir(kind)` = `<root>/FieldSight/device/<kind.dir>`;`newFile`/`hasFreeSpace`/`Kind` 不变;
  companion `MediaStorage.publicRoot(context): File`(SD 优先:StorageManager 可移动卷根否则
  Environment.getExternalStorageDirectory)。CaptureManager 改用 `MediaStorage { MediaStorage.publicRoot(context) }`。

- [ ] **Step 1: 改 `MediaStorageTest.kt`(rootProvider 版)**

把测试里构造 `MediaStorage({ listOf(internal) })` 全改为 `MediaStorage({ internal })`;
断言目录期望改为 `File(File(File(internal,"FieldSight"),"device"), "video")`。具体:

```kotlin
    @Test
    fun `newFile names by kind prefix and timestamp under FieldSight device`() {
        val root = tmp.newFolder("root")
        val storage = MediaStorage({ root })
        val f = storage.newFile(MediaStorage.Kind.VIDEO, millis(2026, 7, 11, 16, 2, 46))
        assertEquals("VID_20260711_160246.mp4", f.name)
        assertEquals(File(File(File(root, "FieldSight"), "device"), "video"), f.parentFile)
    }

    @Test
    fun `same-second collision appends suffix`() {
        val root = tmp.newFolder("root2")
        val storage = MediaStorage({ root })
        val t = millis(2026, 7, 11, 8, 0, 0)
        val first = storage.newFile(MediaStorage.Kind.PHOTO, t)
        first.parentFile!!.mkdirs(); first.createNewFile()
        assertEquals("IMG_20260711_080000_1.jpg", storage.newFile(MediaStorage.Kind.PHOTO, t).name)
    }

    @Test
    fun `hasFreeSpace true for tiny threshold`() {
        val root = tmp.newFolder("root3")
        assertTrue(MediaStorage({ root }).hasFreeSpace(minBytes = 1L))
    }
```

删掉旧的「single/second volume」两个测试(卷选择逻辑移到 publicRoot,不在此单测)。

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL(构造签名不符)。

- [ ] **Step 3: 改 `MediaStorage.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 公共存储:<root>/FieldSight/device/{video,audio,photo}。SD 优先(spec §3)。 */
class MediaStorage(
    private val rootProvider: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val dir: String, val prefix: String, val ext: String) {
        VIDEO("video", "VID", "mp4"),
        AUDIO("audio", "AUD", "m4a"),
        PHOTO("photo", "IMG", "jpg"),
    }

    fun mediaDir(kind: Kind): File =
        File(File(File(rootProvider(), "FieldSight"), "device"), kind.dir).apply { mkdirs() }

    fun newFile(kind: Kind, startMillis: Long = clock()): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startMillis))
        val dir = mediaDir(kind)
        var candidate = File(dir, "${kind.prefix}_$stamp.${kind.ext}")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${kind.prefix}_${stamp}_$suffix.${kind.ext}")
            suffix++
        }
        return candidate
    }

    fun hasFreeSpace(minBytes: Long = 200L * 1024 * 1024): Boolean = rootProvider().usableSpace >= minBytes

    companion object {
        /** SD 优先:枚举可移动卷取其目录;无则内置主共享存储。 */
        fun publicRoot(context: Context): File {
            val removable = runCatching {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                sm.storageVolumes.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                    ?.directory
            }.getOrNull()
            return removable ?: Environment.getExternalStorageDirectory()
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: MediaStorage writes to public FieldSight/device (SD-first)"`

---

### Task 4: MediaMigrator 旧私有目录迁移(TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/MediaMigrator.kt`
- Test: `app/src/test/java/com/benzn/grandtime/capture/MediaMigratorTest.kt`

**Interfaces:**
- Produces: `class MediaMigrator(oldRoot: File, newRoot: File, onMoved: (oldPath: String, newFile: File) -> Unit)`:
  `fun migrate(): Int`——把 `oldRoot/media/{video,audio,photo}/*` 移到 `newRoot/FieldSight/device/<kind>/`,
  同名跳过(幂等),每成功一个回调 onMoved(供 DB 路径更新),返回移动数。CaptureManager 首启调一次。

- [ ] **Step 1: 写失败测试 `MediaMigratorTest.kt`**

```kotlin
package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaMigratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun seed(oldRoot: File, kind: String, name: String) {
        val d = File(File(oldRoot, "media"), kind); d.mkdirs()
        File(d, name).writeText("x")
    }

    @Test
    fun `moves old private files to public and reports each`() {
        val oldRoot = tmp.newFolder("old"); val newRoot = tmp.newFolder("new")
        seed(oldRoot, "video", "VID_1.mp4"); seed(oldRoot, "photo", "IMG_1.jpg")
        val moved = mutableListOf<Pair<String, String>>()
        val n = MediaMigrator(oldRoot, newRoot) { old, nf -> moved.add(old to nf.name) }.migrate()
        assertEquals(2, n)
        assertTrue(File(File(File(File(newRoot, "FieldSight"), "device"), "video"), "VID_1.mp4").exists())
        assertFalse(File(File(File(oldRoot, "media"), "video"), "VID_1.mp4").exists())
        assertEquals(2, moved.size)
    }

    @Test
    fun `idempotent - second run moves nothing and skips existing`() {
        val oldRoot = tmp.newFolder("old2"); val newRoot = tmp.newFolder("new2")
        seed(oldRoot, "audio", "AUD_1.m4a")
        MediaMigrator(oldRoot, newRoot) { _, _ -> }.migrate()
        val n2 = MediaMigrator(oldRoot, newRoot) { _, _ -> }.migrate()
        assertEquals(0, n2)
    }

    @Test
    fun `no old dir - returns zero`() {
        val n = MediaMigrator(tmp.newFolder("none"), tmp.newFolder("dst")) { _, _ -> }.migrate()
        assertEquals(0, n)
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现 `MediaMigrator.kt`**

```kotlin
package com.benzn.grandtime.capture

import java.io.File

/** 旧 app 私有目录 <oldRoot>/media/<kind>/* → 公共 <newRoot>/FieldSight/device/<kind>/*。幂等。 */
class MediaMigrator(
    private val oldRoot: File,
    private val newRoot: File,
    private val onMoved: (oldPath: String, newFile: File) -> Unit,
) {
    fun migrate(): Int {
        var moved = 0
        for (kind in listOf("video", "audio", "photo")) {
            val srcDir = File(File(oldRoot, "media"), kind)
            val files = srcDir.listFiles()?.filter { it.isFile } ?: continue
            val destDir = File(File(File(newRoot, "FieldSight"), "device"), kind).apply { mkdirs() }
            for (src in files) {
                val dest = File(destDir, src.name)
                if (dest.exists()) { src.delete(); continue }
                val oldPath = src.absolutePath
                if (src.renameTo(dest) || (src.copyTo(dest, overwrite = false).exists().also { src.delete() })) {
                    onMoved(oldPath, dest)
                    moved++
                }
            }
        }
        return moved
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: one-time migrator from private to public media dir"`

---

### Task 5: CaptureSounds(MediaActionSound)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/CaptureSounds.kt`

**Interfaces:**
- Produces: `class CaptureSounds`:`fun startRecording()`、`fun stopRecording()`、`fun shutter()`、`fun release()`;懒加载 MediaActionSound。

- [ ] **Step 1: 实现 `CaptureSounds.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.media.MediaActionSound

/** 系统内置录制/快门提示音(spec §2.7)。懒加载,主线程调用安全。 */
class CaptureSounds {
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
        load(MediaActionSound.SHUTTER_CLICK)
    }

    fun startRecording() = sound.play(MediaActionSound.START_VIDEO_RECORDING)
    fun stopRecording() = sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
    fun shutter() = sound.play(MediaActionSound.SHUTTER_CLICK)
    fun release() = sound.release()
}
```

- [ ] **Step 2: 构建 + Commit** — `./gradlew assembleDebug && git add app/src && git commit -m "feat: system capture sounds (MediaActionSound)"`

---

### Task 6: CameraSession 可选 Preview 用例

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CameraSession.kt`

**Interfaces:**
- Consumes: 现有 bindForVideo/bindForPhoto/unbind + camera/videoCapture/imageCapture 属性
- Produces: `fun attachPreview(surfaceProvider: Preview.SurfaceProvider)`、`fun detachPreview()`;内部
  维护 `previewUseCase: Preview?`;attach 时把 Preview 追加进现有绑定(不解绑 VideoCapture),detach 时仅移除 Preview。

- [ ] **Step 1: 改 `CameraSession.kt`**

在类中加字段与方法(imports 补 `androidx.camera.core.Preview`):

```kotlin
    private var previewUseCase: Preview? = null

    /** 追加 Preview 用例到当前绑定(不动 VideoCapture)。前台可见时调。 */
    suspend fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        val p = provider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
        // 追加绑定:CameraX 允许在已绑基础上 bindToLifecycle 追加用例。
        val useCases = listOfNotNull(videoCapture, imageCapture, preview).toTypedArray()
        p.unbindAll()
        camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
        previewUseCase = preview
    }

    fun detachPreview() {
        val preview = previewUseCase ?: return
        runCatching { provider0?.unbind(preview) }
        previewUseCase = null
    }
```

注:`provider()` 是现有 suspend 取 provider 的方法;若现有实现把 provider 存在私有字段(如
`provider`),detachPreview 用该字段做同步 unbind(命名以现有为准,记为 `provider0`——实施时
对齐现有字段名)。**约束**:attachPreview 内 `unbindAll()+重绑`会短暂断流,若正在录像会中断——
因此优先用 CameraX 的「追加用例」而非 unbindAll:改为

```kotlin
    suspend fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        val p = provider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
        camera = p.bindToLifecycle(
            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
            *listOfNotNull(videoCapture, imageCapture, preview).toTypedArray(),
        )
        previewUseCase = preview
    }
```

(bindToLifecycle 对已绑用例是幂等追加;若设备抛 IllegalArgumentException 说明三流超限——
catch 后仅绑 Preview+VideoCapture、放弃 imageCapture,记 probe;真机 Task 13 验。)

- [ ] **Step 2: 构建 + Commit** — `./gradlew assembleDebug && git add app/src && git commit -m "feat: optional Preview use case on CameraSession"`

---

### Task 7: CaptureManager 接线(公共存储/提示音/媒体扫描/迁移/照片精度/熄屏信号)

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/core/AppState.kt`

**Interfaces:**
- Consumes: MediaStorage.publicRoot、MediaMigrator、CaptureSounds、SettingsStore.PhotoResolution、CameraSession.attachPreview/detachPreview
- Produces: `AppState.previewSurface: MutableStateFlow<Preview.SurfaceProvider?>`(UI 设,Manager 观察);
  CaptureManager `fun requestScreenWake()`/内部熄屏计时;public storage 注入;起停提示音;scanFile 收录。

- [ ] **Step 1: `AppState.kt` 加**

```kotlin
    /** RecordingScreen 提供的预览 surface(前台可见时非 null)。 */
    val previewSurface = MutableStateFlow<androidx.camera.core.Preview.SurfaceProvider?>(null)
    /** 自动熄屏请求(录像满 N 分钟置 true;UI 观察后释放屏幕常亮)。 */
    val screenOffRequest = MutableStateFlow(false)
```

- [ ] **Step 2: 改 `CaptureManager.kt`**

(a) storage 构造改公共根 + 加成员:

```kotlin
    private val storage = MediaStorage({ MediaStorage.publicRoot(context) })
    private val sounds = CaptureSounds()
```

(b) 构造后首启迁移(在 init 块或首个 handle 前;放 init):

```kotlin
    init {
        scope.launch(Dispatchers.IO) {
            val oldRoot = context.getExternalFilesDir(null) ?: return@launch // 其下有 media/{kind}
            val migrator = MediaMigrator(
                oldRoot = oldRoot,
                newRoot = MediaStorage.publicRoot(context),
            ) { oldPath, newFile -> scope.launch { dao.updatePath(oldPath, newFile.absolutePath) } }
            runCatching { migrator.migrate() }
        }
    }
```

需 `db/CaptureRecordDao.kt` 加:

```kotlin
    @Query("UPDATE capture_records SET filePath = :newPath WHERE filePath = :oldPath")
    suspend fun updatePath(oldPath: String, newPath: String)
```

(c) preflight 加 MANAGE 门(相机/麦克风之后):

```kotlin
        val startsCaptureAny = action == KeyAction.START_STOP_VIDEO ||
            action == KeyAction.START_STOP_AUDIO || action == KeyAction.TAKE_PHOTO
        if (startsCaptureAny && !android.os.Environment.isExternalStorageManager()) {
            notify("Storage access required — finish setup"); return false
        }
```

(d) 起录/停录提示音 + scanFile:startVideoSegment 成功后 `sounds.startRecording()`(仅段 1,
`if (cmd.segmentIndex == 1)`);startAudio 成功后 `sounds.startRecording()`;takePhoto/performFrameGrab
成功后 `sounds.shutter()`;停录(onAudioFinalized / onVideoFinalized 回 Idle)处 `sounds.stopRecording()`。
每个文件 finalize(finalizeVideoDbRow / stopAudio / 照片 insert 后)调:

```kotlin
    private fun scan(path: String) {
        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
```

在 finalizeVideoDbRow 的 `dao.finalize` 后 `scan(file.absolutePath)`;stopAudio 同理;
照片两处 insert 后 `scan(file/out.absolutePath)`。

(e) 照片精度应用:photo.take 前按 `settings.photoResolution` 设 ImageCapture 目标分辨率——
在 `session.bindForPhoto` 时传入(见下),或独立拍照绑定时用 ResolutionSelector。最小改法:
CameraSession.bindForPhoto 增参 `targetPx: Long?`(MAX=null 满传感器,HIGH≈3_000_000,STD≈1_000_000),
CaptureManager 传 `settings.photoResolution.targetPixels()`。`PhotoResolution.targetPixels()`:

```kotlin
private fun com.benzn.grandtime.core.PhotoResolution.targetPixels(): Long? = when (this) {
    com.benzn.grandtime.core.PhotoResolution.MAX -> null
    com.benzn.grandtime.core.PhotoResolution.HIGH -> 3_000_000L
    com.benzn.grandtime.core.PhotoResolution.STD -> 1_000_000L
}
```

(bindForPhoto 用 ResolutionSelector + ResolutionStrategy 就近选;MAX 用默认 MAXIMUM。)

(f) 预览 attach/detach:收 `AppState.previewSurface`——录像态且 surface 非 null → attachPreview;
surface 变 null 或回 Idle → detachPreview。在 init 加 collector:

```kotlin
        scope.launch {
            AppState.previewSurface.collect { sp ->
                if (sp != null && core.state is CaptureState.RecordingVideo) {
                    runCatching { session.attachPreview(sp) }
                } else {
                    session.detachPreview()
                }
            }
        }
```

(g) 熄屏计时:startVideoSegment 段 1 起,launch 一个 `screenOffTimer`,delay
`settings.screenOffMinutes*60_000`(>0 才计;0=Never 不计),到点 `AppState.screenOffRequest.value = true`;
停录/回 Idle 时 `AppState.screenOffRequest.value = false` 且 cancel 计时器。

(h) shutdown 加 `sounds.release()`。

- [ ] **Step 3: 构建 + 全量测试** — `./gradlew assembleDebug && ./gradlew test`(既有单测零回归)。

- [ ] **Step 4: Commit** — `git add app/src && git commit -m "feat: public storage, sounds, media-scan, migration, photo resolution, screen-off signal"`

---

### Task 8: RecordingScreen 全屏预览 + 导航 + 自动熄屏

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/ui/RecordingScreen.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/ui/Screen.kt`(+RECORDING)、`ui/MainActivity.kt`

**Interfaces:**
- Consumes: `AppState.captureState/previewSurface/screenOffRequest`、`CaptureState.RecordingVideo`
- Produces: `RecordingScreen(onStop: () -> Unit)`;MainActivity 观察 captureState 导航。

- [ ] **Step 1: `Screen.kt` 加** `RECORDING("Recording")`。

- [ ] **Step 2: `RecordingScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.CaptureState
import kotlinx.coroutines.delay

@Composable
fun RecordingScreen(onStop: () -> Unit) {
    val context = LocalContext.current
    val capture by AppState.captureState.collectAsStateWithLifecycle()
    val screenOff by AppState.screenOffRequest.collectAsStateWithLifecycle()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMillis = System.currentTimeMillis(); delay(1000) } }

    // 预览 surface 交给 CaptureManager;熄屏请求未到时保持屏幕常亮。
    val previewView = remember { PreviewView(context) }
    DisposableEffect(Unit) {
        AppState.previewSurface.value = previewView.surfaceProvider
        onDispose { AppState.previewSurface.value = null }
    }
    val activity = remember(context) { context as? android.app.Activity }
    LaunchedEffect(screenOff) {
        val w = activity?.window ?: return@LaunchedEffect
        if (screenOff) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        val start = (capture as? CaptureState.RecordingVideo)?.startedAtMillis
        Row(
            Modifier.align(Alignment.TopStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(8.dp))
            Text(
                "REC ${mmss((nowMillis - (start ?: nowMillis)))}",
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
        }
        Button(
            onClick = onStop,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White,
            ),
        ) { Text("Stop") }
    }
}

private fun mmss(elapsed: Long): String {
    val t = (elapsed / 1000).coerceAtLeast(0); return "%02d:%02d".format(t / 60, t % 60)
}
```

需依赖 `androidx.camera:camera-view`(PreviewView)——Task 1 未加,此处补:libs.versions.toml
加 `camera-view = { group="androidx.camera", name="camera-view", version.ref="camerax" }`,
app/build.gradle.kts 加 `implementation(libs.camera.view)`。

- [ ] **Step 3: `MainActivity.kt` 导航**

MainScaffold 里,`when` 前加:观察 captureState,`RecordingVideo` 且当前非 RECORDING → 切
`screen=Screen.RECORDING`;非 RecordingVideo 且当前是 RECORDING → 回 `Screen.HOME`:

```kotlin
    val capture by AppState.captureState.collectAsStateWithLifecycle()
    LaunchedEffect(capture) {
        if (capture is CaptureState.RecordingVideo && screen != Screen.RECORDING) screen = Screen.RECORDING
        else if (capture !is CaptureState.RecordingVideo && screen == Screen.RECORDING) screen = Screen.HOME
    }
```

`when(screen)` 加分支 `Screen.RECORDING -> RecordingScreen(onStop = { AppState.screenKeyEvents.tryEmit(...) })`——
停止走物理键同管线:emit `HardKey.VIDEO to RawDirection.DOWN` 后 UP(短按=停),或直接调
CaptureManager?更稳:RecordingScreen 的 Stop 发 `AppState.screenKeyEvents`(VIDEO down/up),
与屏幕键同路径触发 START_STOP_VIDEO(录像态=停)。RECORDING 时隐藏底栏与顶栏(全屏)。

- [ ] **Step 4: 构建 + 真机冒烟**

```bash
export ANDROID_SERIAL=F2S202503103054 && ./gradlew installDebug
```

手动:授 MANAGE 权限后,前台按录像 → 进全屏预览、见镜头、红点计时、Stop 回主页。

- [ ] **Step 5: Commit** — `git add -A app/src app/build.gradle.kts gradle && git commit -m "feat: fullscreen RecordingScreen preview, nav, auto screen-off"`

---

### Task 9: 权限向导扩展 MANAGE_EXTERNAL_STORAGE

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`、`ui/HomeScreen.kt`

- [ ] **Step 1: MainActivity 权限流加 MANAGE 引导**

`maybeRequestOverlay()` 之后链一步 `maybeRequestAllFiles()`:

```kotlin
    private fun maybeRequestAllFiles() {
        if (!android.os.Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Allow \"All files access\" so recordings save to a browsable folder", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }
```

在 overlay 授权回来后或 onCreate 权限齐后调用一次(串在 maybeRequestOverlay 之后)。

- [ ] **Step 2: HomeScreen 权限警示合并 MANAGE 检查**

`isSetupComplete(context)` 的返回改为 `camera && mic && Settings.canDrawOverlays(context) &&
Environment.isExternalStorageManager()`;`openSetup` 若缺 all-files 则跳
`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`(overlay 已授时)。

- [ ] **Step 3: 构建 + Commit** — `./gradlew assembleDebug && git add app/src && git commit -m "feat: all-files-access step in permission wizard"`

---

### Task 10: SettingsScreen 增 照片精度 + 自动熄屏

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ui/SettingsScreen.kt`

- [ ] **Step 1: RECORDING 组加两行**

在 Recording 组的 FsCard 内(现有 Video quality/Segment length/Photo quality 之后)加:

```kotlin
            RowDivider()
            SettingRow("Photo resolution", settings.photoResolution.label) { dialog = SettingDialog.PHOTO_RESOLUTION }
            RowDivider()
            SettingRow(
                "Auto screen-off",
                if (settings.screenOffMinutes == 0) "Never" else "${settings.screenOffMinutes} min",
            ) { dialog = SettingDialog.SCREEN_OFF }
```

`SettingDialog` 枚举加 `PHOTO_RESOLUTION, SCREEN_OFF`;`when(dialog)` 加两分支:

```kotlin
        SettingDialog.PHOTO_RESOLUTION -> RadioDialog(
            title = "Photo resolution", options = PhotoResolution.entries, selected = settings.photoResolution,
            label = { it.label }, onSelect = { scope.launch { store.setPhotoResolution(it) } }, onDismiss = { dialog = null },
        )
        SettingDialog.SCREEN_OFF -> RadioDialog(
            title = "Auto screen-off", options = SettingsStore.SCREEN_OFF_OPTIONS, selected = settings.screenOffMinutes,
            label = { if (it == 0) "Never" else "$it min" },
            onSelect = { scope.launch { store.setScreenOffMinutes(it) } }, onDismiss = { dialog = null },
        )
```

import 补 `PhotoResolution`。

- [ ] **Step 2: 构建 + Commit** — `./gradlew assembleDebug && git add app/src && git commit -m "feat: photo resolution and auto screen-off settings UI"`

---

### Task 11: FilesScreen 缩略图网格(Coil)

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ui/FilesScreen.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/GrandTimeApp.kt`(Coil ImageLoader + video decoder)

**Interfaces:**
- Consumes: Room `observeAll()`、CaptureRecord

- [ ] **Step 1: GrandTimeApp 提供 Coil ImageLoader(含 VideoFrameDecoder)**

`GrandTimeApp` 实现 `coil.ImageLoaderFactory`:

```kotlin
class GrandTimeApp : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader() = coil.ImageLoader.Builder(this)
        .components { add(coil.decode.VideoFrameDecoder.Factory()) }
        .build()
}
```

- [ ] **Step 2: FilesScreen 列表 → LazyVerticalGrid 缩略图**

MediaRow 改为缩略图单元:视频/照片用 `AsyncImage(model = ImageRequest.Builder(context).data(File(record.filePath))...)`,
视频请求加 `.videoFrameMillis(0)`(取首帧);音频无帧用音符占位图标。3 列网格,组头 `span(maxLineSpan)`。
点单元 → `ACTION_VIEW`(FileProvider 或 file:// under MANAGE)。时长角标叠加右下。
(完整代码:LazyVerticalGrid(GridCells.Fixed(3)),item 里 Box 叠 AsyncImage + 时长文本 + 视频 ▶。)

- [ ] **Step 3: 构建 + 真机走查** — `./gradlew installDebug`;录几个文件后 Files 页出缩略图。

- [ ] **Step 4: Commit** — `git add app/src && git commit -m "feat: Files thumbnail grid via Coil (video first-frame)"`

---

### Task 12: H.265/HEVC 编码(隔离,验证/尽力)

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CameraSession.kt` 或 `VideoRecorder.kt`

- [ ] **Step 1: 真机测当前默认编码**

录一段 → pull → `ffprobe`(或 `adb shell` MediaExtractor 日志)看 codec。若已是 hevc → 直接跳 Step 3 记录。

- [ ] **Step 2: 尝试强制 HEVC**

CameraX 1.4 `Recorder` 无公开 setVideoCodec。可尝试路径(择一验证):
(a) 选 QualitySelector 中该设备 HEVC 的 CamcorderProfile(部分 MTK 设备 HEVC profile 存在);
(b) 若不可行:记录"CameraX 1.4 无法强制 HEVC",**STOP 并报控制器**——由用户定是否为 HEVC 上
Camera2/MediaRecorder 管线(超出 SP3c),或接受设备默认编码。不要在本任务内做大改。

- [ ] **Step 3: 记录结论 + Commit(若有改动)**

报告实测编码与是否达成 HEVC;commit 仅当有可用改动。

---

### Task 13: 真机端到端验收 + tag

**Files:** 无新文件(缺陷回对应 Task 修)

设备 F2S202503103054。安装 + 授权(相机/麦克风 `pm grant`;overlay `appops ... SYSTEM_ALERT_WINDOW allow`;
**MANAGE**:`appops set com.benzn.grandtime MANAGE_EXTERNAL_STORAGE allow`)。逐项(证据:logcat/截图/
`adb shell ls /sdcard/FieldSight/device/*`/ffprobe):

- [ ] **1 公共可见**:录一段 → `adb shell ls -la /sdcard/FieldSight/device/video/` 有文件;
  安卓自带 Files 应用 `FieldSight/device/video` 可见;相册能看到(scanFile);**请用户在设备 Files 里确认**
- [ ] **2 全屏预览 + 提示音**:前台按录像 → 进全屏预览、镜头实时、红点计时、**开录提示音**;停 → 回主页 + 停录音
- [ ] **3 1080p 未降**:pull 视频 ffprobe 确认分辨率 1080p(编码见 Task 12 结论)
- [ ] **4 息屏后台录**:息屏物理键录 → 后台成功,文件落公共目录
- [ ] **5 自动熄屏续录**:设 Auto screen-off=1min → 录 → 1 分钟后屏灭、`ls` 确认文件仍增长 → 唤醒回预览
- [ ] **6 缩略图**:Files 页视频显首帧、照片显缩略图、点开能播
- [ ] **7 照片精度**:改 photo_resolution=Std → 单独拍照 → 尺寸随档变(ffprobe/尺寸)
- [ ] **8 迁移**:(若旧私有目录尚有文件)重装后启动 → 旧文件迁到公共目录、DB 路径更新、Files 不丢
- [ ] **9 权限缺失**:撤 MANAGE(`appops ... deny`)→ 按录像提示 "Storage access required"、不崩
- [ ] **收尾**:`./gradlew test` 全绿;`git tag sp3c-accepted`;报告逐项 expected vs actual
  + USER VISUAL(手电、回放、Files 应用可见性)

---

## 验证总览

| 层次 | 手段 | 覆盖 |
|---|---|---|
| JVM 单测 | `./gradlew test` | SettingsStore(照片精度/熄屏)、MediaStorage(公共路径)、MediaMigrator(迁移幂等)+ 既有 |
| 真机 | Task 13 九项 | 公共可见/预览/提示音/熄屏续录/缩略图/精度/迁移/权限 |

## 风险与缓解

- **H.265 在 CameraX 1.4 无法强制** → Task 12 隔离,不可行则报控制器 + 用户决策,不阻塞其余
- **录制中热加 Preview 打断录像** → attachPreview 用追加用例(非 unbindAll);真机验;失败则退化只在 Idle→录像启动时带 Preview
- **480P 三流(预览+录像+拍照)超限** → catch 后放弃 imageCapture,拍照走抓帧;真机验
- **MANAGE_EXTERNAL_STORAGE 授权页因 ROM 而异** → appops 命令兜底验收;Home 警示引导
- **迁移与新录并发** → 迁移在 init 一次性 IO;新录用新根,路径不冲突;renameTo 失败回退 copy+delete
- **Dropbox 文件锁** → 重跑
