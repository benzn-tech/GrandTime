# SP-Capture2-P3:GPS 水印 + 轨迹 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans。Steps use checkbox (`- [ ]`) syntax。
>
> **跨两仓**:GrandTime(`C:/Users/camil/Dropbox/GrandTime`)+ fieldsight-pipeline(`C:/Users/camil/Dropbox/fieldsight-pipeline`,后端 gps_track 入库)。媒体/GL/GPS 类**无法 JVM 单测**(需硬件),以"实现 + 真机验证"代替 TDD;纯逻辑(WatermarkContent/设置/后端)仍 TDD。**Task 1 是 GL 水印方向真机探针,先定生死再接正式路径。**

**Goal:** 给视频(GL 逐帧叠)与照片(Canvas 叠)烧录 4 行 GPS 水印(用户名/时间戳/经纬度/街道地址),用 `LocationManager` 采 GPS,段起点写 mp4 `setLocation`,逐秒 `gps_track` 上传后端 `recordings.gps_track`。

**Architecture:** 在 P2 的 Camera2+GL 管线上叠加:`GpsTracker`(LocationManager)供定位;`WatermarkRenderer` 把 4 行内容画到 Bitmap;`GlRecordPipeline` 加"文字纹理层"(2D alpha 着色器,叠相机帧底部,方向由 Task1 探针定);照片走 Canvas 叠同样内容;`CaptureManager` 编排(GPS 起停、~1Hz 刷水印、段起点 location、照片叠、gps_track 存 DB);上传 worker 把 gps_track 随 complete 上传;后端 `mark_uploaded` 写 jsonb 列。水印开关默认开,关时行为同 P2。

**Tech Stack:** Kotlin;Android framework:`android.location.LocationManager`/`Geocoder`、`android.graphics.Canvas`/`Paint`/`Bitmap`、`android.opengl`(GLES20 2D 纹理 + alpha 混合)。后端:Python/psycopg3/pytest。**不引原生库/新 Gradle 依赖。**

## Global Constraints

- **全 Android framework + 后端 Python**;不引原生库、不加 Gradle 依赖(armeabi)。
- **GPS 用 `LocationManager`(GPS_PROVIDER)**,不用 FusedLocation(F2SP 无 GMS 保证)。
- **水印 4 行**(缺则省略该行):`<用户名>` / `<YYYY-MM-DD HH:mm:ss>` / `<lat>, <lon>`(无定位="定位中…") / `<街道地址>`(Geocoder 反查,离线/失败省略)。
- **水印开关默认开**;关时视频不叠文字层、照片不叠、逻辑与 P2 完全一致(回归即失败)。
- **保住 P2 全部功能**:HEVC/AVC、4:3/16:9、分段、录像中拍照、预览挂摘不断录、息屏后台、手电、**音频轨**、SP3b 灯语、SP4 上传+siteId、照片精度降采样。
- **`gps_track` 是 jsonb**:移动端发 JSON 数组字符串;后端 `parse_body` 得 Python list → 以 `psycopg.types.json.Jsonb(list)` 写列(不是裸字符串)。格式 `[{"t":<epochMs>,"lat":<double>,"lon":<double>}, …]`。
- **complete 契约向后兼容**:`gpsTrack` 为**可选**新字段,旧客户端不传照常。
- 权限 `ACCESS_FINE_LOCATION` 未授权 → 录制照常,水印 GPS 行显"无定位权限",不阻断。
- 每阶段真机验收(对照 P2 验收 + 水印方向可读/照片带水印/gps_track 入库/setLocation 起点);过了合 main 推 GitHub + tag。

## 现有契约(P3 消费,来自勘察)

- `GlRecordPipeline`(P2):`start(cameraW,cameraH,onCameraTextureReady)`、`addTarget/removeTarget(Surface)`、`release()`;`drawFrame()` 画相机 OES 纹理到各目标 window。GL 线程亲和(自有 HandlerThread + handler)。
- `Camera2Pipeline`(P2):`startSegment(file, aspect, quality, hevcPreferred, location: Pair<Float,Float>?, onFinalized): SegmentResult?`(location 现传 null)、`takePhoto(file, jpegQuality): Boolean`、`setPreviewSurface(Surface?)`、`release()`。内部 `gl: GlRecordPipeline`。
- `SegmentRecorder.prepare(file, spec, hevcPreferred, location, recordAudio)`——已接受 location。
- `CaptureManager`(P2):`startVideoSegment`(pipeline.startSegment location=null)、`takePhoto`(pipeline.takePhoto + `downscaleJpeg` 含 EXIF 保留)、preview collector、`init` 有 `pipeline.onCameraLost` 接线。构造 `(context, scope, settingsStore, dao, notify, probe, uploadEnqueuer)`。
- `AppState.loginState: MutableStateFlow<LoginState>`;`LoginState.LoggedIn(displayName, authorSub)`。读名:`(AppState.loginState.value as? LoginState.LoggedIn)?.displayName`。
- `SettingsStore`/`RecordingSettings`(P2):有 `aspectRatio` 等;加 `watermarkEnabled`。`SettingsScreen` 有 `SettingRow`/`RadioDialog`/`SettingDialog` enum。
- `RecordingsApiClient.complete(idToken, recordingId, sizeBytes: Long?): Boolean`(`net/RecordingsApiClient.kt:106`);body 用 `JSONObject` + `?.let{put}`。
- `UploadWorker.doWork`(`upload/UploadWorker.kt:105`):`client.complete(idToken, urlResult.recordingId, file.length())`。
- `CaptureRecord`(`db/CaptureRecord.kt`):无 gps 列;`CaptureDb`(Room)现版本 2(SP4b 迁移 1→2)。
- `MainActivity`(`ui/MainActivity.kt:76-79`):`required = listOf(POST_NOTIFICATIONS, CAMERA, RECORD_AUDIO)`,`RequestMultiplePermissions`。
- 后端 `recordings.mark_uploaded(conn, rec_id, company_id, size_bytes)`(`src/repositories/recordings.py:32`);`complete_recording(conn, caller, rec_id, body)`(`src/lambda_org_api.py:291`)取 `body["sizeBytes"]`。`gps_track jsonb` 列已存在(迁移 0009)+ 已在 `_COLS` SELECT,但从不写。

## File Structure

**新建(mobile)**:
- `capture/WatermarkContent.kt` — 纯:4 行内容数据类 + 从 用户名/时间/fix/地址 组装、格式化(lat/lon 4 位小数)。JVM 可测。
- `capture/camera2/WatermarkRenderer.kt` — `WatermarkContent` + 画布尺寸 → `Bitmap`(Canvas/Paint,底部半透明条 + 白字描边)。视频/照片共用。
- `capture/GpsTracker.kt` — `LocationManager` GPS_PROVIDER 采集:`latestFix`、`snapshotTrack()`、`address`;`start()/stop()`;Geocoder 反查(后台+缓存)。

**修改(mobile)**:
- `capture/camera2/GlRecordPipeline.kt` — 加文字纹理层 + `setWatermarkBitmap(Bitmap?)`。
- `capture/camera2/Camera2Pipeline.kt` — `setWatermarkBitmap` 透传 gl;`startSegment` location 由调用方传真值。
- `capture/CaptureManager.kt` — GpsTracker 起停、~1Hz 刷水印、段起点 location、照片叠水印、gps_track 存 DB。
- `core/SettingsStore.kt` — `watermarkEnabled`(默认 true)+ setter。
- `ui/SettingsScreen.kt` — 水印开关行。
- `ui/MainActivity.kt` — `required` 加 `ACCESS_FINE_LOCATION`。
- `AndroidManifest.xml` — 加 `ACCESS_FINE_LOCATION`。
- `db/CaptureRecord.kt` + `db/CaptureDb.kt` — 加 `gpsTrack: String?` 列 + Room 迁移 2→3。
- `net/RecordingsApiClient.kt` — `complete` 加 `gpsTrack: String?`。
- `upload/UploadWorker.kt` — 传 `record.gpsTrack`。
- `debug/`(临时探针,Task1;Task14 移除)+ `ui/DiagnosticsScreen.kt` 临时按钮。

**修改(backend)**:
- `src/repositories/recordings.py` — `mark_uploaded` 加 `gps_track`,UPDATE `SET gps_track=COALESCE(...)`。
- `src/lambda_org_api.py` — `complete_recording` 取 `body["gpsTrack"]` 传下去。
- `tests/unit/test_recordings_api.py` + `tests/integration/test_recordings_repo.py` — 加 gps_track 用例。

---

### Task 1: `GlRecordPipeline` 水印叠加层 + 方向真机定标(先定生死)

**Files:** Modify `app/src/main/java/com/benzn/grandtime/capture/camera2/GlRecordPipeline.kt`;Modify `ui/DiagnosticsScreen.kt`(临时探针按钮,Task14 删)。

**Interfaces:**
- Consumes:`android.graphics.Bitmap`。
- Produces:`GlRecordPipeline.setWatermarkBitmap(bmp: Bitmap?)`(在 OES 相机帧上叠一张 2D 位图于底部;null=不叠);`companion object { var WM_ROTATION_DEG }`(叠加内容 GL 内预旋角度,Task1 探针定标)。

**目的**:编码帧是传感器方向(横 1440×1080),`setOrientationHint(90)` 播放旋正。水印必须在 GL 里画成"播放旋正后底部且水平"。先用**默认预旋 90°** 实现,再真机探针确认/改 `WM_ROTATION_DEG`——不靠猜。

- [ ] **Step 1: 给 `GlRecordPipeline` 加 2D 叠加层**——在类里加字段、`setWatermarkBitmap`、2D 着色器、`drawWatermark`,并在 `drawFrame` 每个目标 window 的相机 OES 绘制后、`eglSwapBuffers` 前调 `drawWatermark`:

```kotlin
    // ==== 水印叠加(P3-T1)====
    companion object { var WM_ROTATION_DEG = 90 } // 播放 setOrientationHint(90) 对应的 GL 预旋;Task1 探针定标
    private var wmTexId = 0
    private var wmProgram = 0
    private var wmAPos = 0
    private var wmATex = 0
    @Volatile private var hasWatermark = false
    private val wmTexCoord: java.nio.FloatBuffer = java.nio.ByteBuffer.allocateDirect(8 * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0) } // 位图正立采样

    /** 叠加位图上传为 GL_TEXTURE_2D(GL 线程);null=停止叠加。位图生命周期由调用方管。 */
    fun setWatermarkBitmap(bmp: Bitmap?) = handler.post {
        if (bmp == null) { hasWatermark = false; return@post }
        if (wmProgram == 0) wmProgram = build2dProgram()
        if (wmTexId == 0) { val t = IntArray(1); GLES20.glGenTextures(1, t, 0); wmTexId = t[0] }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        hasWatermark = true
    }

    /** 相机帧画完后叠水印:底部条,按 WM_ROTATION_DEG 预旋,alpha 混合。 */
    private fun drawWatermark() {
        if (!hasWatermark || wmProgram == 0) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(wmProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTexId)
        val pos = watermarkQuadPositions(WM_ROTATION_DEG) // NDC 顶点:占"播放底部条"
        GLES20.glEnableVertexAttribArray(wmAPos)
        GLES20.glVertexAttribPointer(wmAPos, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(wmATex)
        GLES20.glVertexAttribPointer(wmATex, 2, GLES20.GL_FLOAT, false, 0, wmTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(wmAPos)
        GLES20.glDisableVertexAttribArray(wmATex)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * 叠加 quad 的 NDC 顶点(TRIANGLE_STRIP 4 点)。编码帧是横向,播放靠 orientationHint 旋 90°。
     * rotationDeg=90:水印占编码帧"右侧一条竖带",播放旋正后成为底部横条。Task1 探针据实测改此角。
     * 各角度返回对应的 4 顶点(左下、右下、左上、右上顺序,配合 wmTexCoord)。
     */
    private fun watermarkQuadPositions(rotationDeg: Int): java.nio.FloatBuffer {
        // band = 播放画面底部 22% 高;在编码帧(横)里,rotation=90 → 该带位于编码帧右侧(x∈[0.78,1.0])
        val b = 0.22f
        val v = when (((rotationDeg % 360) + 360) % 360) {
            90 -> floatArrayOf( // 右侧竖带,纹理顺时针 90°(播放后底部水平)
                1f - 2f * b, -1f,  1f, -1f,  1f - 2f * b, 1f,  1f, 1f,
            )
            270 -> floatArrayOf(
                -1f, -1f,  -1f + 2f * b, -1f,  -1f, 1f,  -1f + 2f * b, 1f,
            )
            180 -> floatArrayOf( // 顶部带(播放旋 180)
                -1f, 1f - 2f * b,  1f, 1f - 2f * b,  -1f, 1f,  1f, 1f,
            )
            else -> floatArrayOf( // 0:底部带(无旋转设备)
                -1f, -1f,  1f, -1f,  -1f, -1f + 2f * b,  1f, -1f + 2f * b,
            )
        }
        return java.nio.ByteBuffer.allocateDirect(v.size * 4).order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(v); position(0) }
    }

    /** 2D(非 OES)透传着色器:sampler2D,用于叠加层。 */
    private fun build2dProgram(): Int {
        val vs = """
            attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = aTex; }
        """.trimIndent()
        val fs = """
            precision mediump float; varying vec2 vTex; uniform sampler2D sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        fun sh(t: Int, s: String) = GLES20.glCreateShader(t).also { GLES20.glShaderSource(it, s); GLES20.glCompileShader(it) }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        wmAPos = GLES20.glGetAttribLocation(p, "aPos")
        wmATex = GLES20.glGetAttribLocation(p, "aTex")
        return p
    }
```

在 `drawFrame()` 里,每个目标 window 画完相机 OES 纹理(`glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)`)之后、`EGL14.eglSwapBuffers(...)` 之前,插一行 `drawWatermark()`。在 `release()` 的 handler.post 里加 `if (wmTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(wmTexId), 0); wmTexId = 0 }`、`hasWatermark = false`。

- [ ] **Step 2: 临时探针(Diagnostics 按钮)**——加一个临时 `debug/WatermarkProbe.kt`(用 P2 已删的 `probeGlRecord` 同构代码,见 git `a941bf7`:相机→`GlRecordPipeline`→`SegmentRecorder`(1440×1080)录 3s),差异仅:`gl.start` 后 `gl.setWatermarkBitmap(makeMarkerBitmap())`,其中 `makeMarkerBitmap` 画一张不对称标记(顶部黄"TOP"字 + 左上红角);输出 `/sdcard/FieldSight/_probe/probe_wm.mp4`。DiagnosticsScreen 加临时 `OutlinedButton("Run watermark probe (debug)")` 触发。**探针复用 `setWatermarkBitmap` 正式实现,不另写 GL**。

```kotlin
// WatermarkProbe.makeMarkerBitmap:透明底 + 顶部黄字 "TOP" + 左上红角(判方向用)
fun makeMarkerBitmap(): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(480, 120, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(android.graphics.Color.argb(120, 0, 0, 0))
    c.drawRect(0f, 0f, 60f, 60f, android.graphics.Paint().apply { color = android.graphics.Color.RED })
    c.drawText("TOP", 80f, 80f, android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; textSize = 64f; isAntiAlias = true })
    return bmp
}
```

- [ ] **Step 3: 构建** `./gradlew assembleDebug`。

- [ ] **Step 4: 真机定标(控制器)**——装机、Diagnostics 点探针、`adb pull //sdcard/FieldSight/_probe/probe_wm.mp4`,ffprobe(1440×1080 + rotation)、抽帧看 "TOP"/红角:**在播放旋正后的画面里,标记是否位于底部且文字水平?** 若否,调 `WM_ROTATION_DEG`(试 0/90/180/270)重跑直到底部水平。把定标值写死进 `WM_ROTATION_DEG` 默认值 + 记 `docs/superpowers/plans/.capture2-p3-findings.md`。**这步过 = 水印方向绿灯,P3 正式路径可接。**

- [ ] **Step 5: Commit** `feat(capture2): GlRecordPipeline watermark overlay layer + orientation calibration (device)`

---

### Task 2: `WatermarkContent`(4 行内容组装)— TDD

**Files:** Create `app/src/main/java/com/benzn/grandtime/capture/WatermarkContent.kt`;Test `app/src/test/java/com/benzn/grandtime/capture/WatermarkContentTest.kt`。

**Interfaces:**
- Produces:
  - `data class WatermarkContent(val lines: List<String>)`
  - `object Watermark { fun build(userName: String?, epochMillis: Long, lat: Double?, lon: Double?, address: String?, zone: java.time.ZoneId): WatermarkContent }`
- Consumes: 无。

- [ ] **Step 1: 写失败测试** `WatermarkContentTest.kt`:

```kotlin
package com.benzn.grandtime.capture

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class WatermarkContentTest {
    private val utc = ZoneId.of("UTC")

    @Test fun `full content = 4 lines user time latlon address`() {
        val c = Watermark.build("Ben Lin", 0L, -36.85005, 174.76001, "12 Queen St", utc)
        assertEquals(
            listOf("Ben Lin", "1970-01-01 00:00:00", "-36.8501, 174.7600", "12 Queen St"),
            c.lines,
        )
    }

    @Test fun `no fix shows locating placeholder and omits address`() {
        val c = Watermark.build("Ben Lin", 0L, null, null, null, utc)
        assertEquals(listOf("Ben Lin", "1970-01-01 00:00:00", "定位中…"), c.lines)
    }

    @Test fun `null user omitted, address omitted when null but fix present`() {
        val c = Watermark.build(null, 0L, 1.0, 2.0, null, utc)
        assertEquals(listOf("1970-01-01 00:00:00", "1.0000, 2.0000"), c.lines)
    }
}
```

- [ ] **Step 2: 跑测试确认失败** `./gradlew testDebugUnitTest --tests "com.benzn.grandtime.capture.WatermarkContentTest"` → FAIL(类不存在)。

- [ ] **Step 3: 实现** `WatermarkContent.kt`:

```kotlin
package com.benzn.grandtime.capture

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 水印内容:若干行文本(缺项已省略)。 */
data class WatermarkContent(val lines: List<String>)

object Watermark {
    private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 组装 4 行:用户名(有才加)/ 时间戳 / 经纬度(无定位=占位)/ 街道地址(有才加)。 */
    fun build(
        userName: String?,
        epochMillis: Long,
        lat: Double?,
        lon: Double?,
        address: String?,
        zone: ZoneId,
    ): WatermarkContent {
        val lines = ArrayList<String>(4)
        if (!userName.isNullOrBlank()) lines += userName
        lines += TIME_FMT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
        if (lat != null && lon != null) {
            lines += "%.4f, %.4f".format(lat, lon)
            if (!address.isNullOrBlank()) lines += address
        } else {
            lines += "定位中…"
        }
        return WatermarkContent(lines)
    }
}
```

- [ ] **Step 4: 跑测试确认通过** → PASS(3 测)。
- [ ] **Step 5: Commit** `feat(capture): WatermarkContent — assemble 4-line watermark text`

---

### Task 3: 水印开关设置(`watermarkEnabled`)— TDD

**Files:** Modify `core/SettingsStore.kt`;Test `core/SettingsStoreTest.kt`(追加)。

**Interfaces:**
- Produces:`RecordingSettings.watermarkEnabled: Boolean`(默认 `true`);`SettingsStore.setWatermarkEnabled(value: Boolean)`。

- [ ] **Step 1: 写失败测试**(追加,沿用文件内既有内存 DataStore 模式):

```kotlin
    @Test fun `watermark defaults to enabled`() = runTest {
        assertEquals(true, SettingsStore(testDataStore()).settings.first().watermarkEnabled)
    }

    @Test fun `watermark toggle roundtrips`() = runTest {
        val store = SettingsStore(testDataStore())
        store.setWatermarkEnabled(false)
        assertEquals(false, store.settings.first().watermarkEnabled)
    }
```

- [ ] **Step 2: 跑测试确认失败** → FAIL(`watermarkEnabled` unresolved)。

- [ ] **Step 3: 实现** `SettingsStore.kt`:
  - `companion object` 加 `private val KEY_WATERMARK = booleanPreferencesKey("watermark_enabled")`(import `androidx.datastore.preferences.core.booleanPreferencesKey`)。
  - `RecordingSettings` 末尾加 `val watermarkEnabled: Boolean = true,`。
  - `settings` map 里加 `watermarkEnabled = prefs[KEY_WATERMARK] ?: true,`。
  - 加 `suspend fun setWatermarkEnabled(value: Boolean) { dataStore.edit { it[KEY_WATERMARK] = value } }`。

- [ ] **Step 4: 跑测试确认通过** → PASS(含既有全绿)。
- [ ] **Step 5: Commit** `feat(settings): watermark on/off (default on)`

---

### Task 4: `CaptureRecord.gpsTrack` 列 + Room 迁移 2→3

**Files:** Modify `db/CaptureRecord.kt`、`db/CaptureDb.kt`;Test `db/`(迁移测试,若既有迁移有测则照抄;否则加最小实例化冒烟)。

**Interfaces:**
- Produces:`CaptureRecord.gpsTrack: String?`(JSON 数组字符串,null=无);`CaptureDb` version 3 + `MIGRATION_2_3`。

- [ ] **Step 1: 加列**——`CaptureRecord.kt` 在 `missing` 前加 `val gpsTrack: String? = null,`。

- [ ] **Step 2: 迁移**——`CaptureDb.kt`:`@Database(version = 3, ...)`;照 SP4b 的 `MIGRATION_1_2` 写:
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE capture_records ADD COLUMN gpsTrack TEXT")
    }
}
```
并在 builder `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`(不加 fallbackToDestructive)。

- [ ] **Step 3: 构建 + 迁移冒烟**——`./gradlew testDebugUnitTest`(既有测全绿);若项目有 Room migration test 基建则加 2→3 用例,否则在 report 记"迁移为纯 ADD COLUMN,run 时 Room 校验 schema"。

- [ ] **Step 4: Commit** `feat(db): CaptureRecord.gpsTrack column + migration 2->3`

---

### Task 5: `WatermarkRenderer`(内容 → Bitmap)

**Files:** Create `app/src/main/java/com/benzn/grandtime/capture/camera2/WatermarkRenderer.kt`。

**Interfaces:**
- Consumes:`WatermarkContent`(T2)。
- Produces:`object WatermarkRenderer { fun render(content: WatermarkContent, widthPx: Int): Bitmap }`(视频/照片共用;`widthPx`=目标水印带宽度,高度按行数自适应)。

- [ ] **Step 1: 实现**`WatermarkRenderer.kt`——底部半透明黑条 + 白字带描边(可读性),从下往上排行:

```kotlin
package com.benzn.grandtime.capture.camera2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.benzn.grandtime.capture.WatermarkContent

/** 把 4 行水印内容画到一张 ARGB 位图:半透明黑底条 + 白字黑描边。视频(GL 纹理)与照片(直接叠)共用。 */
object WatermarkRenderer {
    fun render(content: WatermarkContent, widthPx: Int): Bitmap {
        val lines = content.lines
        val textSize = (widthPx * 0.028f).coerceAtLeast(18f)
        val pad = textSize * 0.4f
        val lineH = textSize * 1.25f
        val height = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.argb(110, 0, 0, 0)) // 半透明黑底条
        val stroke = Paint().apply {
            color = Color.BLACK; this.textSize = textSize; isAntiAlias = true
            style = Paint.Style.STROKE; strokeWidth = textSize * 0.12f
        }
        val fill = Paint().apply {
            color = Color.WHITE; this.textSize = textSize; isAntiAlias = true; style = Paint.Style.FILL
        }
        var y = pad + textSize
        for (line in lines) {
            c.drawText(line, pad, y, stroke)
            c.drawText(line, pad, y, fill)
            y += lineH
        }
        return bmp
    }
}
```

- [ ] **Step 2: 构建** `./gradlew assembleDebug`(无独立单测——Canvas 绘制真机/验收覆盖;`render` 尺寸逻辑简单,由 T1/T14 真机看效果)。
- [ ] **Step 3: Commit** `feat(capture2): WatermarkRenderer — draw watermark lines to bitmap`

---

### Task 6: `Camera2Pipeline` 水印透传 + 段起点 location

**Files:** Modify `app/src/main/java/com/benzn/grandtime/capture/camera2/Camera2Pipeline.kt`。

**Interfaces:**
- Consumes:`GlRecordPipeline.setWatermarkBitmap`(T1)。
- Produces:`Camera2Pipeline.setWatermarkBitmap(bmp: Bitmap?)`(转发给内部 gl;gl 未建则记住,建后补设)。`startSegment` 的 `location` 参数已存在(P2),本任务不改签名,只确保透传。

- [ ] **Step 1: 加透传**——`Camera2Pipeline.kt` 加:
```kotlin
    @Volatile private var pendingWatermark: android.graphics.Bitmap? = null

    /** 水印位图转发给 GL 叠加层;会话未建时记住,ensureSession 里补设。null=不叠。 */
    fun setWatermarkBitmap(bmp: android.graphics.Bitmap?) {
        pendingWatermark = bmp
        gl?.setWatermarkBitmap(bmp)
    }
```
在 `ensureSession(...)` 里 `gl` 建好(`glp.start(...)` 之后)补一行:`if (pendingWatermark != null) glp.setWatermarkBitmap(pendingWatermark)`。在 `release()` 里加 `pendingWatermark = null`。
> `startSegment` 的 `location: Pair<Float,Float>?` 已透传给 `SegmentRecorder.prepare(..., location, ...)`(P2 已在),本任务无需改;CaptureManager(T8)开始传真值。

- [ ] **Step 2: 构建** `./gradlew assembleDebug`。
- [ ] **Step 3: Commit** `feat(capture2): Camera2Pipeline.setWatermarkBitmap passthrough`

---

### Task 7: `GpsTracker`(LocationManager + 轨迹 + 反查地址)

**Files:** Create `app/src/main/java/com/benzn/grandtime/capture/GpsTracker.kt`。

**Interfaces:**
- Produces:
  - `class GpsTracker(context: Context)`
  - `fun start()` / `fun stop()`(录制起停调;stop 清轨迹)
  - `val latestFix: Pair<Double, Double>?`(lat,lon;无=null)
  - `val address: String?`(反查街道,无/离线=null)
  - `fun snapshotTrackJson(): String?`(当前轨迹 `[{"t","lat","lon"}]` 的 JSON;空=null)

- [ ] **Step 1: 实现**`GpsTracker.kt`——`LocationManager.requestLocationUpdates(GPS_PROVIDER, 1000ms, 0f)`;每 fix 更新 latestFix + append 轨迹;移动 >50m 触发后台 Geocoder 反查:

```kotlin
package com.benzn.grandtime.capture

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** GPS 采集:LocationManager(GPS_PROVIDER ~1s)。持最新 fix + 累积轨迹 + 反查地址(后台/缓存)。 */
class GpsTracker(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile var latestFix: Pair<Double, Double>? = null
        private set
    @Volatile var address: String? = null
        private set
    private val track = JSONArray()
    private var lastGeocodeLat = 0.0
    private var lastGeocodeLon = 0.0

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            latestFix = loc.latitude to loc.longitude
            synchronized(track) {
                track.put(JSONObject().put("t", loc.time).put("lat", loc.latitude).put("lon", loc.longitude))
            }
            maybeGeocode(loc.latitude, loc.longitude)
        }
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission") // 调用方确保 ACCESS_FINE_LOCATION(未授权则 start no-op)
    fun start() {
        synchronized(track) { for (i in track.length() - 1 downTo 0) track.remove(i) }
        latestFix = null; address = null
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, context.mainLooper)
        }
    }

    fun stop() { runCatching { lm.removeUpdates(listener) } }

    fun snapshotTrackJson(): String? = synchronized(track) {
        if (track.length() == 0) null else track.toString()
    }

    private fun maybeGeocode(lat: Double, lon: Double) {
        if (address != null && distanceM(lat, lon, lastGeocodeLat, lastGeocodeLon) < 50) return
        lastGeocodeLat = lat; lastGeocodeLon = lon
        scope.launch {
            val addr = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
                    ?.firstOrNull()?.let { it.thoroughfare?.let { t -> "${it.subThoroughfare ?: ""} $t".trim() } ?: it.getAddressLine(0) }
            }.getOrNull()
            if (!addr.isNullOrBlank()) address = addr
        }
    }

    private fun distanceM(la1: Double, lo1: Double, la2: Double, lo2: Double): Float {
        val r = FloatArray(1); Location.distanceBetween(la1, lo1, la2, lo2, r); return r[0]
    }
}
```

- [ ] **Step 2: 构建** `./gradlew assembleDebug`(GPS/Geocoder 真机验收覆盖)。
- [ ] **Step 3: Commit** `feat(capture): GpsTracker — LocationManager GPS + track + reverse-geocode`

---

### Task 8: `CaptureManager` 编排(GPS 起停 + ~1Hz 刷水印 + 段起点 + gps_track 存 DB)

**Files:** Modify `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`。

**Interfaces:**
- Consumes:`GpsTracker`(T7)、`WatermarkRenderer`(T5)、`Watermark.build`(T2)、`Camera2Pipeline.setWatermarkBitmap`(T6)、`SettingsStore.settings.watermarkEnabled`(T3)、`CaptureRecord.gpsTrack`(T4)。

- [ ] **Step 1: 加 GPS + 水印编排**——`CaptureManager.kt`:
  - 字段:`private val gps = GpsTracker(context)`;`private var watermarkTimer: Job? = null`。
  - `startVideoSegment`:段 1(`cmd.segmentIndex == 1`)时:`if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) gps.start()`;并在拿到 `settings.watermarkEnabled` 后启动水印刷新(见下)。段起点 location:`pipeline.startSegment(..., location = gps.latestFix?.let { it.first.toFloat() to it.second.toFloat() }, ...)`(替换 P2 的 `location = null`)。
  - **~1Hz 刷水印**:段 1 起 `startWatermarkTimer(settings)`:
```kotlin
    private fun startWatermarkTimer(settings: RecordingSettings) {
        watermarkTimer?.cancel()
        if (!settings.watermarkEnabled) { pipeline.setWatermarkBitmap(null); return }
        watermarkTimer = scope.launch {
            while (true) {
                val name = (AppState.loginState.value as? com.benzn.grandtime.core.LoginState.LoggedIn)?.displayName
                val fix = gps.latestFix
                val content = com.benzn.grandtime.capture.Watermark.build(
                    userName = name, epochMillis = System.currentTimeMillis(),
                    lat = fix?.first, lon = fix?.second, address = gps.address,
                    zone = java.time.ZoneId.systemDefault(),
                )
                val bmp = com.benzn.grandtime.capture.camera2.WatermarkRenderer.render(content, widthPx = 640)
                pipeline.setWatermarkBitmap(bmp)
                delay(1000)
            }
        }
    }
    private fun stopWatermarkTimer() { watermarkTimer?.cancel(); watermarkTimer = null; pipeline.setWatermarkBitmap(null) }
```
  - 录像回 Idle(onFinalized 的 `core.state is Idle` 分支)+ `shutdown()`:`stopWatermarkTimer(); gps.stop()`。相机丢失(`onCameraLost` 分支)也 `stopWatermarkTimer(); gps.stop()`。
  - **gps_track 存 DB**:`finalizeVideoDbRow()` 里,取 `gps.snapshotTrackJson()` 写入该行的 `gpsTrack`——加 DAO `updateGpsTrack(id, json)`(或在 finalize 时带上)。最简:在 `finalizeVideoDbRow` 的 `dao.finalize(...)` 后 `gps.snapshotTrackJson()?.let { dao.updateGpsTrack(id, it) }`(DAO 加 `@Query("UPDATE capture_records SET gpsTrack=:json WHERE id=:id") suspend fun updateGpsTrack(id: String, json: String)`)。

- [ ] **Step 2: 构建 + 既有单测** `./gradlew testDebugUnitTest --tests "com.benzn.grandtime.capture.CaptureCoreTest"`(13 绿,契约未变)+ `assembleDebug`。
- [ ] **Step 3: Commit** `feat(capture): CaptureManager — GPS start/stop + 1Hz watermark refresh + segment start location + gps_track to DB`

---

### Task 9: 照片水印(Canvas 叠 JPEG)

**Files:** Modify `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`。

**Interfaces:** Consumes T5/T2/T3。

- [ ] **Step 1: takePhoto 落盘后叠水印**——在 `takePhoto` 成功、`downscaleJpeg` 之后(或合并到同一次 IO),若 `settings.watermarkEnabled`:解码 JPEG → Canvas 叠水印(用 `WatermarkRenderer.render` 出的位图画到底部,按图片宽)→ 重压 JPEG(保 EXIF orientation,复用 P2 helper 模式):

```kotlin
    /** 照片水印:解码 → Canvas 底部叠 render 出的水印带 → 重压 JPEG(保 EXIF orientation)。IO 线程。 */
    private suspend fun stampPhotoWatermark(file: java.io.File, quality: Int) = withContext(Dispatchers.IO) {
        runCatching {
            val orientation = runCatching { android.media.ExifInterface(file.absolutePath).getAttribute(android.media.ExifInterface.TAG_ORIENTATION) }.getOrNull()
            val src = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching
            val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val name = (AppState.loginState.value as? com.benzn.grandtime.core.LoginState.LoggedIn)?.displayName
            val fix = gps.latestFix
            val content = com.benzn.grandtime.capture.Watermark.build(name, System.currentTimeMillis(), fix?.first, fix?.second, gps.address, java.time.ZoneId.systemDefault())
            val wm = com.benzn.grandtime.capture.camera2.WatermarkRenderer.render(content, out.width)
            android.graphics.Canvas(out).drawBitmap(wm, 0f, (out.height - wm.height).toFloat(), null)
            java.io.FileOutputStream(file).use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }
            if (out != src) out.recycle(); src.recycle(); wm.recycle()
            if (orientation != null) runCatching {
                android.media.ExifInterface(file.absolutePath).apply { setAttribute(android.media.ExifInterface.TAG_ORIENTATION, orientation); saveAttributes() }
            }
        }
    }
```
在 `takePhoto` 成功分支里,`downscaleJpeg` 之后加 `if (settings.watermarkEnabled) stampPhotoWatermark(file, jpegQuality(settings.photoQuality))`。**注意**:GpsTracker 在 Idle 拍照时未必 start(照片可在 Idle 拍)——Idle 拍照 fix 可能为空,水印显"定位中…";若要 Idle 拍照也带 GPS,在 `takePhoto` 非录像分支也 `if (granted(FINE_LOCATION)) gps.start()`,拍完不 stop(录像态由录像管)。**决策**:P3 拍照水印用"当前 gps.latestFix"(录像中有值;Idle 单拍显定位中或触发一次性 fix),不为单拍强开 GPS(YAGNI);真机验收看是否够用。

- [ ] **Step 2: 构建** `assembleDebug`。
- [ ] **Step 3: Commit** `feat(capture): photo watermark — Canvas overlay on JPEG (EXIF preserved)`

---

### Task 10: 定位权限(manifest + 向导)

**Files:** Modify `app/src/main/AndroidManifest.xml`、`app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`。

- [ ] **Step 1: manifest**——在 `RECORD_AUDIO` 那行后加 `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />`。
- [ ] **Step 2: 向导**——`MainActivity.kt` 的 `required = listOf(POST_NOTIFICATIONS, CAMERA, RECORD_AUDIO)` 加 `Manifest.permission.ACCESS_FINE_LOCATION`(`RequestMultiplePermissions` 已处理数组,callback 不分支,无需改别处)。
- [ ] **Step 3: 构建** `assembleDebug`。
- [ ] **Step 4: Commit** `feat(perm): request ACCESS_FINE_LOCATION for GPS watermark`

---

### Task 11: SettingsScreen 水印开关行

**Files:** Modify `app/src/main/java/com/benzn/grandtime/ui/SettingsScreen.kt`。

- [ ] **Step 1: 加行**——`SettingDialog` enum 加 `WATERMARK`;RECORDING 区加行(照 Aspect ratio 模式,布尔用 `RadioDialog<Boolean>`):
```kotlin
// 行
SettingRow("Watermark", if (settings.watermarkEnabled) "On" else "Off") { dialog = SettingDialog.WATERMARK }
// 弹窗分支
SettingDialog.WATERMARK -> RadioDialog(
    title = "Watermark",
    options = listOf(true, false),
    selected = settings.watermarkEnabled,
    label = { if (it) "On" else "Off" },
    onSelect = { scope.launch { store.setWatermarkEnabled(it) } },
    onDismiss = { dialog = null },
)
```
- [ ] **Step 2: 构建** `assembleDebug`;装机核对 Settings 多出 "Watermark On/Off" 行、切换持久化。
- [ ] **Step 3: Commit** `feat(settings-ui): Watermark on/off row`

---

### Task 12: 后端 `gps_track` 入库(fieldsight-pipeline)— TDD

**Files:**(仓 `C:/Users/camil/Dropbox/fieldsight-pipeline`)Modify `src/repositories/recordings.py`、`src/lambda_org_api.py`;Test `tests/unit/test_recordings_api.py`、`tests/integration/test_recordings_repo.py`。

**Interfaces:** `mark_uploaded(conn, rec_id, company_id, size_bytes=None, gps_track=None)`;`complete_recording` 取 `body["gpsTrack"]`。

- [ ] **Step 1: 写失败单测**——`tests/unit/test_recordings_api.py` 加(照 `test_complete_marks_uploaded` 模式,断言 `mark_uploaded` 收到 gps_track):
```python
def test_complete_persists_gps_track(monkeypatch):
    # body 带 gpsTrack 数组 → complete_recording 透传给 mark_uploaded
    captured = {}
    def fake_mark(conn, rid, cid, size_bytes=None, gps_track=None):
        captured["gps_track"] = gps_track
        return {"id": rid}
    monkeypatch.setattr(recordings, "mark_uploaded", fake_mark)
    body = {"sizeBytes": 10, "gpsTrack": [{"t": 1, "lat": -36.85, "lon": 174.76}]}
    resp = complete_recording(FakeConn(), CALLER, "rid", body)
    assert resp["statusCode"] == 200
    assert captured["gps_track"] == [{"t": 1, "lat": -36.85, "lon": 174.76}]
```
(用文件里既有的 `CALLER`/`FakeConn`/`complete_recording` import 方式;若名字不同照抄既有 complete 测试的 fixture。)

- [ ] **Step 2: 跑测试确认失败** `pytest tests/unit/test_recordings_api.py::test_complete_persists_gps_track -q` → FAIL。

- [ ] **Step 3: 实现**:
  - `lambda_org_api.py` `complete_recording`:
```python
def complete_recording(conn, caller, rec_id, body):
    b = body or {}
    row = recordings.mark_uploaded(conn, rec_id, caller["company_id"], b.get("sizeBytes"), b.get("gpsTrack"))
    if row is None:
        return error("recording not found", 404)
    return ok({"ok": True})
```
  - `recordings.py` `mark_uploaded`——加参数 + jsonb 写(用 `psycopg.types.json.Jsonb`):
```python
from psycopg.types.json import Jsonb
...
def mark_uploaded(conn, rec_id, company_id, size_bytes=None, gps_track=None):
    return conn.cursor(row_factory=dict_row).execute(
        f"UPDATE recordings SET uploaded_at=now(), "
        f"size_bytes=COALESCE(%s, size_bytes), "
        f"gps_track=COALESCE(%s, gps_track) "
        f"WHERE id=%s AND company_id=%s RETURNING {_COLS}",
        (size_bytes, Jsonb(gps_track) if gps_track is not None else None, rec_id, company_id),
    ).fetchone()
```

- [ ] **Step 4: 跑测试确认通过** + 集成测(加 `tests/integration/test_recordings_repo.py` 一例:insert→mark_uploaded(gps_track=[…])→get_by_id 断言 gps_track 回读)。`pytest tests/unit/test_recordings_api.py -q`(全绿),集成测在 CI pg16 跑。

- [ ] **Step 5: Commit + 推 develop**(CI 部署 fieldsight-test)`feat(recordings): persist gps_track on complete`

---

### Task 13: 移动端上传 gps_track — TDD 解析

**Files:** Modify `app/src/main/java/com/benzn/grandtime/net/RecordingsApiClient.kt`、`app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt`、`db/CaptureRecordDao`(加 `updateGpsTrack`,若 T8 未加);Test `net/RecordingsApiClientTest.kt`(追加)。

**Interfaces:** `complete(idToken, recordingId, sizeBytes, gpsTrack: String? = null): Boolean`。

- [ ] **Step 1: 写失败测试**——`RecordingsApiClientTest.kt` 追加(照既有注入式 http 断言 body 含 gpsTrack):
```kotlin
    @Test fun `complete includes gpsTrack in body when present`() {
        var sentBody = ""
        val client = RecordingsApiClient("https://x", http = fakeHttp { _, _, body -> sentBody = body; HttpResult(200, "") })
        client.complete("tok", "rid", 10L, gpsTrack = """[{"t":1,"lat":-36.85,"lon":174.76}]""")
        assert(sentBody.contains("\"gpsTrack\"") && sentBody.contains("174.76"))
    }
```
(用该文件既有的 fakeHttp/HttpResult 注入模式;若签名不同照抄既有 uploadUrl 测试。)

- [ ] **Step 2: 跑测试确认失败** → FAIL。

- [ ] **Step 3: 实现**:
  - `RecordingsApiClient.complete`:签名加 `gpsTrack: String? = null`;body 加 `gpsTrack?.let { body.put("gpsTrack", org.json.JSONArray(it)) }`(把 JSON 字符串还原成数组放进 body,后端得 list)。
  - `UploadWorker.doWork` line 105:`client.complete(idToken, urlResult.recordingId, file.length(), gpsTrack = record.gpsTrack)`。
  - `CaptureRecord` 已有 `gpsTrack`(T4);DAO `updateGpsTrack`(T8 或此处)。

- [ ] **Step 4: 跑测试确认通过** `./gradlew testDebugUnitTest --tests "com.benzn.grandtime.net.RecordingsApiClientTest"` → PASS。
- [ ] **Step 5: Commit** `feat(net): upload gps_track in recording complete`

---

### Task 14: 真机端到端验收 + tag

**Files:** Delete `debug/WatermarkProbe.kt` + Diagnostics 临时按钮(git rm/清理);更新 `.capture2-p3-findings.md`。

- [ ] **Step 1: 删探针**——`git rm debug/WatermarkProbe.kt`,DiagnosticsScreen 去临时按钮。`assembleDebug testDebugUnitTest` 全绿、`grep WatermarkProbe app/src` 零命中。
- [ ] **Step 2: 真机验收(控制器 + 用户)**——对照 P2 验收 + 新增:
  1. **视频水印**:录像 → 拉回 mp4,播放确认底部 4 行(用户名/时间/经纬度/地址)**方向正、可读、逐秒走时**;水印**关**时录一段确认无水印(回归)。
  2. **照片水印**:录像中/空闲拍照 → JPEG 带同样水印、方向正。
  3. **setLocation 起点**:mp4 `©xyz` atom 含起点 GPS。
  4. **gps_track 入库**:录制上传后,查 fieldsight-test 的 recordings 行 `gps_track` 有逐秒数组(控制器可用后端/日志确认)。
  5. **无定位/无权限**:关 GPS 或拒权限,水印 GPS 行显"定位中…"/"无定位权限",录制不崩。
  6. **P2 回归**:HEVC/音频/4:3-16:9/分段/预览/息屏/手电/录音/红灯/上传+siteId 仍 OK。
- [ ] **Step 3: 记录 + tag** 写 `.capture2-p3-findings.md`;`git tag sp-capture2-p3-accepted`。
- [ ] **Step 4: Commit** `docs: SP-Capture2-P3 device acceptance` + 后端 develop 已部署。

---

## Self-Review(写完自查)

**Spec 覆盖**:视频水印(T1 GL 层+T5 renderer+T8 刷新)、照片水印(T9)、4 行内容(T2)、LocationManager GPS(T7)、setLocation 起点(T6/T8)、gps_track 全链路(T4 存/T13 传/T12 后端)、地址反查(T7)、开关默认开(T3/T11)、权限(T10)、方向探针(T1)。全覆盖 spec §1-§8。
**Placeholder 扫描**:无 TODO/TBD;device 任务以真机验证代替 JVM 单测(计划头声明);`WM_ROTATION_DEG` 默认 90 由 T1 探针定标(诚实,非占位)。
**类型一致**:`WatermarkContent`/`Watermark.build`(T2)↔ T8/T9 调用一致;`WatermarkRenderer.render(content, widthPx)`(T5)↔ T8/T9;`setWatermarkBitmap`(T1 GL / T6 pipeline)一致;`GpsTracker`(T7)`latestFix/address/snapshotTrackJson`↔ T8/T9;`complete(...,gpsTrack)`(T13)↔ 后端 `gpsTrack`(T12)↔ `CaptureRecord.gpsTrack`(T4)。
**已知 scope 决策**:①Idle 单拍不强开 GPS(YAGNI,水印用当前 fix);②地址反查 50m 阈值 + 离线降级;③水印带宽固定 640px(视频)/图片宽(照片)。
**顺带(可选)**:动 GlRecordPipeline.drawFrame 时可补 P2.5 的编码器 EGL 错误检查、`eglPresentationTimeANDROID`(见 P2 计划附录),非本计划必做。

