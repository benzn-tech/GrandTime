# SP3a 采集核心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 物理键真正驱动录像(分段)/拍照(含录制中取证拍照)/录音/手电,元数据入 Room(SQLite,RDS 词汇映射),Files/Home 联动,SYSTEM_ALERT_WINDOW 息屏豁免。

**Architecture:** 纯决策核 CaptureCore(互斥表状态机,JVM 可测)输出命令,CaptureManager 在主线程串行执行(CameraX 绑定/录制、MediaRecorder 音频、DB 写入、震动/通知);CameraSession 管双用例并绑与降级;MediaStorage 管卷选择/命名;FilesScreen 改由 Room Flow 渲染 + 打开对账。

**Tech Stack:** 既有栈 + CameraX 1.4.1(core/camera2/lifecycle/video)+ Room 2.6.1 + KSP 2.1.0-1.0.29。

Spec(唯一事实来源):`docs/superpowers/specs/2026-07-11-sp3a-capture-design.md`(v2)

## Global Constraints

- 工作分支:`feature/sp3a-capture`(自 main 切出;控制器创建)
- 互斥表以 spec §2 v2 为准:**录像中/录音中拍照 = 执行且不打断,同 session_id**;录像↔录音互斥(忽略+震两下)
- DB schema 严格按 spec §3.5(字段名/取值);目录与命名按 spec §3(`VID_yyyyMMdd_HHmmss.mp4` 等,SD 卡优先=getExternalFilesDirs 第二卷)
- 分段=SettingsStore.segmentMinutes;清晰度映射 1080P/720P/480P→FHD/HD/SD;JPEG 95/80;音频 AAC/M4A 128kbps 44.1kHz
- 空间阈值:开录/拍照前 usableSpace ≥200MB,否则拒绝+通知 "Storage full"
- ASK_AGENT 进 KeyAction 与 Labels("Ask agent"),handler 桩 = 通知 "Ask agent coming soon";DEFAULTS 映射表不变
- 逻辑层 hardware/keymap(除 KeyAction 枚举追加)/auth/boot 不动
- gradle 前缀:`export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`;设备一律 `-s F2S202503103054` + `export ANDROID_SERIAL=F2S202503103054`(模拟器不可用;真机已授权,是产品目标硬件)
- Dropbox 文件锁:重跑一次;持续 → BLOCKED
- 每 Task 一 commit,footer:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014TCKsgA4JjaWbbFD4zY7zh
```

---

### Task 1: 依赖与权限(构建门)

**Files:**
- Modify: `gradle/libs.versions.toml`、`build.gradle.kts`(根)、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: CameraX/Room/KSP 依赖可用;Manifest 含 CAMERA/RECORD_AUDIO/VIBRATE/SYSTEM_ALERT_WINDOW。

- [ ] **Step 1: `gradle/libs.versions.toml` 增补**

`[versions]` 加:

```toml
camerax = "1.4.1"
room = "2.6.1"
ksp = "2.1.0-1.0.29"
```

`[libraries]` 加:

```toml
camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camera-video = { group = "androidx.camera", name = "camera-video", version.ref = "camerax" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
```

`[plugins]` 加:

```toml
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: 根 `build.gradle.kts` plugins 块加一行**

```kotlin
alias(libs.plugins.ksp) apply false
```

- [ ] **Step 3: `app/build.gradle.kts`**

plugins 块加 `alias(libs.plugins.ksp)`;dependencies 加:

```kotlin
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
```

- [ ] **Step 4: Manifest `<manifest>` 根下加**

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

- [ ] **Step 5: 构建门**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: 双 BUILD SUCCESSFUL(KSP 首跑会下载,耗时正常)。

- [ ] **Step 6: Commit** — `git add -A gradle build.gradle.kts app && git commit -m "build: CameraX, Room/KSP deps and capture permissions"`

---

### Task 2: db 包(Room)+ FilesReconciler(TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/db/CaptureRecord.kt`、`.../db/CaptureRecordDao.kt`、`.../db/CaptureDb.kt`、`.../db/FilesReconciler.kt`
- Test: `app/src/test/java/com/benzn/grandtime/db/FilesReconcilerTest.kt`

**Interfaces:**
- Produces:
  - `@Entity CaptureRecord(id, kind, filePath, fileName, startedAt, endedAt=null, durationMs=null, sizeBytes=0, codec, resolution=null, segmentIndex=null, sessionId, authorSub=null, siteSlug=null, uploadStatus="pending", createdAt, missing=false)`
  - `CaptureRecordDao`:`suspend insert(CaptureRecord)`、`suspend finalize(id, endedAt, durationMs, sizeBytes)`、`observeAll(): Flow<List<CaptureRecord>>`(missing=0,startedAt 倒序)、`suspend listAll()`、`suspend markMissing(ids)`
  - `CaptureDb.get(context): CaptureDb`(单例,db 名 "capture.db")
  - `FilesReconciler(dao, durationReader, clock)`:`suspend reconcile(diskFiles: List<DiskFile>)`;`DiskFile(path, name, kind, sizeBytes, modifiedMillis)`

- [ ] **Step 1: 写失败测试 `FilesReconcilerTest.kt`**

```kotlin
package com.benzn.grandtime.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDao : CaptureRecordDao {
    val rows = mutableListOf<CaptureRecord>()
    val missingIds = mutableListOf<String>()
    override suspend fun insert(record: CaptureRecord) { rows.add(record) }
    override suspend fun finalize(id: String, endedAt: Long, durationMs: Long, sizeBytes: Long) {}
    override fun observeAll(): Flow<List<CaptureRecord>> = flowOf(rows)
    override suspend fun listAll(): List<CaptureRecord> = rows.toList()
    override suspend fun markMissing(ids: List<String>) { missingIds.addAll(ids) }
}

class FilesReconcilerTest {

    private fun record(id: String, path: String) = CaptureRecord(
        id = id, kind = "video", filePath = path, fileName = path.substringAfterLast('/'),
        startedAt = 1L, codec = "h264", sessionId = "s", createdAt = 1L,
    )

    @Test
    fun `disk file without db row gets inserted with duration`() = runTest {
        val dao = FakeDao()
        val reconciler = FilesReconciler(dao, durationReader = { 42_000L }, clock = { 99L })
        reconciler.reconcile(
            listOf(FilesReconciler.DiskFile("/m/video/VID_1.mp4", "VID_1.mp4", "video", 10L, 5L))
        )
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals("VID_1.mp4", row.fileName)
        assertEquals("video", row.kind)
        assertEquals(42_000L, row.durationMs)
        assertEquals(5L, row.startedAt)
        assertEquals(99L, row.createdAt)
    }

    @Test
    fun `db row without disk file marked missing`() = runTest {
        val dao = FakeDao()
        dao.rows.add(record("gone", "/m/video/GONE.mp4"))
        val reconciler = FilesReconciler(dao, durationReader = { null }, clock = { 0L })
        reconciler.reconcile(emptyList())
        assertEquals(listOf("gone"), dao.missingIds)
    }

    @Test
    fun `matching rows untouched`() = runTest {
        val dao = FakeDao()
        dao.rows.add(record("keep", "/m/video/KEEP.mp4"))
        val reconciler = FilesReconciler(dao, durationReader = { null }, clock = { 0L })
        reconciler.reconcile(
            listOf(FilesReconciler.DiskFile("/m/video/KEEP.mp4", "KEEP.mp4", "video", 10L, 5L))
        )
        assertEquals(1, dao.rows.size)
        assertTrue(dao.missingIds.isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL(unresolved reference)。

- [ ] **Step 3: 实现**

`db/CaptureRecord.kt`:

```kotlin
package com.benzn.grandtime.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地元数据行,字段与未来 RDS recordings 表 1:1(spec §3.5)。
 * kind: video/audio/photo;codec: h264/aac/jpeg/frame-grab/unknown。
 */
@Entity(tableName = "capture_records")
data class CaptureRecord(
    @PrimaryKey val id: String,
    val kind: String,
    val filePath: String,
    val fileName: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long = 0,
    val codec: String,
    val resolution: String? = null,
    val segmentIndex: Int? = null,
    val sessionId: String,
    val authorSub: String? = null,
    val siteSlug: String? = null,
    val uploadStatus: String = "pending",
    val createdAt: Long,
    val missing: Boolean = false,
)
```

`db/CaptureRecordDao.kt`:

```kotlin
package com.benzn.grandtime.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CaptureRecord)

    @Query("UPDATE capture_records SET endedAt = :endedAt, durationMs = :durationMs, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun finalize(id: String, endedAt: Long, durationMs: Long, sizeBytes: Long)

    @Query("SELECT * FROM capture_records WHERE missing = 0 ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CaptureRecord>>

    @Query("SELECT * FROM capture_records")
    suspend fun listAll(): List<CaptureRecord>

    @Query("UPDATE capture_records SET missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<String>)
}
```

`db/CaptureDb.kt`:

```kotlin
package com.benzn.grandtime.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CaptureRecord::class], version = 1, exportSchema = false)
abstract class CaptureDb : RoomDatabase() {
    abstract fun captureRecords(): CaptureRecordDao

    companion object {
        @Volatile private var instance: CaptureDb? = null

        fun get(context: Context): CaptureDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CaptureDb::class.java, "capture.db")
                .build()
                .also { instance = it }
        }
    }
}
```

`db/FilesReconciler.kt`:

```kotlin
package com.benzn.grandtime.db

import java.util.UUID

/** 文件↔DB 对账:磁盘有 DB 无 → 补插;DB 有磁盘无 → 标 missing。 */
class FilesReconciler(
    private val dao: CaptureRecordDao,
    private val durationReader: (path: String) -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class DiskFile(
        val path: String,
        val name: String,
        val kind: String,
        val sizeBytes: Long,
        val modifiedMillis: Long,
    )

    suspend fun reconcile(diskFiles: List<DiskFile>) {
        val records = dao.listAll()
        val knownPaths = records.map { it.filePath }.toSet()
        diskFiles.filter { it.path !in knownPaths }.forEach { f ->
            dao.insert(
                CaptureRecord(
                    id = UUID.randomUUID().toString(),
                    kind = f.kind,
                    filePath = f.path,
                    fileName = f.name,
                    startedAt = f.modifiedMillis,
                    endedAt = f.modifiedMillis,
                    durationMs = durationReader(f.path),
                    sizeBytes = f.sizeBytes,
                    codec = "unknown",
                    sessionId = "reconciled",
                    createdAt = clock(),
                )
            )
        }
        val diskPaths = diskFiles.map { it.path }.toSet()
        val gone = records.filter { !it.missing && it.filePath !in diskPaths }.map { it.id }
        if (gone.isNotEmpty()) dao.markMissing(gone)
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(新 3 个)。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: Room capture_records (RDS-mapped) and files reconciler"`

---

### Task 3: MediaStorage(TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/MediaStorage.kt`
- Test: `app/src/test/java/com/benzn/grandtime/capture/MediaStorageTest.kt`

**Interfaces:**
- Produces: `MediaStorage(volumesProvider: () -> List<File?>, clock: () -> Long = System::currentTimeMillis)`:`enum Kind(dir, prefix, ext) { VIDEO("video","VID","mp4"), AUDIO("audio","AUD","m4a"), PHOTO("photo","IMG","jpg") }`、`root(): File`(≥2 卷取第二卷=SD,否则第一卷)、`mediaDir(Kind): File`(自动 mkdirs)、`newFile(Kind, startMillis): File`(`VID_yyyyMMdd_HHmmss.mp4`,同名追加 `_1`)、`hasFreeSpace(minBytes = 200MB): Boolean`。

- [ ] **Step 1: 写失败测试 `MediaStorageTest.kt`**

```kotlin
package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class MediaStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance(TimeZone.getDefault()).apply {
            set(y, mo - 1, d, h, mi, s); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `single volume used when no sd card`() {
        val internal = tmp.newFolder("internal")
        val storage = MediaStorage({ listOf(internal) })
        assertEquals(internal, storage.root())
    }

    @Test
    fun `second volume preferred when sd present, nulls filtered`() {
        val internal = tmp.newFolder("internal")
        val sd = tmp.newFolder("sd")
        val storage = MediaStorage({ listOf(internal, null, sd) })
        assertEquals(sd, storage.root())
    }

    @Test
    fun `newFile names by kind prefix and timestamp`() {
        val internal = tmp.newFolder("i2")
        val storage = MediaStorage({ listOf(internal) })
        val f = storage.newFile(MediaStorage.Kind.VIDEO, millis(2026, 7, 11, 16, 2, 46))
        assertEquals("VID_20260711_160246.mp4", f.name)
        assertEquals(File(File(internal, "media"), "video"), f.parentFile)
    }

    @Test
    fun `same-second collision appends suffix`() {
        val internal = tmp.newFolder("i3")
        val storage = MediaStorage({ listOf(internal) })
        val t = millis(2026, 7, 11, 8, 0, 0)
        val first = storage.newFile(MediaStorage.Kind.PHOTO, t)
        first.parentFile!!.mkdirs(); first.createNewFile()
        val second = storage.newFile(MediaStorage.Kind.PHOTO, t)
        assertEquals("IMG_20260711_080000_1.jpg", second.name)
    }

    @Test
    fun `hasFreeSpace true for tiny threshold, false for absurd`() {
        val internal = tmp.newFolder("i4")
        val storage = MediaStorage({ listOf(internal) })
        assertTrue(storage.hasFreeSpace(minBytes = 1L))
        assertTrue(!storage.hasFreeSpace(minBytes = Long.MAX_VALUE))
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现 `capture/MediaStorage.kt`**

```kotlin
package com.benzn.grandtime.capture

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 卷选择(SD 优先)/目录/命名。spec §3。 */
class MediaStorage(
    private val volumesProvider: () -> List<File?>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val dir: String, val prefix: String, val ext: String) {
        VIDEO("video", "VID", "mp4"),
        AUDIO("audio", "AUD", "m4a"),
        PHOTO("photo", "IMG", "jpg"),
    }

    fun root(): File {
        val volumes = volumesProvider().filterNotNull()
        require(volumes.isNotEmpty()) { "no storage volume available" }
        return if (volumes.size >= 2) volumes[1] else volumes[0]
    }

    fun mediaDir(kind: Kind): File = File(File(root(), "media"), kind.dir).apply { mkdirs() }

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

    fun hasFreeSpace(minBytes: Long = 200L * 1024 * 1024): Boolean = root().usableSpace >= minBytes
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(新 5 个)。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: media storage (sd-first volume, naming, free-space gate)"`

---

### Task 4: CaptureCore 状态机(TDD,核心)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/CaptureState.kt`、`.../capture/CaptureCore.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/keymap/KeyAction.kt`(追加 ASK_AGENT)、`app/src/main/java/com/benzn/grandtime/ui/Labels.kt`(追加 "Ask agent")
- Test: `app/src/test/java/com/benzn/grandtime/capture/CaptureCoreTest.kt`

**Interfaces:**
- Produces:
  - `sealed CaptureState { Idle; RecordingVideo(sessionId, segmentIndex, startedAtMillis); RecordingAudio(sessionId, startedAtMillis) }`
  - `sealed CaptureCommand { StartVideoSegment(sessionId, segmentIndex); StopVideo(rollToNext); TakePhoto(sessionId); StartAudio(sessionId); StopAudio; ToggleTorch; CycleVolume; Vibrate(times); Notify(text) }`
  - `CaptureCore(clock, newId)`:`state: CaptureState`(只读)、`onAction(KeyAction): List<CaptureCommand>`、`onSegmentTimerFired()`、`onVideoFinalized(rollToNext: Boolean)`、`onAudioFinalized()`、`onFailure(message)` — 全部返回命令表
  - `KeyAction.ASK_AGENT`(排 NONE 之前);`actionLabel(ASK_AGENT) = "Ask agent"`

- [ ] **Step 1: KeyAction 与 Labels 追加**

`keymap/KeyAction.kt` 在 `NONE,` 之前插入一行 `ASK_AGENT,`。
`ui/Labels.kt` 的 `actionLabel` when 中加 `KeyAction.ASK_AGENT -> "Ask agent"`。

- [ ] **Step 2: 写失败测试 `CaptureCoreTest.kt`**

```kotlin
package com.benzn.grandtime.capture

import com.benzn.grandtime.keymap.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCoreTest {

    private var idCounter = 0
    private fun core() = CaptureCore(clock = { 1000L }, newId = { "id${idCounter++}" })

    @Test
    fun `idle video starts segment 1 and enters RecordingVideo`() {
        val c = core()
        val cmds = c.onAction(KeyAction.START_STOP_VIDEO)
        assertTrue(cmds.contains(CaptureCommand.StartVideoSegment("id0", 1)))
        assertEquals(CaptureState.RecordingVideo("id0", 1, 1000L), c.state)
    }

    @Test
    fun `video key while recording stops without roll then finalize returns to idle`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val stopCmds = c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(rollToNext = false)), stopCmds)
        val doneCmds = c.onVideoFinalized(rollToNext = false)
        assertEquals(CaptureState.Idle, c.state)
        assertTrue(doneCmds.any { it is CaptureCommand.Notify })
    }

    @Test
    fun `photo during video uses same session and does not change state`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val cmds = c.onAction(KeyAction.TAKE_PHOTO)
        assertTrue(cmds.contains(CaptureCommand.TakePhoto("id0")))
        assertTrue(c.state is CaptureState.RecordingVideo)
    }

    @Test
    fun `photo during audio uses same session`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_AUDIO)
        val cmds = c.onAction(KeyAction.TAKE_PHOTO)
        assertTrue(cmds.contains(CaptureCommand.TakePhoto("id0")))
        assertTrue(c.state is CaptureState.RecordingAudio)
    }

    @Test
    fun `audio key during video is rejected with double vibrate`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val cmds = c.onAction(KeyAction.START_STOP_AUDIO)
        assertTrue(cmds.contains(CaptureCommand.Vibrate(2)))
        assertTrue(cmds.none { it is CaptureCommand.StartAudio })
        assertTrue(c.state is CaptureState.RecordingVideo)
    }

    @Test
    fun `video key during audio is rejected`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_AUDIO)
        val cmds = c.onAction(KeyAction.START_STOP_VIDEO)
        assertTrue(cmds.contains(CaptureCommand.Vibrate(2)))
        assertTrue(c.state is CaptureState.RecordingAudio)
    }

    @Test
    fun `segment timer rolls to next segment with same session`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopVideo(rollToNext = true)), c.onSegmentTimerFired())
        val rollCmds = c.onVideoFinalized(rollToNext = true)
        assertTrue(rollCmds.contains(CaptureCommand.StartVideoSegment("id0", 2)))
        assertEquals(CaptureState.RecordingVideo("id0", 2, 1000L), c.state)
    }

    @Test
    fun `segment timer in idle is a no-op`() {
        assertTrue(core().onSegmentTimerFired().isEmpty())
    }

    @Test
    fun `standalone photos get fresh sessions`() {
        val c = core()
        val first = c.onAction(KeyAction.TAKE_PHOTO)
        val second = c.onAction(KeyAction.TAKE_PHOTO)
        assertTrue(first.contains(CaptureCommand.TakePhoto("id0")))
        assertTrue(second.contains(CaptureCommand.TakePhoto("id1")))
        assertEquals(CaptureState.Idle, c.state)
    }

    @Test
    fun `torch and volume pass through in any state`() {
        val c = core()
        assertTrue(c.onAction(KeyAction.TOGGLE_TORCH).contains(CaptureCommand.ToggleTorch))
        c.onAction(KeyAction.START_STOP_VIDEO)
        assertTrue(c.onAction(KeyAction.TOGGLE_TORCH).contains(CaptureCommand.ToggleTorch))
        assertTrue(c.onAction(KeyAction.ADJUST_VOLUME).contains(CaptureCommand.CycleVolume))
    }

    @Test
    fun `audio stop then finalize returns to idle`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_AUDIO)
        assertEquals(listOf<CaptureCommand>(CaptureCommand.StopAudio), c.onAction(KeyAction.START_STOP_AUDIO))
        c.onAudioFinalized()
        assertEquals(CaptureState.Idle, c.state)
    }

    @Test
    fun `failure resets to idle with double vibrate and message`() {
        val c = core()
        c.onAction(KeyAction.START_STOP_VIDEO)
        val cmds = c.onFailure("Camera unavailable")
        assertEquals(CaptureState.Idle, c.state)
        assertTrue(cmds.contains(CaptureCommand.Vibrate(2)))
        assertTrue(cmds.contains(CaptureCommand.Notify("Camera unavailable")))
    }

    @Test
    fun `unhandled actions produce no commands`() {
        assertTrue(core().onAction(KeyAction.SEND_SOS).isEmpty())
        assertTrue(core().onAction(KeyAction.ASK_AGENT).isEmpty())
    }
}
```

- [ ] **Step 3: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 4: 实现**

`capture/CaptureState.kt`:

```kotlin
package com.benzn.grandtime.capture

sealed interface CaptureState {
    data object Idle : CaptureState
    data class RecordingVideo(val sessionId: String, val segmentIndex: Int, val startedAtMillis: Long) : CaptureState
    data class RecordingAudio(val sessionId: String, val startedAtMillis: Long) : CaptureState
}
```

`capture/CaptureCore.kt`:

```kotlin
package com.benzn.grandtime.capture

import com.benzn.grandtime.keymap.KeyAction

sealed interface CaptureCommand {
    data class StartVideoSegment(val sessionId: String, val segmentIndex: Int) : CaptureCommand
    data class StopVideo(val rollToNext: Boolean) : CaptureCommand
    data class TakePhoto(val sessionId: String) : CaptureCommand
    data class StartAudio(val sessionId: String) : CaptureCommand
    data object StopAudio : CaptureCommand
    data object ToggleTorch : CaptureCommand
    data object CycleVolume : CaptureCommand
    data class Vibrate(val times: Int) : CaptureCommand
    data class Notify(val text: String) : CaptureCommand
}

/**
 * 纯决策核:spec §2 互斥表的唯一实现。无 Android 依赖;
 * 调用方(CaptureManager)负责单线程串行调用。
 */
class CaptureCore(
    private val clock: () -> Long,
    private val newId: () -> String,
) {
    var state: CaptureState = CaptureState.Idle
        private set

    fun onAction(action: KeyAction): List<CaptureCommand> = when (action) {
        KeyAction.START_STOP_VIDEO -> when (state) {
            is CaptureState.Idle -> {
                val session = newId()
                state = CaptureState.RecordingVideo(session, 1, clock())
                listOf(
                    CaptureCommand.StartVideoSegment(session, 1),
                    CaptureCommand.Vibrate(1),
                    CaptureCommand.Notify("Recording video"),
                )
            }
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(rollToNext = false))
            is CaptureState.RecordingAudio -> listOf(
                CaptureCommand.Vibrate(2),
                CaptureCommand.Notify("Stop audio recording first"),
            )
        }
        KeyAction.TAKE_PHOTO -> when (val s = state) {
            is CaptureState.Idle -> listOf(CaptureCommand.TakePhoto(newId()), CaptureCommand.Vibrate(1))
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.TakePhoto(s.sessionId), CaptureCommand.Vibrate(1))
            is CaptureState.RecordingAudio -> listOf(CaptureCommand.TakePhoto(s.sessionId), CaptureCommand.Vibrate(1))
        }
        KeyAction.START_STOP_AUDIO -> when (state) {
            is CaptureState.Idle -> {
                val session = newId()
                state = CaptureState.RecordingAudio(session, clock())
                listOf(
                    CaptureCommand.StartAudio(session),
                    CaptureCommand.Vibrate(1),
                    CaptureCommand.Notify("Recording audio"),
                )
            }
            is CaptureState.RecordingAudio -> listOf(CaptureCommand.StopAudio)
            is CaptureState.RecordingVideo -> listOf(
                CaptureCommand.Vibrate(2),
                CaptureCommand.Notify("Stop video recording first"),
            )
        }
        KeyAction.TOGGLE_TORCH -> listOf(CaptureCommand.ToggleTorch)
        KeyAction.ADJUST_VOLUME -> listOf(CaptureCommand.CycleVolume)
        else -> emptyList()
    }

    fun onSegmentTimerFired(): List<CaptureCommand> = when (state) {
        is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(rollToNext = true))
        else -> emptyList()
    }

    fun onVideoFinalized(rollToNext: Boolean): List<CaptureCommand> = when (val s = state) {
        is CaptureState.RecordingVideo ->
            if (rollToNext) {
                val next = s.segmentIndex + 1
                state = CaptureState.RecordingVideo(s.sessionId, next, clock())
                listOf(CaptureCommand.StartVideoSegment(s.sessionId, next))
            } else {
                state = CaptureState.Idle
                listOf(CaptureCommand.Vibrate(1), CaptureCommand.Notify("Standing by"))
            }
        else -> emptyList()
    }

    fun onAudioFinalized(): List<CaptureCommand> {
        state = CaptureState.Idle
        return listOf(CaptureCommand.Vibrate(1), CaptureCommand.Notify("Standing by"))
    }

    fun onFailure(message: String): List<CaptureCommand> {
        state = CaptureState.Idle
        return listOf(CaptureCommand.Vibrate(2), CaptureCommand.Notify(message))
    }
}
```

- [ ] **Step 5: 跑测试确认通过** — `./gradlew test` → PASS(新 13 个,既有全绿——KeyMappingTest 等不受 ASK_AGENT 影响)。

- [ ] **Step 6: Commit** — `git add app/src && git commit -m "feat: capture state machine (mutex table) and ASK_AGENT placeholder action"`

---

### Task 5: 采集执行层胶水(CameraX/MediaRecorder/手电/音量)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/ServiceLifecycleOwner.kt`、`.../capture/CameraSession.kt`、`.../capture/VideoRecorder.kt`、`.../capture/PhotoTaker.kt`、`.../capture/AudioRecorder.kt`、`.../capture/TorchController.kt`、`.../capture/VolumeCycler.kt`

**Interfaces:**
- Consumes: `VideoQuality/PhotoQuality`(core 包)
- Produces(Task 6 使用,签名精确):
  - `ServiceLifecycleOwner : LifecycleOwner`(常驻 RESUMED;`destroy()`)
  - `CameraSession(context, lifecycleOwner)`:`suspend bindForVideo(quality: VideoQuality, jpegQuality: Int)`、`suspend bindForPhoto(jpegQuality: Int): ImageCapture`、`suspend unbind()`、属性 `camera/videoCapture/imageCapture/photoDuringVideoSupported`
  - `VideoRecorder(context)`:`startSegment(videoCapture, file, onFinalized: (error: Boolean, message: String?) -> Unit)`、`stop()`、`isRecording`
  - `PhotoTaker(context)`:`take(imageCapture, file, onDone: (success: Boolean) -> Unit)`
  - `AudioRecorder(context)`:`start(file): Boolean`、`stop(): Boolean`、`isRecording`
  - `TorchController(context, session)`:`toggle()`、`torchOn`
  - `VolumeCycler(context)`:`cycle(): Int`(返回百分比)

- [ ] **Step 1: `capture/ServiceLifecycleOwner.kt`**

```kotlin
package com.benzn.grandtime.capture

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** 常驻 RESUMED 的 LifecycleOwner:CameraX 绑定不依赖 Activity(spec §2 无预览后台采集)。 */
class ServiceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
```

- [ ] **Step 2: `capture/CameraSession.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import com.benzn.grandtime.core.VideoQuality
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX 绑定管理。录像时尝试 Video+Image 双用例并绑(录像中拍照),
 * 设备拒绝则降级只绑 Video 并记 photoDuringVideoSupported=false(spec §2)。
 * 所有方法须在主线程调用。
 */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: ServiceLifecycleOwner,
) {
    var camera: Camera? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var photoDuringVideoSupported: Boolean = true
        private set

    private var provider: ProcessCameraProvider? = null

    private suspend fun provider(): ProcessCameraProvider =
        provider ?: suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }.also { provider = it }

    private fun qualitySelector(quality: VideoQuality): QualitySelector {
        val target = when (quality) {
            VideoQuality.P1080 -> Quality.FHD
            VideoQuality.P720 -> Quality.HD
            VideoQuality.P480 -> Quality.SD
        }
        return QualitySelector.from(target, FallbackStrategy.higherQualityOrLowerThan(target))
    }

    suspend fun bindForVideo(quality: VideoQuality, jpegQuality: Int): VideoCapture<Recorder> {
        val p = provider()
        p.unbindAll()
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector(quality)).build()
        val video = VideoCapture.withOutput(recorder)
        val image = ImageCapture.Builder().setJpegQuality(jpegQuality).build()
        try {
            camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, video, image)
            imageCapture = image
            photoDuringVideoSupported = true
        } catch (e: IllegalArgumentException) {
            camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, video)
            imageCapture = null
            photoDuringVideoSupported = false
        }
        videoCapture = video
        return video
    }

    suspend fun bindForPhoto(jpegQuality: Int): ImageCapture {
        val p = provider()
        p.unbindAll()
        val image = ImageCapture.Builder().setJpegQuality(jpegQuality).build()
        camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, image)
        videoCapture = null
        imageCapture = image
        return image
    }

    suspend fun unbind() {
        provider().unbindAll()
        camera = null
        videoCapture = null
        imageCapture = null
    }
}
```

- [ ] **Step 3: `capture/VideoRecorder.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File

/** 单段录像的启停。分段调度由 CaptureManager 的定时器驱动。 */
class VideoRecorder(private val context: Context) {

    private var active: Recording? = null
    val isRecording: Boolean get() = active != null

    @SuppressLint("MissingPermission") // 调用前 CaptureManager 已做运行时权限 preflight
    fun startSegment(
        videoCapture: VideoCapture<Recorder>,
        file: File,
        onFinalized: (error: Boolean, message: String?) -> Unit,
    ) {
        val pending = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        val prepared = if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) pending.withAudioEnabled() else pending

        active = prepared.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                active = null
                onFinalized(event.hasError(), if (event.hasError()) "Video error ${event.error}" else null)
            }
        }
    }

    fun stop() {
        active?.stop()
    }
}
```

- [ ] **Step 4: `capture/PhotoTaker.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File

class PhotoTaker(private val context: Context) {

    fun take(imageCapture: ImageCapture, file: File, onDone: (success: Boolean) -> Unit) {
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onDone(true)
                override fun onError(exception: ImageCaptureException) = onDone(false)
            },
        )
    }
}
```

- [ ] **Step 5: `capture/AudioRecorder.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** 纯录音:AAC/M4A 128kbps 44.1kHz 单声道(spec §3)。 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    val isRecording: Boolean get() = recorder != null

    fun start(file: File): Boolean = try {
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        recorder?.release()
        recorder = null
        false
    }

    fun stop(): Boolean = try {
        recorder?.apply { stop(); release() }
        recorder = null
        true
    } catch (e: Exception) {
        recorder?.release()
        recorder = null
        false
    }
}
```

- [ ] **Step 6: `capture/TorchController.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** 手电:相机已绑定时走 CameraControl,空闲时走 CameraManager.setTorchMode。 */
class TorchController(
    private val context: Context,
    private val session: CameraSession,
) {
    var torchOn: Boolean = false
        private set

    fun toggle() {
        torchOn = !torchOn
        val camera = session.camera
        if (camera != null && camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(torchOn)
            return
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backWithFlash = cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (backWithFlash == null) {
            torchOn = !torchOn
            return
        }
        try {
            cm.setTorchMode(backWithFlash, torchOn)
        } catch (e: Exception) {
            torchOn = !torchOn
        }
    }
}
```

- [ ] **Step 7: `capture/VolumeCycler.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioManager

/** 媒体音量循环调档:25% → 50% → 75% → 100% → 25%(SMART-PTT 惯例)。 */
class VolumeCycler(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun cycle(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val steps = listOf(max / 4, max / 2, max * 3 / 4, max).filter { it > 0 }.distinct()
        val next = steps.firstOrNull { it > current } ?: steps.first()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
        return next * 100 / max
    }
}
```

- [ ] **Step 8: 构建 + Commit**

```bash
./gradlew assembleDebug && ./gradlew test
git add app/src && git commit -m "feat: camera session, recorders, torch and volume glue"
```

---

### Task 6: CaptureManager + CoreService 接线

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/core/AppState.kt`(+captureState)、`app/src/main/java/com/benzn/grandtime/service/CoreService.kt`(handler 换装)

**Interfaces:**
- Consumes: Task 2-5 全部产出;`SettingsStore/settingsDataStore`;`AppState`
- Produces: `CaptureManager(context, scope, settingsStore, dao, notify: (String) -> Unit, probe: (String) -> Unit)`:`val handledActions: Set<KeyAction>`、`fun handle(action: KeyAction)`、`fun shutdown()`;`AppState.captureState: MutableStateFlow<CaptureState>`。

- [ ] **Step 1: `core/AppState.kt` 增补**

import 加 `com.benzn.grandtime.capture.CaptureState`;object 内加:

```kotlin
    /** 采集状态(Service 写,Home 卡读)。 */
    val captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
```

- [ ] **Step 2: `capture/CaptureManager.kt`**

```kotlin
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
        for (cmd in commands) when (cmd) {
            is CaptureCommand.StartVideoSegment -> startVideoSegment(cmd)
            is CaptureCommand.StopVideo -> {
                segmentTimer?.cancel()
                pendingRoll = cmd.rollToNext
                video.stop()
            }
            is CaptureCommand.TakePhoto -> takePhoto(cmd)
            is CaptureCommand.StartAudio -> startAudio(cmd)
            CaptureCommand.StopAudio -> stopAudio()
            CaptureCommand.ToggleTorch -> {
                torch.toggle()
                probe("torch ${if (torch.torchOn) "on" else "off"}")
            }
            CaptureCommand.CycleVolume -> probe("volume ${volume.cycle()}%")
            is CaptureCommand.Vibrate -> vibrate(cmd.times)
            is CaptureCommand.Notify -> notify(cmd.text)
        }
        AppState.captureState.value = core.state
    }

    private suspend fun startVideoSegment(cmd: CaptureCommand.StartVideoSegment) {
        val settings = settingsStore.settings.first()
        val videoCapture = session.videoCapture
            ?: try {
                session.bindForVideo(settings.videoQuality, jpegQuality(settings.photoQuality))
            } catch (e: Exception) {
                execute(core.onFailure("Camera unavailable")); return
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
                if (core.state is CaptureState.Idle && !video.isRecording) session.unbind()
            }
        }
    }

    private suspend fun startAudio(cmd: CaptureCommand.StartAudio) {
        val file = storage.newFile(MediaStorage.Kind.AUDIO)
        val startedAt = System.currentTimeMillis()
        if (!audio.start(file)) {
            execute(core.onFailure("Audio recorder unavailable"))
            return
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
```

- [ ] **Step 3: `service/CoreService.kt` 接线(改动限于以下三处)**

(a) 字段区加:

```kotlin
    private var captureManager: CaptureManager? = null
```

(b) `startPipeline()` 里、构造 dispatcher 之前加:

```kotlin
        captureManager = CaptureManager(
            context = this,
            scope = lifecycleScope,
            settingsStore = SettingsStore(applicationContext.settingsDataStore),
            dao = CaptureDb.get(applicationContext).captureRecords(),
            notify = ::notifyStatus,
            probe = ::probe,
        )
```

(c) `handleAction` 整体替换为:

```kotlin
    private fun handleAction(action: KeyAction, press: KeyPress) {
        probe("${press.key.name} ${press.pressType.name} → ${action.name}")
        val manager = captureManager
        if (manager != null && action in manager.handledActions) {
            manager.handle(action)
        } else {
            val text = when (action) {
                KeyAction.ASK_AGENT -> "Ask agent coming soon"
                else -> "[stub] ${actionLabel(action)}"
            }
            AppState.lastAction.value = text
            notifyStatus(text)
        }
    }
```

(d) `onDestroy` 里在 `f2spSource?.stop()` 前加 `captureManager?.shutdown()`。
新增 import:`com.benzn.grandtime.capture.CaptureManager`、`com.benzn.grandtime.core.SettingsStore`、`com.benzn.grandtime.core.settingsDataStore`、`com.benzn.grandtime.db.CaptureDb`。

- [ ] **Step 4: 构建 + 全量测试**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: 双 BUILD SUCCESSFUL。

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "feat: capture manager wired into key pipeline (video/photo/audio/torch/volume live)"`

---

### Task 7: 权限向导 + Home 录制卡

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`(权限部分)、`app/src/main/java/com/benzn/grandtime/ui/HomeScreen.kt`(重写)

**Interfaces:**
- Consumes: `AppState.captureState`、`CaptureState`、FsCard/FsCardTitle、LocalFsColors(warningText/successDot/textTertiary)

- [ ] **Step 1: MainActivity 权限流替换**

删除原 `notifPermission` 字段与 onCreate 的权限分支,替换为:

```kotlin
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startCore()
            maybeRequestOverlay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FieldSightTheme { MainScaffold() } }

        val required = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (required.isEmpty()) {
            startCore()
            maybeRequestOverlay()
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    private fun maybeRequestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Allow \"Display over other apps\" so physical keys can record while the screen is off",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }
```

新增 import:`android.net.Uri`、`android.provider.Settings`、`android.widget.Toast`。`startCore()` 不变。

- [ ] **Step 2: 重写 `ui/HomeScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.ui.theme.LocalFsColors
import kotlinx.coroutines.delay

@Composable
fun HomeScreen() {
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    val login by AppState.loginState.collectAsStateWithLifecycle()
    val capture by AppState.captureState.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current
    val context = LocalContext.current

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(capture) {
        while (capture !is CaptureState.Idle) {
            nowMillis = System.currentTimeMillis()
            delay(1000)
        }
    }

    var setupComplete by remember { mutableStateOf(isSetupComplete(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) setupComplete = isSetupComplete(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        FsCard {
            FsCardTitle("Device")
            val (dotColor, statusText) = when (val s = capture) {
                is CaptureState.RecordingVideo ->
                    MaterialTheme.colorScheme.error to "Recording ${mmss(nowMillis - s.startedAtMillis)}"
                is CaptureState.RecordingAudio ->
                    MaterialTheme.colorScheme.error to "Recording audio ${mmss(nowMillis - s.startedAtMillis)}"
                CaptureState.Idle ->
                    (if (running) fs.successDot else MaterialTheme.colorScheme.outline) to
                        (if (running) "Standing by" else "Service stopped")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(10.dp))
                Text(statusText, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (val s = login) {
                    is LoginState.LoggedIn -> "Signed in as ${s.displayName}"
                    LoginState.LoggedOut -> "Not signed in"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!setupComplete) {
            Spacer(Modifier.height(12.dp))
            FsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openSetup(context) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tap to finish setup — permissions missing",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = fs.warningText,
                    )
                }
            }
        }
    }
}

private fun mmss(elapsedMillis: Long): String {
    val total = (elapsedMillis / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun isSetupComplete(context: Context): Boolean {
    val camera = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    return camera && mic && Settings.canDrawOverlays(context)
}

private fun openSetup(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    } else {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }
}
```

- [ ] **Step 3: 构建 + 测试 + Commit**

```bash
./gradlew assembleDebug && ./gradlew test
git add app/src && git commit -m "feat: permission wizard (camera/mic/overlay) and live recording card"
```

---

### Task 8: FilesScreen 从 DB 渲染

**Files:**
- Rewrite: `app/src/main/java/com/benzn/grandtime/ui/FilesScreen.kt`

**Interfaces:**
- Consumes: `CaptureDb/CaptureRecordDao/CaptureRecord/FilesReconciler`(Task 2)、FsCard、LocalFsColors、`R.drawable.ic_nav_files`

- [ ] **Step 1: 重写 `ui/FilesScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.R
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.db.CaptureRecord
import com.benzn.grandtime.db.FilesReconciler
import com.benzn.grandtime.ui.theme.LocalFsColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MediaFilter(val label: String, val kind: String?) {
    ALL("All", null),
    VIDEO("Video", "video"),
    AUDIO("Audio", "audio"),
    PHOTO("Photo", "photo"),
}

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val dao = remember { CaptureDb.get(context.applicationContext).captureRecords() }
    var filter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    val records by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val fs = LocalFsColors.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            FilesReconciler(dao, durationReader = ::readDurationMillis).reconcile(scanDisk(context))
        }
    }

    val filtered = filter.kind?.let { k -> records.filter { it.kind == k } } ?: records

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            MediaFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                )
            }
        }
        if (filtered.isEmpty()) {
            FsCard {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_nav_files),
                        contentDescription = null,
                        tint = fs.textTertiary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No recordings yet", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Recordings will appear here after you record",
                        style = MaterialTheme.typography.bodySmall,
                        color = fs.textTertiary,
                    )
                }
            }
        } else {
            val grouped = filtered.groupBy { dayLabel(it.startedAt) }
            LazyColumn(Modifier.weight(1f)) {
                grouped.forEach { (day, dayItems) ->
                    item(key = "header-$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = fs.textTertiary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(dayItems, key = { it.id }) { record -> MediaRow(record) }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(record: CaptureRecord) {
    val fs = LocalFsColors.current
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                record.kind.first().uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            record.fileName,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        record.durationMs?.let { d ->
            Text(mmssLabel(d), style = MaterialTheme.typography.bodySmall, color = fs.textTertiary)
            Spacer(Modifier.width(8.dp))
        }
        Text(formatSize(record.sizeBytes), style = MaterialTheme.typography.bodySmall, color = fs.textTertiary)
    }
}

private fun scanDisk(context: Context): List<FilesReconciler.DiskFile> {
    val volumes = context.getExternalFilesDirs(null).filterNotNull()
    val root = if (volumes.size >= 2) volumes[1] else volumes.firstOrNull() ?: return emptyList()
    val dirs = mapOf("video" to "video", "audio" to "audio", "photo" to "photo")
    return dirs.flatMap { (dirName, kind) ->
        File(File(root, "media"), dirName).listFiles()?.filter { it.isFile }?.map { f ->
            FilesReconciler.DiskFile(f.absolutePath, f.name, kind, f.length(), f.lastModified())
        } ?: emptyList()
    }
}

private fun readDurationMillis(path: String): Long? = try {
    MediaMetadataRetriever().use { r ->
        r.setDataSource(path)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }
} catch (e: Exception) {
    null
}

private fun mmssLabel(durationMs: Long): String {
    val total = durationMs / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun dayLabel(millis: Long): String {
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
    val display = SimpleDateFormat("d MMM yyyy", Locale.US)
    return if (dayKey.format(Date(millis)) == dayKey.format(Date())) "Today" else display.format(Date(millis))
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
```

(注:旧文件里的 `MediaEntry`/`scanMedia` 被上述内容整体替代;`MediaFilter` 保留但语义改为 kind 过滤。)

- [ ] **Step 2: 构建 + 测试 + Commit**

```bash
./gradlew assembleDebug && ./gradlew test
git add app/src && git commit -m "feat: Files renders from Room with duration column and disk reconcile"
```

---

### Task 9: 真机端到端验收 + 标记

**Files:** 无新文件(发现缺陷按 TDD 修,fix commit)

设备:F2S202503103054(`ADB="C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe"`)。
安装:`export ANDROID_SERIAL=F2S202503103054 && ./gradlew installDebug`;打开 App 完成权限向导
(相机/麦克风弹窗 adb 点 Allow:uiautomator dump 找按钮;overlay 设置页用
`"$ADB" shell appops set com.benzn.grandtime SYSTEM_ALERT_WINDOW allow` 直接授权,再返回)。
每个场景前 `"$ADB" logcat -c`,证据 = `logcat -d -s GrandTime:I` + 截图 + Files 页。
物理按键用 adb 广播模拟(`lolaage.video1.down`+sleep+`.up` 等,SP1 已证与真按键等价)。

- [ ] **场景 1 录像起停**:短按录像 → 日志 `video segment 1 started` + 通知 Recording video;
  Home 卡红点计时(截图);再短按 → `video segment saved` + Standing by;Files 出现
  `VID_*.mp4` 行(时长/大小显示)。`"$ADB" shell ls` 核对文件存在且 >0 字节;pull 回本地
  用 ffprobe 或大小合理性判断可播性,并请用户在设备上点开播放确认
- [ ] **场景 2 息屏录像**:`"$ADB" shell input keyevent KEYCODE_POWER`(灭屏)→ 广播开录
  → 等 5s → 亮屏 → Home 显示 Recording(SAW 豁免硬验证)→ 停止
- [ ] **场景 3 分段**:Settings 设 Segment length=1 min(uiautomator 驱动)→ 开录 →
  等 150s → 停止 → Files 出 3 段(≈60/60/30s),日志有两次 `segment 2/3 started`;
  改回 5 min
- [ ] **场景 4 录像中拍照**:开录 → 拍照键短按 → 日志 `photo saved` 且录像未停(通知仍
  Recording…)→ 停止;DB 里照片行与录像段 sessionId 相同:
  `"$ADB" exec-out run-as com.benzn.grandtime sqlite3 databases/capture.db "SELECT kind,sessionId FROM capture_records ORDER BY createdAt DESC LIMIT 5;"`
  (若 run-as/sqlite3 不可用,退化为 Diagnostics 日志核对 session 前缀)。
  若设备不支持双用例并绑:确认降级提示 "Photo during video not supported…" 并记入报告
- [ ] **场景 5 拍照/录音/手电/音量**:各单独验证——IMG 落盘;AUD 起停+时长;手电亮灭
  (请用户目视确认);音量循环(日志 volume xx%)
- [ ] **场景 6 互斥**:录像中按录音 → 忽略+`Stop video recording first`;录音中按录像同理;
  录音中拍照 → 成功且录音不断
- [ ] **场景 7 重启链路**:reboot → 待命 → 直接广播开录成功(开机自启路径下豁免与相机可用)
- [ ] **场景 8 设置生效**:改 480P → 录一段 → pull 文件用大小/元数据核对分辨率变化
- [ ] **场景 9 ASK_AGENT 占位**:Key bindings 把 Audio·short 绑 "Ask agent" → 广播短按 →
  通知 "Ask agent coming soon" → 恢复默认
- [ ] **收尾**:`./gradlew test` 全绿;`git tag sp3a-accepted`;报告逐场景 expected vs actual

---

## 验证总览

| 层次 | 手段 | 覆盖 |
|---|---|---|
| JVM 单测 | `./gradlew test` | CaptureCore 互斥表 13 例、MediaStorage 5 例、FilesReconciler 3 例 + 既有 48 个 |
| 真机 | Task 9 九场景 | CameraX 双用例/分段/息屏豁免/DB/UI 联动全链路 |

## 风险与缓解

- **双用例并绑被拒**(录像中拍照)→ CameraSession 自动降级 + 明确提示;验收场景 4 记录实况,SP3b 再评估抓帧方案
- **CameraX 在厂商 ROM 的兼容性**→ 出错路径全部回 IDLE+通知,不崩溃;Diagnostics 日志排障
- **MediaRecorder stop() 过快抛异常**(录音 <1s)→ AudioRecorder.stop 已 try-catch,文件按实际内容保留
- **Room/KSP 首次构建慢**→ 正常,一次性
- **权限弹窗自动化不稳**→ appops 命令兜底(SYSTEM_ALERT_WINDOW);运行时权限可 `pm grant`
