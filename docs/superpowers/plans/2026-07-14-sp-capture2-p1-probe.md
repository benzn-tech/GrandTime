# SP-Capture2-P1 真机探针 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development。本计划是**真机可行性探针**(非 TDD 特性)——每个任务写一段探针代码,真机跑,靠 logcat + 拉回 mp4 验证,产出喂 P2 的硬件结论。探针代码是临时的(P2 会移除/沉淀成正式实现)。

**Goal:** 在 F2SP 真机上验证 Camera2+MediaCodec 管线可行,并测出真实参数——枚举相机可用尺寸、跑通 HEVC(降级 H.264)Camera2→MediaCodec→mp4 端到端、验证录像中并行拍照(ImageReader)与预览三流共存、验证 MediaMuxer.setLocation 元数据——为 P2 重写定生死、供尺寸/码率参数。

**Architecture:** 一个临时 `debug/Capture2Probe.kt`,由 Diagnostics 屏一个临时按钮触发;结果打到 logcat 标签 `CAP2PROBE`,录出的 mp4 存 `/sdcard/FieldSight/_probe/` 便于拉回验证。

**设备**:SDJW-F2SP(F2S202503103054),MediaTek MT6768,Android 13,后置相机,`c2.mtk.hevc.encoder` 已知存在,传感器原生 4:3。

## Global Constraints

- 全走 Android framework(Camera2/MediaCodec/MediaMuxer/ImageReader),**不引原生库**(armeabi 约束)、不引新 gradle 依赖。
- 探针代码放 `app/src/main/java/com/benzn/grandtime/debug/`,临时性;不动现有 `capture/` 生产代码。
- 触发:Diagnostics 屏加一个临时 `Button("Run Capture2 probe")` → 调 `Capture2Probe(context).runAll()`;所有输出 `Log.i("CAP2PROBE", …)`;录像存 `/sdcard/FieldSight/_probe/`。控制器可 `adb shell input tap` 触发 + `adb logcat -s CAP2PROBE` 读结果 + `adb pull` 取 mp4。
- 相机权限已在 manifest;探针跑前若无权限,`runAll` 打日志并退出(控制器先确保已授权)。
- 每个探针任务须**真机实跑**并把 logcat 关键行 + (录像任务)拉回的 mp4 `ffprobe`/播放结果记进报告。控制器负责真机执行与判定。

---

### Task 1: 尺寸 + 编码器枚举探针(不开相机,低风险先行)

**Files:** Create `app/src/main/java/com/benzn/grandtime/debug/Capture2Probe.kt`;Modify `ui/DiagnosticsScreen.kt`(加临时触发按钮)。

**Interfaces:** Produces `class Capture2Probe(context)`;`fun enumerate()`(本任务);`suspend fun runAll()`(后续任务累加)。

- [ ] **Step 1: 写 `Capture2Probe.enumerate()`**——用 `CameraManager`(无需开相机、无需权限):
  - 找后置 `CameraCharacteristics`(LENS_FACING_BACK);取 `SCALER_STREAM_CONFIGURATION_MAP`。
  - 打印 `getOutputSizes(MediaRecorder::class.java)`(视频档)、`getOutputSizes(ImageFormat.JPEG)`(拍照档)、`getOutputSizes(SurfaceTexture::class.java)`(预览档);对每组标出 **4:3 与 16:9 各自的最大尺寸**(按 w*3==h*4 / w*9==h*16 近似过滤)。
  - 打印 `SENSOR_INFO_ACTIVE_ARRAY_SIZE`、`SENSOR_INFO_PIXEL_ARRAY_SIZE`(满传感器广角依据)、`INFO_SUPPORTED_HARDWARE_LEVEL`、`REQUEST_MAX_NUM_OUTPUT_STREAMS`(几流上限,决定 preview+encoder+jpeg 三流是否 OK)。
  - 用 `MediaCodecList(REGULAR_CODECS)` 找 `video/hevc` 与 `video/avc` 的**编码器**:打印名字(期望 `c2.mtk.hevc.encoder`)、`VideoCapabilities` 的 supported widths/heights/bitrate range/frame rate。
  - 全部 `Log.i("CAP2PROBE", …)`。
- [ ] **Step 2: DiagnosticsScreen 加触发按钮**——一个 `Button`(文案 "Run Capture2 probe (debug)")onClick `scope.launch { Capture2Probe(context).runAll() }`;`runAll()` 本阶段只调 `enumerate()`。
- [ ] **Step 3: 构建** `./gradlew assembleDebug`。
- [ ] **Step 4: 真机跑 + 记结论**——控制器装机、进 Diagnostics 点按钮、`adb logcat -s CAP2PROBE` 收全部枚举输出,整理成"尺寸/HEVC 参数表"写进 `docs/superpowers/plans/.capture2-p1-findings.md`(或报告文件)。
- [ ] **Step 5: Commit** `feat(debug): capture2 probe — camera size + codec enumeration`

---

### Task 2: HEVC Camera2→MediaCodec→mp4 端到端探针(降级 H.264)

**Files:** Modify `debug/Capture2Probe.kt`。

**Interfaces:** Produces `suspend fun recordClip(useHevc: Boolean): String`(返回 mp4 路径或错误串)。

- [ ] **Step 1: 实现 `recordClip(useHevc)`**:
  - 目标尺寸:用 Task1 枚举出的 **4:3 最大视频档**(如 1920×1440;真机结果为准)。
  - 建 MediaCodec 编码器:`useHevc` → `createEncoderByType("video/hevc")`,否则 `"video/avc"`;`MediaFormat` 设 KEY_WIDTH/HEIGHT、COLOR_FormatSurface、KEY_BIT_RATE(~20Mbps)、KEY_FRAME_RATE(30)、KEY_I_FRAME_INTERVAL(1);`configure(..., CONFIGURE_FLAG_ENCODE)` → `createInputSurface()`。
  - 开后置 Camera2(`openCamera`),建 `CaptureSession`,输出 = 编码器 inputSurface(先只这一路,验证最简链路);`setRepeatingRequest(TEMPLATE_RECORD)`。
  - `MediaMuxer(mp4)`;编码器 `dequeueOutputBuffer` 拿到 INFO_OUTPUT_FORMAT_CHANGED 后 `addTrack` + `start`,循环写 encoded buffer;`setOrientationHint` 按传感器方向;录 ~3 秒后 signalEndOfInputStream、drain、stop、muxer.stop。
  - 输出 `/sdcard/FieldSight/_probe/probe_${if(useHevc)"hevc" else "avc"}.mp4`;成功/失败、耗时、文件大小、最终尺寸 `Log.i("CAP2PROBE", …)`。
  - **降级逻辑验证**:`runAll()` 先 `recordClip(useHevc=true)`,catch 到 HEVC 建/配置/录制异常 → 记 "HEVC 失败:<原因>" 并 `recordClip(useHevc=false)`。
  - 全程 try/catch/资源释放(camera/codec/muxer),异常打完整 stack。
- [ ] **Step 2: `runAll()` 接上** enumerate() 后调 recordClip 流程。
- [ ] **Step 3: 构建 + 真机跑**——控制器点按钮、`adb logcat -s CAP2PROBE` 看录制结果、`adb pull /sdcard/FieldSight/_probe/probe_hevc.mp4` 拉回,用可播放性/`ffprobe`(若本机有)确认编码=hevc、尺寸/码率对;记进 findings。**HEVC 能出可播 mp4 = P2 绿灯**;不能则记原因、看降级 H.264 是否可行。
- [ ] **Step 4: Commit** `feat(debug): capture2 probe — HEVC/AVC record-to-mp4 end-to-end`

---

### Task 3: 三流并行探针(编码器 + 拍照 ImageReader + 预览)

**Files:** Modify `debug/Capture2Probe.kt`。

**Interfaces:** Produces `suspend fun probeConcurrent(): String`。

- [ ] **Step 1: 实现 `probeConcurrent()`**:在 Task2 链路上,`CaptureSession` 的输出加两路——一个 JPEG `ImageReader`(拍照档,如 4:3 最大)+ 一个 `SurfaceTexture`/`ImageReader` 冒充预览(小档,如 1280×960)。即 encoder+jpeg+preview **三流**。
  - `setRepeatingRequest(TEMPLATE_RECORD, targets=[encoderSurface, previewSurface])` 录像;录制中发一个 `capture(TEMPLATE_STILL_CAPTURE, target=jpegReader)` 单拍;`onImageAvailable` 落一张 jpeg。
  - 判定:会话能否建成(不抛 IllegalArgumentException / 不超 MAX_NUM_OUTPUT_STREAMS)、录像中单拍 JPEG 是否成功、录像是否不中断。全部 `Log.i("CAP2PROBE", …)`;jpeg 存 _probe/。
  - 若三流超硬件限制 → 记 "三流不支持",退化测 encoder+jpeg 双流(录像中拍照最低需求)是否 OK。
- [ ] **Step 2: `runAll()` 接上**。
- [ ] **Step 3: 构建 + 真机跑**——控制器验证:三流(或至少 encoder+jpeg)能否共存、录像中拍照是否成;记进 findings(直接决定 P2 "全分辨率录像中拍照 + 前台预览"可行性)。
- [ ] **Step 4: Commit** `feat(debug): capture2 probe — 3-stream (encoder+jpeg+preview) concurrency`

---

### Task 4: setLocation 元数据 + 汇总 P1 结论

**Files:** Modify `debug/Capture2Probe.kt`;Create `docs/superpowers/plans/.capture2-p1-findings.md`(结论汇总,供 P2 plan 引用)。

- [ ] **Step 1: setLocation 验证**——在 recordClip 的 muxer `start()` 前调 `muxer.setLocation(-36.85f, 174.76f)`(示例),录出 mp4;控制器拉回用工具/相册读位置元数据确认写入。`Log.i("CAP2PROBE", "setLocation written")`。
- [ ] **Step 2: 汇总**——控制器把 Task1-4 真机结果写进 `.capture2-p1-findings.md`:①各宽高比可用视频/JPEG/预览尺寸表 ②HEVC 编码器名 + 能出可播 mp4?码率/尺寸 ③降级 H.264 结论 ④三流并发结论(录像中拍照可行否)⑤setLocation 元数据可写否 ⑥满传感器 4:3 广角尺寸。**结论直接决定 P2 是否 GO 及用什么参数。**
- [ ] **Step 3: Commit** `docs: SP-Capture2-P1 findings (device probe results)`

---

## 交付后
- P1 findings → 写 **SP-Capture2-P2**(管线对等重写)的 plan,用实测尺寸/HEVC 参数。
- 探针代码(`debug/Capture2Probe.kt` + Diagnostics 临时按钮)在 P2 完成后移除或沉淀进正式相机层。
- 若 P1 发现 HEVC/三流有硬伤,回 brainstorm 调整 P2 方案(如降级 H.264、或录像中拍照仅特定档)。
