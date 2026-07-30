# GrandTime — Claude 项目指南

F2SP 执法终端上的现场作业记录仪原生 Android App(Kotlin/Compose),替代商用 SMART-PTT(com.corget),是 FieldSight 的移动客户端。包名 `com.benzn.grandtime`,GitHub `benzn-tech/GrandTime`,Kotlin 2.1 / AGP 8.7 / minSdk=targetSdk 33 / compileSdk 35。

**沟通**:中文回复;汇报用 已完成/如何/影响 格式。

## 当前状态(2026-07-30)

- **prod 线上 = 0.5.7**(versionCode 11,签名 release,tag `v0.5.7`,连客户湖 `fieldsight-data-509194952652`)。dev 版桌面标签叫 **devfieldsight**(见记忆 [[grandtime-dev-label-convention]])。
- **已上线到 prod**(按时间):SP1-4 采集/登录/上传、SP3b 灯语、SP-Capture2-P2/P3(Camera2+GL+水印+GPS)、录像中拍照预览黑修复、**视频 Pause/Resume + 息屏省电暂停**、**音频 Pause/Resume**(音频键:空闲长按=调音量、录音中短按=暂停、长按=结束、空闲短按=开始)、**P0 chunk-session**(文件名 `_sid{32hex}_c{NNNN}`+session open/close+≤2min 快报告链路)、**30s 滚动分段 + 文件页/首页按"整段录制"归一**(UI 归一非物理合并)、**release 签名**(keystore.jks + keystore.properties 均 gitignored,**必须备份**,口令见早期会话)。
- **进行中:扫码登录(QR passwordless sign-in)** —— spec + 三份计划已完成**未开始实现**。0.5.7 里已有登录页"Scan QR to Sign in"按钮 + Camera2/ZXing 扫码界面,但**当前只解码不真登录**(探针)。详见下方"扫码登录"节 + 记忆 [[grandtime-qr-login]]。
- `main` 与 origin 同步。当前无活跃 feature 分支。

## 扫码登录(QR sign-in)—— 下一步要做的事

**目标**:终端扫 web 出示的一次性二维码 → 走 Cognito 自定义认证(passwordless)→ 终端拿到**自己的** token 登录,免在硬键盘打字。**跨 3 仓**。

- **设计已定**(spec `docs/superpowers/specs/2026-07-30-qr-login-design.md`):流向=终端扫 web 码;架构=Cognito CUSTOM_AUTH(因 web/移动端 app client 不同 + 后端无法凭空签发 token);v1 只自助;码 TTL 90s 单次;Verify 按 `userAttributes.sub` 比对;码表+create 全放 prod(兑换 Cognito 直连、环境无关)。
- **三份实施计划**(逐任务 TDD,共 12 任务):
  - Backend `C:/Users/camil/Dropbox/fieldsight-pipeline/docs/superpowers/plans/2026-07-30-qr-login-backend.md`(本地 commit `9032acc`,分支 feat/session-continuity,**未推**——推 develop 触发部署)
  - Mobile `docs/superpowers/plans/2026-07-30-qr-login-mobile.md`(已推 main)
  - Web `.../fieldsight-pipeline/docs/superpowers/plans/2026-07-30-qr-login-web.md`(本地 commit `cbb371c`,未推)
- **执行顺序**:后端(Task1-3 纯代码/模板本地可测 → Task4 动**共享 prod Cognito 池**改 app client + 挂触发器,`describe→merge→update`,权限受限**须用户 `!` 亲自跑**;Task4 Step5 纯 CLI 端到端演练即可验后端,免移动端)→ 移动 → web。
- **待用户选执行方式**:Subagent-Driven(推荐)或 Inline,选定后从后端 Task 1 开工。

## 架构(采集核心 = Camera2 管线)

`capture/camera2/`(P2):`Camera2Pipeline`(门面:CameraDevice + CaptureSession **固定 2 路输出**[GL 相机 SurfaceTexture + JPEG ImageReader])、`GlRecordPipeline`(相机 OES 纹理 → GL 画到编码器面+预览面)、`SegmentRecorder`(HEVC→AVC 降级 + AAC 音频轨,muxerLock 串行双轨)、`VideoSizeSelector`(选码尺寸 ≤1920×1088)。**核心不变式**:预览挂摘/分段起停/拍照/手电**都不重配相机会话**(GL 目标切换,故不中断录像)。
`capture/`:`CaptureManager`(编排:状态机+DB+上传+灯+GPS)、`CaptureCore`(纯状态机)、`AudioRecorder`、`MediaStorage`。
其它:`auth/`(Cognito 裸 HTTP)、`upload/`(WorkManager)、`db/`(Room)、`net/`(RecordingsApiClient/SitesApiClient)、`ui/`(Compose)、`core/AppState`+`SettingsStore`。

## 开发/真机工作流(重要)

- **构建**:`JAVA_HOME` 未设 → 用 Android Studio 自带 JDK:Git Bash `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` 再 `./gradlew`。
- **Dropbox 构建锁**:仓库在 Dropbox 里,gradle 偶发 `java.io.IOException: Could not delete '...build...'` —— **重跑一次即过**,非真失败。
- **adb**:`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`;真机 `F2S202503103054`。设备**常处横屏**(rotation 1/3,坐标系 640×480 变),UI 自动化用 `uiautomator dump` 动态取控件 bounds 别硬编坐标;掉线用 `adb reconnect`/重启 server;`adb pull` 用 `MSYS_NO_PATHCONV=1 ... //sdcard/...`。物理键是 lolaage 广播(F2spActionParser),真机验收需用户上手。
- **媒体验证**:本机有 ffmpeg/ffprobe(`$LOCALAPPDATA/Microsoft/WinGet/Packages/Gyan.FFmpeg_*/...bin`),用来验录制 mp4 编码/分辨率/音轨、抽帧看画面。
- **测试**:`./gradlew testProdDebugUnitTest`(现 163 绿)。相机/GL/GPS 类无 JVM 单测,靠真机验证。构建按 flavor 分:`assembleProdDebug`/`assembleDevDebug`。
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

FieldSight 后端在 `C:/Users/camil/Dropbox/fieldsight-pipeline`(Python/psycopg3/SAM,Aurora PG16)。移动端现按 flavor 分连不同栈:**prod flavor(默认/出货)** 连 prod org gateway `ys94qy2tk0`,湖 `fieldsight-data-509194952652`;**dev flavor** 连 fieldsight-test 栈(org API `wdsgobb7b0`,桶 `fieldsight-data-test-509194952652`)。CI/CD:合 `develop` → sam deploy fieldsight-test(自动迁移);合 `main` → prod(审批门)。`recordings` 表(迁移 0009)含 `gps_track jsonb` 列(P3 用)。
