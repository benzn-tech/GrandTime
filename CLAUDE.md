# GrandTime — Claude 项目指南

F2SP 执法终端上的现场作业记录仪原生 Android App(Kotlin/Compose),替代商用 SMART-PTT(com.corget),是 FieldSight 的移动客户端。包名 `com.benzn.grandtime`,GitHub `benzn-tech/GrandTime`,Kotlin 2.1 / AGP 8.7 / minSdk=targetSdk 33 / compileSdk 35。

**沟通**:中文回复;汇报用 已完成/如何/影响 格式。

## 当前状态(2026-07-15)

- **已上线**:主干采集(SP1)、UI 换装、SP3a/SP3c 采集、SP2 Cognito 登录、SP4 上传+工地选择、SP3b 物理灯语、**SP-Capture2-P2(相机层 CameraX→Camera2+OpenGL+MediaCodec+音频 重写)** —— 全部合并 `main` + 推 GitHub + 真机验收。P2 tag `sp-capture2-p2-accepted`。
- **下一步(已设计+计划,未实施)**:**SP-Capture2-P3(GPS 水印 + gps_track)**。spec + plan 已 push,直接接 SDD 执行即可。见 `docs/superpowers/2026-07-15-session-handoff.md`。
- `main` 与 origin 同步。当前无活跃 feature 分支(P3 执行时新建)。

## 架构(采集核心 = Camera2 管线)

`capture/camera2/`(P2):`Camera2Pipeline`(门面:CameraDevice + CaptureSession **固定 2 路输出**[GL 相机 SurfaceTexture + JPEG ImageReader])、`GlRecordPipeline`(相机 OES 纹理 → GL 画到编码器面+预览面)、`SegmentRecorder`(HEVC→AVC 降级 + AAC 音频轨,muxerLock 串行双轨)、`VideoSizeSelector`(选码尺寸 ≤1920×1088)。**核心不变式**:预览挂摘/分段起停/拍照/手电**都不重配相机会话**(GL 目标切换,故不中断录像)。
`capture/`:`CaptureManager`(编排:状态机+DB+上传+灯+GPS)、`CaptureCore`(纯状态机)、`AudioRecorder`、`MediaStorage`。
其它:`auth/`(Cognito 裸 HTTP)、`upload/`(WorkManager)、`db/`(Room)、`net/`(RecordingsApiClient/SitesApiClient)、`ui/`(Compose)、`core/AppState`+`SettingsStore`。

## 开发/真机工作流(重要)

- **构建**:`JAVA_HOME` 未设 → 用 Android Studio 自带 JDK:Git Bash `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` 再 `./gradlew`。
- **Dropbox 构建锁**:仓库在 Dropbox 里,gradle 偶发 `java.io.IOException: Could not delete '...build...'` —— **重跑一次即过**,非真失败。
- **adb**:`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`;真机 `F2S202503103054`。设备**常处横屏**(rotation 1/3,坐标系 640×480 变),UI 自动化用 `uiautomator dump` 动态取控件 bounds 别硬编坐标;掉线用 `adb reconnect`/重启 server;`adb pull` 用 `MSYS_NO_PATHCONV=1 ... //sdcard/...`。物理键是 lolaage 广播(F2spActionParser),真机验收需用户上手。
- **媒体验证**:本机有 ffmpeg/ffprobe(`$LOCALAPPDATA/Microsoft/WinGet/Packages/Gyan.FFmpeg_*/...bin`),用来验录制 mp4 编码/分辨率/音轨、抽帧看画面。
- **测试**:`./gradlew testDebugUnitTest`(现 106 绿)。相机/GL/GPS 类无 JVM 单测,靠真机验证。
- **权限**:CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS 走 `MainActivity` 的 RequestMultiplePermissions;MANAGE_EXTERNAL_STORAGE/SYSTEM_ALERT_WINDOW 走 Settings intent。

## 硬约束

- **全 Android framework,不引原生库/新 Gradle 依赖**(设备 ABI 仅 armeabi 32 位)。
- **无 Google Play Services 保证**(MediaTek):GPS 用 `LocationManager` 不用 FusedLocation。
- **开发产物一律英文**(代码注释/提交信息/技术文档,2026-07-15 起用户指定;存量中文注释不回改);用户可见文案英文;与用户的对话交流用中文。
- 视频硬编上限 **1920×1088**;4:3=1440×1080 / 16:9=1920×1080;HEVC 默认降 H.264。
- 不反编译/抄厂商代码(只取接口事实,干净重写);SMART-PTT 只禁用(`pm disable`,恢复 `pm enable com.corget`)。

## Superpowers 流程

每个子项目:`brainstorming`(→ spec `docs/superpowers/specs/`)→ `writing-plans`(→ plan `docs/superpowers/plans/`)→ `subagent-driven-development`(逐任务:实现子代理 + 审查子代理 + 修复循环;终审用 Fable 5)→ 真机验收 → `finishing-a-development-branch`(合 main + tag + push)。SDD 台账 `.superpowers/sdd/progress.md`(gitignored)。**逐组件真机验证**(P1/P2 那样先探针定生死)。

## 后端(跨仓)

FieldSight 后端在 `C:/Users/camil/Dropbox/fieldsight-pipeline`(Python/psycopg3/SAM,Aurora PG16)。移动端连的是 **fieldsight-test 栈**(org API `wdsgobb7b0`,桶 `fieldsight-data-test-509194952652`)。CI/CD:合 `develop` → sam deploy fieldsight-test(自动迁移);合 `main` → prod(审批门)。`recordings` 表(迁移 0009)含 `gps_track jsonb` 列(P3 用)。
