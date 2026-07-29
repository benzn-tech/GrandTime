# SP3a.1 抓帧 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 720P/1080P 录像中按拍照键 = 立即滚段 + 从刚收尾的段抽出按键时刻的帧存 JPEG(codec='frame-grab',同 session),默认画质保持 1080P。

**Architecture:** CaptureCore 零改动。CaptureManager 的 takePhoto 高画质分支从「提示不支持」改为发起抓帧:记录按键时刻与当前段信息 → 复用 onSegmentTimerFired 滚段 → 段 finalize 后在 IO 线程用 MediaMetadataRetriever 抽帧压 JPEG 落盘 + DB。新增薄类 FrameGrabber(偏移计算为纯函数,JVM 可测)。

**Tech Stack:** 既有栈,无新依赖。

Spec:`docs/superpowers/specs/2026-07-11-sp3a-capture-design.md` §2.5(2026-07-12 增补)

## Global Constraints

- 分支:`feature/sp3a-capture`(接在既有 SP3a 提交后)
- 480P 双绑传感器拍照路径与录音中拍照路径**不动**;抓帧仅接管「录像中且 session.imageCapture == null」分支
- DB 行:kind='photo'、codec='frame-grab'、resolution=当前视频档字符串、sessionId=录像 session、startedAt=endedAt=按键时刻
- 抓帧进行中再按拍照 → 忽略+震两下(防抖,单挂起槽)
- 设备命令一律 `-s F2S202503103054`;JAVA_HOME 前缀同前;Dropbox 锁重跑一次
- Commit footer:

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014TCKsgA4JjaWbbFD4zY7zh
```

---

### Task 1: FrameGrabber + Manager 抓帧接线(偏移计算 TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/FrameGrabber.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`(仅 takePhoto 高画质分支 + 新增字段/两个私有方法 + finalize 回调一处)
- Test: `app/src/test/java/com/benzn/grandtime/capture/FrameGrabberTest.kt`

**Interfaces:**
- Consumes: `MediaStorage.newFile(Kind.PHOTO, startMillis)`、`CaptureRecordDao.insert`、`CaptureCore.onSegmentTimerFired()`、既有 `currentVideoFile/currentVideoStartedAt`、`jpegQuality()/resolutionString()`
- Produces: `FrameGrabber.grab(videoFile, offsetMillis, dest, jpegQuality): Long?`;`FrameGrabber.frameOffsetMillis(requestedAt, segmentStartedAt): Long`(纯函数)

- [ ] **Step 1: 写失败测试 `FrameGrabberTest.kt`**

```kotlin
package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGrabberTest {

    @Test
    fun `offset is press time minus segment start`() {
        assertEquals(5_500L, FrameGrabber.frameOffsetMillis(10_500L, 5_000L))
    }

    @Test
    fun `offset clamps negative to zero`() {
        assertEquals(0L, FrameGrabber.frameOffsetMillis(4_000L, 5_000L))
    }

    @Test
    fun `offset zero when pressed exactly at segment start`() {
        assertEquals(0L, FrameGrabber.frameOffsetMillis(5_000L, 5_000L))
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL(unresolved FrameGrabber),粘 RAW 尾部。

- [ ] **Step 3: 实现 `capture/FrameGrabber.kt`**

```kotlin
package com.benzn.grandtime.capture

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

/** 从已收尾的视频段按时间偏移抽帧存 JPEG(spec §2.5 高画质录像中拍照)。 */
class FrameGrabber {

    /** @return 写出的 JPEG 字节数;抽帧或写盘失败返回 null。 */
    fun grab(videoFile: File, offsetMillis: Long, dest: File, jpegQuality: Int): Long? = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(videoFile.absolutePath)
            val frame: Bitmap? = retriever.getFrameAtTime(
                offsetMillis.coerceAtLeast(0) * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST,
            )
            frame?.let { bitmap ->
                FileOutputStream(dest).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                }
                bitmap.recycle()
                dest.length()
            }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        /** 按键时刻相对段起点的偏移;段起点之前按下(时钟偏差)归零。 */
        fun frameOffsetMillis(requestedAtMillis: Long, segmentStartedAtMillis: Long): Long =
            (requestedAtMillis - segmentStartedAtMillis).coerceAtLeast(0)
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(新 3 个),粘 RAW 尾部。

- [ ] **Step 5: CaptureManager 接线(只动以下四处)**

(a) 字段区加:

```kotlin
    private val frameGrabber = FrameGrabber()

    private data class PendingFrameGrab(
        val requestedAtMillis: Long,
        val segmentStartedAtMillis: Long,
        val segmentFile: File,
        val sessionId: String,
        val resolution: String?,
    )

    private var pendingFrameGrab: PendingFrameGrab? = null
```

(b) `takePhoto` 中 recordingVideo 分支:把 `session.imageCapture ?: run { notify("Photo during video not supported on this device"); vibrate(2); return }` 整体替换为:

```kotlin
            recordingVideo -> session.imageCapture ?: run {
                initiateFrameGrab(cmd)
                return
            }
```

(c) 新增两个私有方法:

```kotlin
    /** 720P/1080P 录像中拍照:立即滚段,段收尾后抽帧(spec §2.5)。 */
    private suspend fun initiateFrameGrab(cmd: CaptureCommand.TakePhoto) {
        if (pendingFrameGrab != null) {
            vibrate(2)
            probe("frame-grab ignored: previous grab pending")
            return
        }
        val segmentFile = currentVideoFile ?: run { vibrate(2); return }
        val settings = settingsStore.settings.first()
        pendingFrameGrab = PendingFrameGrab(
            requestedAtMillis = System.currentTimeMillis(),
            segmentStartedAtMillis = currentVideoStartedAt,
            segmentFile = segmentFile,
            sessionId = cmd.sessionId,
            resolution = settings.videoQuality.resolutionString(),
        )
        probe("frame-grab requested: rolling segment early")
        execute(core.onSegmentTimerFired())
    }

    private suspend fun performFrameGrab(pending: PendingFrameGrab) {
        val settings = settingsStore.settings.first()
        val dest = storage.newFile(MediaStorage.Kind.PHOTO, pending.requestedAtMillis)
        val offset = FrameGrabber.frameOffsetMillis(pending.requestedAtMillis, pending.segmentStartedAtMillis)
        val size = frameGrabber.grab(pending.segmentFile, offset, dest, jpegQuality(settings.photoQuality))
        if (size != null) {
            dao.insert(
                CaptureRecord(
                    id = UUID.randomUUID().toString(),
                    kind = "photo",
                    filePath = dest.absolutePath,
                    fileName = dest.name,
                    startedAt = pending.requestedAtMillis,
                    endedAt = pending.requestedAtMillis,
                    sizeBytes = size,
                    codec = "frame-grab",
                    resolution = pending.resolution,
                    sessionId = pending.sessionId,
                    createdAt = System.currentTimeMillis(),
                )
            )
            notify("Photo saved")
            probe("frame-grab saved: ${dest.name} ($size bytes, offset ${offset}ms)")
        } else {
            notify("Photo failed")
            vibrate(2)
            probe("frame-grab failed for ${pending.segmentFile.name}")
        }
    }
```

(d) `startVideoSegment` 的 finalize 回调里,`execute(core.onVideoFinalized(roll))` 之后、`if (core.state is CaptureState.Idle) session.unbind()` 之前插入:

```kotlin
                    pendingFrameGrab?.let { pending ->
                        pendingFrameGrab = null
                        launch(kotlinx.coroutines.Dispatchers.IO) { performFrameGrab(pending) }
                    }
```

(import 区补 `kotlinx.coroutines.Dispatchers` 后 (d) 中可写 `Dispatchers.IO`。)

- [ ] **Step 6: 构建 + 全量测试 + Commit**

```bash
./gradlew assembleDebug && ./gradlew test
git add app/src && git commit -m "feat: frame-grab photo during high-quality video (early segment roll)"
```

---

### Task 2: 真机验收(抓帧四场景)+ 标记

**Files:** 无(缺陷才有 fix commit)

设备 F2S202503103054;`export ANDROID_SERIAL=F2S202503103054 && ./gradlew installDebug`,
force-stop + am start 起新进程;每场景前 `adb logcat -c`;证据 = logcat GrandTime 行 +
文件列表 + `run-as … sqlite3`(不可用则 logcat)。物理键用 lolaage 广播模拟。

- [ ] **场景 A 1080P 抓帧**:确认 Settings=1080P → 开录 → 等 5s →