# SP3c 采集可见性与录制 UX 设计:公共存储 + 缩略图 + 全屏预览

日期:2026-07-12
状态:已与用户逐节确认
前置:SP3a 采集(含抓帧)已合并 main;SP2 登录 spec 已写、暂挂(SP3c 后再实现)

## 1. 背景与目标

用户真机试用发现三个"信任级"问题:①录制文件在安卓 Files/相册里找不到(SP3a 写在
app 私有目录 `Android/data/...`,被 scoped storage 隐藏);②按录像没有明确的"正在录"
反馈;③想要缩略图浏览。SP3c 让录制"看得见、找得到、像 SMART-PTT"。

**真机实况核实**(2026-07-12,F2S202503103054):文件其实都在
`/sdcard/Android/data/com.benzn.grandtime/files/media/`(视频 27/音频 3/照片 9),
录制链路正常——纯粹是存储位置被系统隐藏。

**北极星(统领全局)= 省电、提升续航、最大化录制时长。** 每个技术选择都往这靠。

**已确认决策**:
- 存储 = **单个公共文件夹**,根 = `/sdcard/FieldSight/`(设备内置共享存储;**有可移动
  SD 卡时优先写 SD** 的 FieldSight/,服务续航/容量目标),配 MANAGE_EXTERNAL_STORAGE
  「所有文件访问」权限(设置页授权,并入权限向导);最贴近 SMART-PTT
- **目录分层**:本期(SP3c,登录前)用**设备级** `<根>/FieldSight/device/{video,audio,photo}`;
  SP2 登录后套**用户层** `<根>/FieldSight/<用户名>_<user-id>/{video,audio,photo}` 并迁移旧文件
- **命名**:本期 `VID_/AUD_/IMG__yyyyMMdd_HHmmss.<ext>`(稳定清晰);SP2 登录后改
  `<用户名>_yyyyMMdd_HHmmss.<ext>`
- **视频编码 = H.265/HEVC**(硬件编码,同画质省 ~40-50% 体积 = 录更久、写盘少、省电);
  音频 AAC/M4A 128kbps;照片 JPEG。回放兼容性代价可接受(专用设备+现代播放器)
- **照片精度设置**:SettingsStore 增 `photo_resolution`(Max 5MP / High ~3MP / Std ~1MP),
  与现有 photo_quality(JPEG 压缩 High/Standard)并存;单独拍照默认满传感器 5MP
- **开录/停录系统提示音**:`MediaActionSound`(安卓内置 START/STOP_VIDEO_RECORDING、
  SHUTTER_CLICK;零延迟、无音频文件、任何 ROM 都有)
- **录制中自动熄屏保电**:录像开始 N 分钟(默认 3,可设)后自动熄屏,**录像后台继续**
  (服务驱动,不依赖屏幕);任意物理键/触屏唤醒回预览
- **分段 = 每段独立文件 + 接受段间 <100ms 空隙**(一路相机无法真正 overlap);
  每段收尾即可独立上传(准实时"流"= **SP4** 每段落盘即传,`upload_status` 已备);真·直播推流
  是独立大工程,不在范围
- 录制预览 = **全屏录制界面**(前台按录像→进全屏摄像头预览;停止→回主页)
- 前台/后台分流:息屏/口袋物理键录制维持无界面后台录(SP3a 现状),不弹预览
- 缩略图 = Files 页改缩略图网格,视频取首帧、照片取缩略图,用 **Coil**(唯一新依赖)
- 前置红灯闪烁(录制指示)归 **SP3b**(厂商 police_system/lolaage 灯光),本期不做

## 2. 全屏预览 × 后台录像(核心架构)

**张力**:SP3a 录像是服务持有相机、无预览(息屏可录);全屏预览需 Activity 的
Surface。二者并存方案:

- **CameraSession 支持可选 Preview 用例**:新增 `attachPreview(surfaceProvider)` /
  `detachPreview()`。RecordingScreen 的 PreviewView 前台可见时,把 surfaceProvider 交给
  服务持有的 session,session 重绑为 Video+Preview;界面离开/息屏则 detach,回 Video-only
  (录像不中断——CameraX 重绑在录制中需谨慎,见下)。
- **surface 传递**:经共享单例(AppState 或 CaptureManager 引用)传 SurfaceProvider,
  不用 AIDL。CaptureManager 已是 Service/UI 可达的单例雏形。
- **导航**:MainActivity 观察 `AppState.captureState`——转 RecordingVideo 且 App 前台
  → 进 RecordingScreen(Screen.RECORDING);转 Idle → 退回 Home。息屏时无 Activity,
  不导航(物理键后台录,SP3b 红灯提示)。
- **流组合**:Video+Preview = 两条 PRIV 流,CameraX 担保组合,**1080p 预览录像不降**
  (不同于"录像+JPEG"被压 640×480)。**真机验收项**:480P 档"预览+录像+拍照"三流可能
  超限——若超限,预览态下 480P 的录像中拍照也走抓帧(与 1080p 一致),即预览态一律不绑
  ImageCapture,拍照统一抓帧。
- **录制中重绑的安全性**:CameraX 在 active Recording 期间 unbind/rebind 会中断录制。
  因此 attach/detach Preview 采用「只增删 Preview 用例、不动 VideoCapture 绑定」的方式
  (ProcessCameraProvider 支持在已绑基础上追加/移除用例);**真机验证**追加 Preview
  不打断进行中的 Recording。若该设备不支持热加 Preview:退化为"进 RecordingScreen 时若
  正在录,先无缝滚段再带 Preview 重绑"(复用抓帧的滚段机制),记为验收分支。

### RecordingScreen(全屏)
Compose:`AndroidView(PreviewView)` 铺满;顶部浮层——红点 + "REC 00:32" 计时
(error 色);底部大「Stop」按钮(也可物理键停)。深色沉浸(隐藏系统栏)。仅录像态存在。

## 2.6 省电与录制时长(北极星落地)

- **自动熄屏**:录像态起 N 分钟(SettingsStore `screen_off_minutes`,默认 3,可设
  1/3/5/Never)后,若仍在录且屏幕亮,调 `PowerManager`/降亮度或释放屏幕常亮,让屏幕熄灭;
  录像后台继续(服务已可后台录);唤醒(物理键/触屏)回 RecordingScreen 重挂预览。
- **预览按需**:仅前台且屏亮时绑 Preview;熄屏/后台 detach,省编码+GPU+屏幕功耗。
- **H.265**:同容量录更久、写盘 IO 少 → 省电 + 长时长(北极星主杠杆)。
- **无多余 wakelock**:录制本身由前台服务保活,不额外持 CPU wakelock;熄屏靠系统。
- 验收含"熄屏后录像持续 + 唤醒回预览"与"H.265 文件体积对照 H.264"两项。

## 2.7 提示音

`MediaActionSound`(懒加载单例):开录像/开录音 → `START_VIDEO_RECORDING`;停 →
`STOP_VIDEO_RECORDING`;拍照 → `SHUTTER_CLICK`。与现有震动/通知并行,不阻塞。

## 3. 公共存储迁移

- **MediaStorage 重写**:根 = 可移动 SD 卷(若存在)否则 `Environment.getExternalStorageDirectory()`
  (`/sdcard`),下 `FieldSight/device/{video,audio,photo}`(SP2 后 `device`→`<用户名>_<user-id>`)。
  写文件需 `Environment.isExternalStorageManager()` 为真(MANAGE_EXTERNAL_STORAGE 已授)。
  SD 卷探测:`getExternalFilesDirs` 第二卷父级回推到公共根,或 StorageManager 枚举 removable。
  命名本期 `VID_/AUD_/IMG__yyyyMMdd_HHmmss`,SP2 后前缀换用户名。
- **权限向导扩展**(MainActivity + Home 警示):在现有 相机/麦克风/通知/悬浮窗 之后,
  加一步引导 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`(设置页),文案「Required to
  save recordings to a folder you can browse」。缺此权限时录制拒绝执行 + 通知提示
  「Storage access required — finish setup」;Home 警示行合并检查此项。
- **媒体库收录**:每个文件写完 `MediaScannerConnection.scanFile(path)`,让系统相册/
  Files 立即索引可见。
- **DB**:`capture_records.filePath` 存新公共路径(绝对路径,MANAGE 权限下可直接
  File 访问);其余字段不变。FilesReconciler 的 scanDisk 根目录改为 `/sdcard/FieldSight`。
- **旧文件**:app 私有目录旧文件一次性迁移到公共目录(启动时若检测到旧目录非空,move
  + DB 路径更新);迁移为幂等、失败不阻断。
- **权限被拒降级**:未授 MANAGE 时,录制不启动并提示;不崩溃、不静默丢。

## 4. 缩略图网格(Files 页)

- 依赖:`io.coil-kt:coil-compose` + `coil-video`(视频帧解码)。
- 布局:`LazyVerticalGrid`(3 列)缩略图;视频=首帧 + 时长角标 + ▶ 覆盖;照片=缩略图;
  音频=波形/音符占位图标(无帧)+ 时长。仍按日期分组(组头横跨整行)。
- 点缩略图 → 系统 `ACTION_VIEW`(FileProvider content:// 或公共路径 file://,MANAGE 下
  可直接文件 URI)打开播放/查看。
- 数据源不变:Room `observeAll()` Flow;Coil 按 filePath 异步加载缩略图,自带内存+磁盘缓存。
- 空态不变(「No recordings yet」)。

## 5. 工程结构变化

```
capture/MediaStorage.kt        根改公共(SD 优先)FieldSight/device;+isExternalStorageManager 门;SD 卷探测
capture/CameraSession.kt       +attachPreview/detachPreview;预览态不绑 ImageCapture;录像 QualitySelector 保持
capture/VideoRecorder.kt       Recorder 输出 H.265:Recorder.Builder().setVideoCodec 或经 MediaFormat/EncoderProfiles HEVC(真机确认 CameraX 1.4 HEVC 路径)
capture/CaptureManager.kt      持有 surfaceProvider;scanFile 收录;attach/detach 联动;起停调 MediaActionSound;熄屏计时
capture/MediaMigrator.kt       新增:旧私有目录→公共目录一次性迁移(幂等)
capture/CaptureSounds.kt       新增:MediaActionSound 懒加载封装(start/stop/shutter)
core/SettingsStore.kt          +photo_resolution(Max/High/Std)、+screen_off_minutes(1/3/5/Never,默认3)、+video_codec 若做成开关(本期定 H.265,先不做开关)
core/AppState.kt               +previewSurfaceRequest(或经 CaptureManager 直传);Screen 加 RECORDING
ui/RecordingScreen.kt          新增:全屏 PreviewView + 红点计时 + Stop;熄屏后唤醒重挂
ui/Screen.kt                   +RECORDING
ui/MainActivity.kt             captureState==RecordingVideo && 前台 → 进 RecordingScreen;Idle → 退回
ui/FilesScreen.kt              列表 → Coil 缩略图网格(LazyVerticalGrid)
ui/SettingsScreen.kt           +照片精度 + 自动熄屏时长 两个选择项
ui/HomeScreen.kt               权限警示合并「所有文件访问」检查
db/FilesReconciler.kt          scanDisk 根目录改公共 FieldSight/device
gradle/libs.versions.toml      +coil-compose 2.7、+coil-video 2.7
app/build.gradle.kts / Manifest +MANAGE_EXTERNAL_STORAGE
```

逻辑层 hardware/keymap/auth/boot 不动;CaptureCore 状态机不动(RECORDING 是 UI 态,
由 captureState 派生,非新采集状态)。

## 6. 错误处理与边界

- 前台录像中被系统回收/切后台:detachPreview,录像继续(后台无界面);回前台重 attach。
- attach Preview 失败(设备不支持热加):走滚段重绑退化路径(§2),仍不丢录像。
- 缩略图解码失败:占位图标,不崩。
- 迁移中断(空间/权限):记日志,保留旧文件,下次启动重试。
- MANAGE 权限运行时被撤销:录制拒绝 + 提示,已录文件不受影响。

## 7. 测试与验收

**JVM 单测**:MediaStorage 新根路径/命名、MediaMigrator 迁移决策(假文件树:空/有旧文件/
已迁移幂等)、FilesReconciler 新根目录扫描。

**真机验收**(F2S202503103054,SP3c 完成门):
1. 权限向导走通「所有文件访问」授权;录一段视频 → **安卓自带 Files 里
   `FieldSight/device/video` 直接可见**,系统相册也能看到(scanFile 生效)
2. 前台按录像 → 进全屏预览、镜头内容实时可见、红点计时 + **开录提示音**;按停止 → 回主页
   + 停录提示音;文件落公共目录
3. 预览态录像分辨率仍 1080p 且 **编码为 HEVC**(ffprobe 确认 codec=hevc、未降分辨率);
   同段景 H.265 vs H.264 体积对照(证明省容量)
4. 息屏物理键录像 → 仍后台录成功(不弹预览),文件落公共目录
5. **录制中 N 分钟后自动熄屏、录像持续**(dumpsys 确认服务前台 + 文件仍在增长);
   唤醒 → 回预览
6. Files 页 → 缩略图网格:视频显首帧、照片显缩略图、点开能播
7. 单独拍照按 `photo_resolution` 出片(Max=5MP);改档位后分辨率随之变(ffprobe/尺寸确认)
8. 旧 app 私有目录文件迁移到公共目录,不丢记录
9. 480P 档:预览+录像+(拍照)真机核实——不超限则拍照出片,超限则拍照走抓帧(记录实况)

**不做**:红灯指示(SP3b)、云端上传(SP4)、真·直播推流、用户层目录(SP2 后)、
视频剪辑/相册管理、多选删除。

## 8. 范围外

前置红灯 1Hz 闪烁(SP3b 厂商灯光)、每段收尾即传的准实时上传(SP4,`upload_status` 已备)、
真·直播推流(独立大工程)、用户名层目录与用户名文件名(SP2 登录后套上并迁移)、
登录(SP2,spec 已就绪待实现)、视频剪辑/相册管理。
