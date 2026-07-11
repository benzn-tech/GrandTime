# SP3a 采集核心设计:物理键真正驱动录像/拍照/录音/手电

日期:2026-07-11
状态:已与用户逐节确认
前置:主干(subproject-1-accepted)与 UI 换装(ui-reskin-accepted)已合并 main

## 1. 背景与目标

物理键管线(广播→短/长按判定→映射→派发)已真机闭环,但动作全是 `[stub]` 桩。
SP3a 把四类核心动作换成真实现:**录像(分段)、拍照、纯录音、手电**,录制文件落盘
到 Files 媒体目录并联动 UI。厂商灯光/警灯/夜视(police_system 反射)拆到 **SP3b**;
SOS 发送、视频上传仍为桩(SP4)。

**真机侦察事实**(2026-07-11,SDJW-F2SP):无 SD 卡插入(设计须支持插卡优先);
`police_system` 服务确认存在(SP3b 可行);三颗摄像头(后置/前置/疑似红外)。

**已确认决策**:
- 技术路线 = **CameraX**(VideoCapture/ImageCapture/CameraControl.enableTorch)+
  MediaRecorder(纯音频);不用裸 Camera2
- 后台豁免 = **SYSTEM_ALERT_WINDOW**(「显示在其他应用上层」,一次授权,息屏可录)
- 录像 = 后置摄像头 + 含音轨;录像中拍照/录音被忽略(互斥);录音与录像互斥
- 按键反馈 = 短振动(开始/停止/拍照)+ 通知文字 + Home 卡实时状态
- ADJUST_VOLUME = 媒体音量循环调档(沿 SMART-PTT 惯例)
- 存储 = SD 卡优先(未插卡落内置),App 专属目录,无需存储权限

## 2. 组件架构(新增 capture/ 包)

```
KeyActionDispatcher → CaptureActionHandler(替代 CoreService 里的桩 handler)
                          │
                     CaptureManager(状态机单例,单线程执行器串行化)
                     状态:IDLE / RECORDING_VIDEO(segmentIndex, startMillis)
                          / RECORDING_AUDIO(startMillis)
                     ├─ VideoRecorder   CameraX Recorder/VideoCapture,分段滚动
                     ├─ PhotoTaker      CameraX ImageCapture(JPEG 质量 95/80)
                     ├─ AudioRecorder   MediaRecorder(AAC/M4A 128kbps)
                     ├─ TorchController CameraX CameraControl / CameraManager
                     └─ VolumeCycler    AudioManager 媒体音量循环
                          │
                     MediaStorage(卷选择/目录/命名)
                          │
                     AppState.captureState: StateFlow<CaptureState>(UI 读)
                     AppState.mediaVersion: StateFlow<Int>(Files 刷新触发)
```

### 互斥表(CaptureManager 全部行为)

| 当前状态 \ 动作 | START_STOP_VIDEO | TAKE_PHOTO | START_STOP_AUDIO | TOGGLE_TORCH |
|---|---|---|---|---|
| IDLE | 开录像 | 拍照 | 开录音 | 开/关手电 |
| RECORDING_VIDEO | 停止收尾 | 忽略(震两下) | 忽略(震两下) | 允许 |
| RECORDING_AUDIO | 忽略(震两下) | 允许拍照 | 停止收尾 | 允许 |

ADJUST_VOLUME 任何状态可用。NONE/SEND_SOS/TOGGLE_VIDEO_UPLOAD/TOGGLE_WARNING_LIGHT
维持桩(通知提示 [stub])。

### 分段滚动

录像每满 N 分钟(SettingsStore.segmentMinutes,1/3/5/10)收尾当前段、无缝续录下一段
(CameraX 连续 Recording,段间间隙毫秒级);每段独立 mp4。手动停止时收尾当前段。

### 无预览后台采集

CaptureManager 自持 `LifecycleOwner`(手造 LifecycleRegistry,常驻 RESUMED),
CameraX bindToLifecycle 绑它,不依赖任何 Activity;录像无预览 Surface(仅
VideoCapture 用例)。

### 错误处理

- 相机打开失败/被抢:震两下 + 通知「Camera unavailable」,回 IDLE
- 开录前检查所选卷剩余空间 <200MB:拒绝,通知「Storage full」
- 录制中出错(CameraX Finalize error):当前段按已写内容收尾,回 IDLE,通知
- Service 被杀重启:状态归 IDLE(已收尾的段不丢;正在写的段由 CameraX finalize
  尽力保全)
- 每次动作起止均写探针日志(Diagnostics 可见,排障用)

## 3. 存储与文件

- **卷选择**:`context.getExternalFilesDirs(null)` 返回多卷时取第二卷(SD),
  否则第一卷(内置模拟卷);目录 `<卷>/media/{video,audio,photo}`(沿 Files 契约)
- **命名**:`VID_yyyyMMdd_HHmmss.mp4` / `AUD_yyyyMMdd_HHmmss.m4a` /
  `IMG_yyyyMMdd_HHmmss.jpg`(时间取段/文件的开始时刻,同秒冲突追加 `_1`)
- **格式映射**:video_quality 1080P/720P/480P → QualitySelector FHD/HD/SD,H.264;
  photo_quality High/Standard → JPEG 95/80;音频 AAC 128kbps 44.1kHz 单声道
- **Files 页联动**:`MediaEntry` 增加 `durationMillis: Long?`(视频/音频行显示
  mm:ss,照片 null 不显示;时长用 MediaMetadataRetriever 读,IO 线程);
  Files 改为收集 `AppState.mediaVersion`,变化即重扫(修终审遗留的一次性扫描问题)
- **Home 卡联动**:captureState 驱动——RECORDING_VIDEO → 红点 +「Recording 00:32」
  计时(每秒刷新);RECORDING_AUDIO →「Recording audio 00:32」;IDLE 回「Standing by」

## 4. 权限与豁免流

- Manifest 新增:`CAMERA`、`RECORD_AUDIO`(运行时)、`VIBRATE`、`SYSTEM_ALERT_WINDOW`
- **首启向导**(MainActivity):①相机+麦克风运行时弹窗(一起请求);②引导跳
  `ACTION_MANAGE_OVERLAY_PERMISSION` 授「显示在其他应用上层」,文案:
  「Required so physical keys can record while the screen is off」
- **缺权限提示**:Home 卡黄色警示行「Tap to finish setup」(warning 色),点击重入
  向导;缺权限时对应动作拒绝执行并通知提示,不崩溃
- `foregroundServiceType` 维持不声明(targetSdk 33 合法);**升级注意**:升 34 时
  必须补 `camera|microphone` 类型与对应清单权限
- 通知权限已有;振动无需运行时授权

## 5. 工程结构变化

```
capture/CaptureManager.kt      状态机(纯逻辑核可单测:CaptureCore)
capture/CaptureState.kt        sealed:Idle / RecordingVideo / RecordingAudio
capture/VideoRecorder.kt       CameraX 录像封装(分段滚动)
capture/PhotoTaker.kt          CameraX 拍照封装
capture/AudioRecorder.kt       MediaRecorder 纯音频封装
capture/TorchController.kt     手电
capture/VolumeCycler.kt        媒体音量循环
capture/MediaStorage.kt        卷选择/目录/命名(纯逻辑可单测)
capture/ServiceLifecycleOwner.kt 手造常驻 RESUMED LifecycleOwner
service/CoreService.kt         桩 handler → CaptureActionHandler;captureState→通知文字
core/AppState.kt               +captureState、+mediaVersion
ui/HomeScreen.kt               录制状态卡(红点计时)+ 缺权限警示行
ui/FilesScreen.kt              MediaEntry.durationMillis + mediaVersion 触发重扫
ui/MainActivity.kt             首启权限向导
gradle/libs.versions.toml      +androidx.camera:camera-{core,camera2,lifecycle,video} 1.4.x
AndroidManifest.xml            +4 权限
```

逻辑层 hardware/keymap/auth/boot 零改动。

## 6. 测试与验收

**JVM 单测**:互斥表全组合(CaptureCore 注入假录制器)、分段触发计时、文件命名
(含同秒冲突)、格式映射、SD 优先卷选择(注入假卷列表)、空间不足拒绝。

**真机验收清单**(SP3a 完成门):
1. 亮屏录像键短按→开录(震动+Home 红点计时+通知),再按→落盘;Files 立即出现,
   系统播放器可播,画质符合设置
2. **息屏口袋按键→掏出已在录**(SAW 豁免硬验证)
3. 1 分钟档连录 2.5 分钟→3 个文件(≈60/60/30s),回放无缺帧感
4. 拍照/手电/录音/音量键逐一验证
5. 录像中按拍照、录音→被忽略不打断
6. 重启→待命→直接按键可录(开机自启路径下豁免成立)
7. 改清晰度/分段→下次录像生效

## 7. 范围外

police_system 警灯/夜视/补光(SP3b)、SOS 发送与上传(SP4)、录像中拍照
(video snapshot,SP3b 评估)、外置 SD 实测(无卡,逻辑已备+单测覆盖)、
MediaStore 公共相册曝光(现阶段 App 内 Files 页足够)。
