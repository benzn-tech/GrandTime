# F2SP 现场作业终端 App —— 技术拆解与自研规格书（SPEC）

> 版本：v1.0　|　日期：2026-07-13　|　目标平台：Android（联发科 MT6768 执法终端 F2SP / 普通安卓手机降级）
>
> 本文档分两部分：
> **第一部分（§3）** 是对现有 `SMART-PTT`（包名 `com.corget`, v10.1.7.124）在 F2SP 设备上的**逆向技术拆解**，所有结论来自对该 APK 的静态反编译（androguard + DEX 反编译），可作为事实依据。
> **第二部分（§4 起）** 是在此基础上设计的**自研 App 规格**：去除 PTT，新增开机自动登录、项目选择、语音激活、语音对话。

---

## 0. 修订记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-07-13 | 首版：完成源 App 逆向拆解 + 自研模块规格 |

## 0.1 合规声明（必读）

- 源 App `com.corget` 为**商业闭源产品**，`com.lolaage.policesystem`（`police_system` 服务）、鼎桥 `com.tdtech.*` 等为**厂商私有 SDK**。本文档仅用于**接口层面互操作**分析，**不得反编译照搬其代码**。
- `lolaage.*` / `com.tdtech.action.KEYEVENT_*` 是设备 ROM **对外广播的公开接口**，任何 App 动态注册即可合法监听——这是自研 App 复用物理键的**合法**方式。
- `police_system` 系统服务、GPIO sysfs 节点属于设备 ROM 能力，调用它需要设备厂商提供对应 SDK/权限或将你的包名加入系统白名单。**务必向 F2SP 硬件供应商索取 `police_system` SDK 与自启白名单授权。**

---

## 1. 背景与目标

### 1.1 背景
F2SP（`Build.MODEL` = `SDJW-F2SP` / `F2S-A`）是一款基于 MT6768、运行定制 Android ROM 的**执法记录仪 / 现场作业终端**。现有 `SMART-PTT` App 提供对讲、录像、拍照、录音、定位上传、灯光/夜视、人脸识别等能力，并与设备物理键深度联动。

### 1.2 目标
打造一款自研移动端 App，功能对标 SMART-PTT，但：
- ❌ **不含 PTT（一键对讲）**
- ✅ **开机自动登录**
- ✅ **手动选择项目 / 语音激活项目**
- ✅ **语音对话**（AI 语音助手：语音识别 → 大模型 → 语音播报）
- ✅ 保留：录像、拍照、录音、GPS 位置水印、物理键联动、灯光/夜视、H265 编码

### 1.3 术语表
| 术语 | 含义 |
|---|---|
| Handler（设备处理类） | 按 `Build.MODEL` 选定的、封装某型号物理键与硬件差异的类 |
| lolaage.* | F2SP ROM 广播的物理键事件 action 前缀 |
| police_system | F2SP ROM 提供的系统服务，控制 LED/红外/警灯 |
| AudioSource | 安卓 `MediaRecorder.AudioSource` 常量，决定物理麦路由 |
| 烧录水印 | 把文字/坐标直接绘制进视频像素帧（不可去除） |
| STT / TTS | 语音识别 / 语音合成 |

---

## 2. 目标设备与运行环境

| 项 | 值 |
|---|---|
| SoC | MediaTek MT6768（Android，A/B 分区） |
| Build.MODEL | `SDJW-F2SP`、`F2S-A`（同族 `SDJW-F2S`、`XB-15`） |
| ABI | **仅 `armeabi`（32 位 ARM）** —— 自研原生库需提供 armeabi/armeabi-v7a |
| 摄像头 | 2 颗（Camera2 ID `0`/`1`），支持红外夜视 |
| 特有服务 | `police_system`（`PoliceSystemManager`）：LED / IR / 警灯 |
| 物理键接口 | ROM 广播 `lolaage.*`（详见附录 A） |
| 固件 | `F2SP_V01_DQ_ZX_GPS_20260205_1000`（含 GPS 更新） |

**降级策略（普通安卓手机）**：无 `police_system`、无 `lolaage` 物理键、可能无红外——通过硬件抽象层（§5.3）自动降级为屏幕按钮 / 音量键触发，隐藏灯光/夜视功能。

---

## 3. 源 App 逆向技术拆解（事实依据）

### 3.1 应用概况

| 项 | 值 |
|---|---|
| 包名 / 应用名 | `com.corget` / `SMART-PTT` |
| 版本 | versionName 10.1.7（versionCode 10107） |
| minSDK / targetSDK | 22 / 34 |
| DEX | `classes.dex`(9.3MB) + `classes2.dex`(6.0MB) |
| 原生库 | 仅 `lib/armeabi/`（32 位），见附录 C |
| 核心组件 | 主界面 `com.corget.MainView`；核心常驻服务 `com.corget.PocService` |
| 设备适配 | `com.corget.device.handler` 下 **454 个设备处理类**，运行时按机型选择 |

**设备识别与 Handler 选择**（`DeviceHandlerManager.InitDeviceHandler`）：
```
Config.isSDJWF2SDevice() == true
  ⇔ Build.MODEL ∈ { "SDJW-F2S", "F2S-A", "SDJW-F2SP", "XB-15" }
  ⇒ deviceHandler = new ZfySDJWF2S(service)
```
> ⚠️ F2SP 用的是 **`ZfySDJWF2S`（lolaage 体系）**，不是 `ZfyEC520`（后者是 `EC520` 型号用的鼎桥 `com.tdtech.*` 体系）。清单里声明的 `com.tdtech.permission.*_KEY`、`com.motorolasolutions.*` 权限是给其它机型用的。

### 3.2 物理键机制

**注册**：`PocService.registerDynamicReceiver()` 动态注册 `BroadcastReceiver`（非 Manifest 静态声明），监听 `lolaage.*` 全部 action（见附录 A）。
**分发**：`ZfySDJWF2S.onReceive(action, ctx, intent)` 按 action 分派。
**短按/长按判定**：`.down` 到达时 `isShortPress=true` 并 `postDelayed(longClickCallback, 1000ms)`；
- 1 秒内 `.up` → 取消计时器，`isShortPress` 仍为 true → 触发**短按**动作；
- 按住满 1 秒 → `longClickCallback` 执行：`isShortPress=false` + 触发**长按**动作；随后的 `.up` 因 `isShortPress=false` 不再触发短按。

**F2SP 原始键映射**（`ZfySDJWF2S`）：

| 物理键 | 广播 action | 短按（<1s） | 长按（≥1s） |
|---|---|---|---|
| 对讲键 | `lolaage.ptt.down/up` | 按下 `OnStartPtt()` / 松开 `OnEndPtt()` | — |
| 录像键 | `lolaage.video1.down/up` | `switchRecordVideo()` 开/停录像 | `switchUploadVideo()` 切换上传 |
| 拍照键 | `lolaage.take.picture.down/up` | `takePhoto()` 拍照 | `switchFlash()` 开/关手电 |
| 录音键 | `lolaage.audio.down/up` | 调节音量（循环） | `switchRecordAudio()` 开/停录音 |
| SOS 键 | `lolaage.sos.down/up` | `sendSOSData()` 报警 | `switchWarningLightTimer()` 警灯 |

### 3.3 摄像头子系统

- **API**：Camera2（`VideoCamera2Manager`），`cameraManager.openCamera(String.valueOf(id), stateCallback, handler)`。
- **相机数量与 ID**：`VideoRecoderManager.getNextCameraId()` 对 F2SP 特判——轮到 ID `2` 时绕回 `0`，即在 **`0`↔`1`** 间循环 ⇒ **2 颗摄像头**。
- **分辨率**：`setPreferredResolution()` 中 `SDJW-F2SP` @1440 档 → `previewWidth=1920, previewHeight=1440`；其余按 `SCALER_STREAM_CONFIGURATION_MAP` 匹配。
- **预览帧率**：读 `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 后 `set(CONTROL_AE_TARGET_FPS_RANGE, …)`。
- **多后端**（其它机型）：大华 `android.dahua.camera.DHCameraManager`、美格 `com.meige.autosdk.camera.PoliceCameraManager`、USB 外接 `UVCCameraManager`（`libuvc.so`/`libusb_100.so`）。F2SP 只用标准 Camera2。

### 3.4 录像与编码

- **编码器**：MediaCodec，MIME `video/hevc`（H265）或 `video/avc`（H264）。
- **H265 开关**：设置项 `Switch_EnableH265Encoding`；默认值 `Constant.getDefaultEnableH265()` + `Config.needH265Encoder()` 依固件 `VersionType` 决定；运行时 `isH265EncoderSupport` 探测硬件支持。
- **帧处理**：YUV420 → 旋转（`libyuv.so` / `rotateYuv420`）→ 烧录水印（§3.5）→ 编码。
- **音频**：由 `AudioRecordManager` 采集 → AAC 编码（`AACEncoder`）→ 与视频混流。

### 3.5 GPS 与视频水印（georeference）

**定位源**（多路）：
- `MyLocationManager`（Android GPS/网络，监听器 `MyGpsLocationListener` 等）
- 百度定位 `BaiduLocationManager`（`liblocSDK8b.so`）
- **千寻 RTK 高精度**：`RTKLocationManager` / `QianxunLocationManager` / `XchipRTKLocationManager`（`libqxinertial.so`、`libqxsignature.so`）

**水印烧录**（非 MP4 元数据）：
1. `VideoRecoderManager.getVideoWatermark(String[] lines, …)`：`Canvas.drawText()` 把**经纬度 + 时间**等逐行画到 `ARGB_8888` Bitmap；
2. `addWatermark()`：经 native `YuvUtils.addWatermark`（`libyuv.so`）把水印**烧进每帧 YUV420** 像素。
3. 相关设置：`WatermarkLocation`（是否显示位置）、`WaterMarkTimeEnable`（时间）、`WaterMarkFontSize`、`WaterMarkPosition`（顶/底）。

### 3.6 音频录制

| 参数 | 值（`com.corget.common.Config`） | 含义 |
|---|---|---|
| `ChannelConfiguration` | `2` | **单声道**（`CHANNEL_CONFIGURATION_MONO`） |
| `AudioEncoding` | `2` | `ENCODING_PCM_16BIT` |
| `Frequency` | `8000`（录像/降噪时 `16000`） | 采样率 Hz |

- **采集**：单个 `AudioRecord`（`AudioRecordManager$RecordThread`），`getMinBufferSize(rate, ChannelConfiguration, AudioEncoding)`。
- **物理麦选择**：靠 `getTargetAudioSource()` 返回的 **AudioSource 常量**，非显式 mic 编号：
  - `1` = `MIC`（默认）、`5` = `CAMCORDER`（朝摄像头麦，录像）、`7` = `VOICE_COMMUNICATION`（带 AEC）
  - 依有线耳机 / 蓝牙 / 机型 / 单双向自动选。
- **音频增强**：`librnnoise.so`（RNNoise 降噪，设置 `EnableRNNoise`）、`libwebrtc_apms.so`（WebRTC APM/AEC 回声消除）、VAD（`com.corget.vad.VadManager`，设置 `EnableVideoVad`）、`libspeexcodec-jni.so` / `libresample.so` / `libvocoder.so`。
- **结论**：录音 = **1 路逻辑输入、单声道**；**代码中无显式物理 mic 索引**，由音频 HAL 按 AudioSource 路由。

### 3.7 语音提示 / TTS

**三条路并存，默认第一条**：
1. **内置离线 TTS 引擎（默认）**：`Tts.speak(text)` → `PocService.EncodeTTS(text → PCM)` → `PlayData()`；引擎在 native 库（`libpoc.so`/`libvocoder.so`），错误串 `"offline engine is uninitialized, please invoke initTts()"`、`"mix engine initTTS"`。**非** Android `TextToSpeech`。参数：`TTS_VOLUME/SPEED/PITCH/SPEAK_STYLE`；含英文发音修正（`"WIFI"→"why fi"`）。
2. **预录 `.wav` 音效**：`res/*.wav`（混淆名 `bF1/bF2/bF3.wav` 等），`SoundPlayManager` 播放。
3. **可选引擎**：Android `android.speech.tts.TextToSpeech`、百度离线合成 `BaiduSpeechSynthesizer`（`com.baidu.tts`）；设置项 `TTSEngine` 切换。

英文提示文本示例：`"Login successfully"`、`"Recording stopped"`、`"Insufficient storage space, Video recording stopped"`、`"Failed to start recorder"`、`"The recording is cleared"`。

### 3.8 灯光 / 夜视

F2SP 全部经 `police_system`（`ZfySDJWF2S`）：

| 功能 | 调用 |
|---|---|
| 绿灯（运行提示） | `setGreenLed()` → `policeSystemManager.setIndicatorLed(1, 0xFF00FF00, on)` |
| 红灯（录像提示） | `setRedLed()` → `setIndicatorLed(1, 0xFFFF0000, on)` |
| 黄灯 | `setYellowLed()` → `setIndicatorLed(1, 0xFFFFFF00, on)` |
| 红/蓝警灯 | `setRedWarningLight/setBlueWarningLight` → `setAlertLight(32/8, on)` |
| 红外夜视 | `setNightVision(true)` → `setIRLed(1, 500)` + `setIRBarrier(1)`（开红外补光 + 撤 IR-Cut） |

- 驱动：录像状态机在开始/停止时调用 `deviceHandler.setRedLed(true/false)` 等。
- **Camera2 层 `setNightVision` 是空操作**，真夜视靠 IR 硬件。
- 其它机型走 GPIO sysfs：`writeToDevice("on", "/sys/class/gpio_switch/ir-led")`、`ircut_switch`（`libsetGPIO.so`、`libhexwrnativelib.so`）。

### 3.9 传感器（`MySensorManager`）

标准 `android.hardware.SensorManager`，可注册：**光线（`TYPE_LIGHT`）**、接近、方向、加速度、线性加速度、磁场、气压、计步、心率、显著运动、静止检测。
- `registerLightSensorListener()` 读环境照度，用于低光判断（部分机型专用节点 `/sys/…/xyc_lightsensor/ir_enable`）。

### 3.10 其它能力（从 native 库推断）

- **人脸检测/识别**：SeetaFace 全家桶（`libSeetaFaceDetector/Landmarker/Recognizer600`）+ `libonnxruntime.so` + `libTenniS.so`；姿态估计 `libPoseEstimation600`、质量评估 `libQualityAssessor300`。
- **地图**：百度地图 `libBaiduMapSDK_*`、Mapbox `libmapbox-gl.so`。
- **影像**：OpenCV `libopencv_java3.so`、`libjpeg_turbo_150.so`、GIF `libpl_droidsonroids_gif.so`。
- **串口/GPIO**：`libandroid_serial_port.so`、`libserial_port.so`、`libsetGPIO.so`。

---

## 4. 自研 App 功能规格

### 4.1 功能范围

| 功能 | 状态 | 说明 |
|---|---|---|
| 开机自动登录 | ✅ 必做 | Boot 后静默登录 |
| 项目选择（手动） | ✅ 必做 | 列表选择当前作业项目 |
| 项目激活（语音） | ✅ 必做 | 唤醒词 + "打开XX项目" |
| 语音对话 | ✅ 必做 | STT → LLM → TTS |
| 录像（H264/H265） | ✅ 必做 | Camera2 + MediaCodec |
| 拍照 | ✅ 必做 | Camera2 |
| 录音 | ✅ 必做 | AudioRecord/MediaRecorder |
| GPS 位置水印 | ✅ 必做 | 烧录 + 可选 MP4 geotag |
| 物理键联动 | ✅ 必做 | 复用 `lolaage.*` |
| 灯光提示（红/绿） | ✅ 必做（F2SP） | police_system；普通机降级 |
| 夜视 / 自动夜间 | ✅ 必做（F2SP） | IR + 光线传感器 |
| 上传（视频/照片/轨迹） | ✅ 必做 | 后端 API |
| PTT 一键对讲 | ❌ 移除 | — |
| 人脸识别 | ⭕ 可选 | 后续按需 |

### 4.2 核心用户流程

```
开机
 └─(BootReceiver)→ 启动前台 Service
      └─ 读取加密 token → 静默自动登录
           ├─ 成功 → 进入待命（常驻通知）
           │    ├─ [手动] 用户打开 App → 选择项目 → 作业
           │    └─ [语音] 说唤醒词 → "打开XX项目" → 进入项目 → 作业
           │         └─ 作业中：物理键录像/拍照/录音；语音对话；GPS 水印；灯光提示
           └─ 失败 → 通知提示，待网络恢复重试
```

### 4.3 权限清单（最小集）

```
RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_LOCATION,
CAMERA, RECORD_AUDIO, FLASHLIGHT,
ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION,
INTERNET, ACCESS_NETWORK_STATE,
READ_MEDIA_VIDEO, READ_MEDIA_IMAGES, READ_MEDIA_AUDIO,  (或 WRITE_EXTERNAL_STORAGE for <=API28)
POST_NOTIFICATIONS, WAKE_LOCK, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
（F2SP 专用灯光/夜视：厂商 police_system SDK 声明的权限）
```

---

## 5. 系统架构设计

### 5.1 分层
```
┌───────────────────────── UI 层（Activity / Compose）─────────────────────────┐
│  登录页  项目选择页  作业主界面(预览+状态)  设置页  语音对话浮层               │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                 │
┌───────────────────────── 应用服务层（Foreground Service）────────────────────┐
│  SessionManager(登录态)  ProjectManager  RecordingController(状态机)          │
└───┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────────────────────┘
    │         │         │         │         │         │
┌───▼──┐ ┌───▼───┐ ┌───▼───┐ ┌───▼───┐ ┌───▼────┐ ┌──▼─────────┐
│硬件键 │ │摄像头  │ │录音    │ │定位/  │ │语音    │ │上传/存储    │
│HAL    │ │+编码   │ │        │ │水印   │ │(唤醒/  │ │             │
│       │ │(H265) │ │        │ │(RTK)  │ │STT/LLM/│ │             │
│       │ │       │ │        │ │       │ │TTS)    │ │             │
└───────┘ └───────┘ └───────┘ └───────┘ └────────┘ └────────────┘
                                 │
┌────────────────── 硬件抽象层 DeviceCapability（按机型注入）──────────────────┐
│  F2SPCapability(police_system LED/IR, lolaage 键)  │  GenericPhoneCapability │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 关键设计原则
- **常驻前台 Service** 持有登录态与硬件监听，避免被系统回收；申请电池优化白名单。
- **RecordingController 单一状态机**：`IDLE → RECORDING_VIDEO / TAKING_PHOTO / RECORDING_AUDIO`，统一驱动摄像头、录音、LED、水印、上传。
- **DeviceCapability 接口**隔离设备差异（见 §5.3），F2SP 与普通手机各一实现。
- **配置驱动键映射**：物理键 → 功能的绑定放配置，可热改（见 §7.1）。

### 5.3 硬件抽象层接口
```kotlin
interface DeviceCapability {
    fun hasHardwareKeys(): Boolean
    fun registerKeys(cb: KeyEventCallback)         // 内部注册 lolaage.* 或屏幕/音量键
    fun setRunningLed(on: Boolean)                 // 绿灯：F2SP=police_system；普通机=NA
    fun setRecordingLed(on: Boolean)               // 红灯
    fun setNightVision(on: Boolean)                // IR：F2SP=police_system；普通机=提ISO
    fun setFlash(on: Boolean)
    fun supportsNightVision(): Boolean
}
// F2SPCapability：注入 getSystemService("police_system") 的 PoliceSystemManager
// GenericPhoneCapability：LED/IR 空实现，夜视改走 Camera2 提 ISO
```

---

## 6. 详细模块规格

> 说明：以下代码为**接口/关键逻辑示例**（Kotlin），非完整实现；默认技术栈 = 原生 Android / Kotlin。

### 6.1 开机自启 + 自动登录
- **验收**：设备开机后 30s 内完成静默登录并常驻；杀进程后能被 boot/alarm 拉起。
```kotlin
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) {
    if (i.action == Intent.ACTION_BOOT_COMPLETED)
      ContextCompat.startForegroundService(c, Intent(c, FieldService::class.java))
  }
}
// FieldService.onCreate: 读 EncryptedSharedPreferences 中 token → AuthApi.login(token)
//   成功→保持；失败→WorkManager 重试。Android10+ 不弹 Activity，仅前台通知。
```
- 需求：向厂商申请把包名加入**自启白名单**，否则国产 ROM 会拦截开机广播。

### 6.2 常驻前台 Service（`FieldService`）
- `startForeground` + `foregroundServiceType="camera|microphone|location"`。
- 持有 `SessionManager`、`DeviceCapability`、各 Manager 单例。
- 验收：熄屏、后台 24h 不被杀；通知栏显示当前项目与录制状态。

### 6.3 硬件键 HAL
- 复用源 App 的 `lolaage.*`；短/长按判定沿用 1000ms 延时回调。
- **自研默认键映射（无 PTT）**，全部集中在配置：

| 物理键 | action | 短按 | 长按 |
|---|---|---|---|
| 录像键 | `lolaage.video1.*` | 开/停录像 | 切换上传 |
| 拍照键 | `lolaage.take.picture.*` | 拍照 | 开/关手电 |
| 录音键 | `lolaage.audio.*` | 开/停录音 | 调音量 |
| SOS 键 | `lolaage.sos.*` | 发送报警 | 警灯 |

```kotlin
// 见仓库参考实现 HardwareKeyController（§附录 D 提供的思路）：
// .down → isShort=true; postDelayed(long,1000)
// .up   → removeCallbacks; if(isShort) onShort()
// long callback → isShort=false; onLong()
```
- 验收：短按/长按区分正确率 100%；连按不串键。

### 6.4 摄像头 & 录像（Camera2 + MediaCodec + H265）
- 枚举 `getCameraIdList()`，按 `LENS_FACING` 选前/后，**不硬编码 ID**；F2SP 实测 2 颗（0/1）。
- 预览 `SurfaceView`/`SurfaceTexture`；录制走 **MediaCodec Surface 输入 + MediaMuxer**（便于插入水印帧）。
- H265：
```kotlin
val hevcOk = MediaCodecList(REGULAR_CODECS)
   .findEncoderForFormat(MediaFormat.createVideoFormat("video/hevc", w, h)) != null
val mime = if (settings.enableH265 && hevcOk) "video/hevc" else "video/avc"
val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
   setInteger(KEY_COLOR_FORMAT, COLOR_FormatSurface)
   setInteger(KEY_BIT_RATE, bitrate)        // H265 同画质省约 40%
   setInteger(KEY_FRAME_RATE, 30)
   setInteger(KEY_I_FRAME_INTERVAL, 1)
}
```
- 验收：H265 开关生效；不支持时自动回退 H264；1080p@30 稳定录制；文件可正常播放。

### 6.5 GPS 位置水印
- 定位：`FusedLocationProviderClient`（普通机）；F2SP 若需厘米级接 RTK SDK。
- 两种写入，可同时：
  - **烧录**（同源 App，防篡改）：把 `时间 + 纬度 + 经度 + 项目名` 绘到帧上（OpenGL 纹理叠加或 Canvas→Bitmap→YUV 合成）。
  - **元数据**：`MediaRecorder.setLocation(lat, lon)` 或 MediaMuxer 写 MP4 `©xyz`。
- 配置：水印开关、字号、位置（顶/底）、是否含坐标/时间。
- 验收：水印随定位实时刷新（≤1s）；坐标精度与来源一致；关闭定位时水印仅显时间。

### 6.6 录音
- 单声道 PCM_16BIT；`getMinBufferSize` 计算缓冲；可选 RNNoise / WebRTC AEC。
- AudioSource 选择：默认 `MIC(1)`；录像联动可用 `CAMCORDER(5)`；与语音助手互斥时用 `VOICE_COMMUNICATION(7)`。
- 指定物理麦（可选，API≥28）：
```kotlin
val builtin = audioManager.getDevices(GET_DEVICES_INPUTS).first { it.type == TYPE_BUILTIN_MIC }
audioRecord.setPreferredDevice(builtin)
val mics: List<MicrophoneInfo> = audioManager.microphones   // 含 id/位置/朝向
```
- 验收：录音清晰无回声；与录像/语音助手不抢占（音频焦点管理）。

### 6.7 夜视 / 灯光 / 自动夜间
- LED/IR：`DeviceCapability` 注入；F2SP→`police_system`，普通机→降级。
- **自动夜间**：光线传感器阈值触发 + Camera2 提感光：
```kotlin
sensorManager.registerListener(object: SensorEventListener {
  override fun onSensorChanged(e: SensorEvent) {
    val lux = e.values[0]
    when { lux < 10 -> capability.setNightVision(true)
           lux > 30 -> capability.setNightVision(false) }
  }
  override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}, sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT), SENSOR_DELAY_NORMAL)

// 普通机提感光（无 IR 时）：
req.set(CONTROL_AE_MODE, CONTROL_AE_MODE_OFF)
req.set(SENSOR_SENSITIVITY, iso)            // 上限查 SENSOR_INFO_SENSITIVITY_RANGE
req.set(SENSOR_EXPOSURE_TIME, expNs)
// 或 req.set(CONTROL_SCENE_MODE, SCENE_MODE_NIGHT)
```
- 验收：暗光下自动开夜视/提亮，回亮后恢复；带迟滞避免抖动。

### 6.8 语音模块（唤醒 + STT + 对话 + TTS）
| 环节 | 推荐方案 |
|---|---|
| 唤醒词 | Picovoice Porcupine（离线、可定制、低功耗） |
| STT | 在线：Android `SpeechRecognizer` / 讯飞；离线：Vosk |
| LLM 对话 | **Claude（`claude-sonnet-5` / `claude-opus-4-8`）经自有后端中转**（见 §8） |
| TTS | 见 §6.9（英文自然发音） |
| 项目激活 | 唤醒后一句 "打开XX项目" → STT → 意图/关键词匹配 → 切项目 |

流程：`唤醒命中 → 录音 → STT → 意图判定(选项目? 直接切换 : 转 LLM) → LLM 流式回复 → TTS 播报`。
- 验收：唤醒误触发率低；离线可用基础指令；对话端到端延迟可接受（首字 <2s，流式）。

### 6.9 TTS / 英文语音提示（重点：更 English-native）
固定提示词：`"Login successfully"`、`"Recording started"`、`"Recording stopped"`、`"Storage full"` 等。
- **方案 A（推荐固定短语）**：预生成英文 `.wav` 缓存（用云端神经 TTS 生成一次，离线播放，最自然无延迟）。
- **方案 B（离线可用）**：Android `TextToSpeech`，`setLanguage(Locale.US/UK)`，选高质量引擎（Google TTS）与 `enhanced/network` voice。
- **方案 C（动态/最自然）**：云端神经 TTS（Amazon Polly / Google Cloud TTS / Azure Neural / ElevenLabs）实时合成，本地缓存。
```kotlin
val tts = TextToSpeech(ctx) { if (it==SUCCESS) { tts.language = Locale.US
    tts.voice = tts.voices.firstOrNull { v -> v.locale==Locale.US && !v.isNetworkConnectionRequired && v.quality>=Voice.QUALITY_HIGH } ?: tts.defaultVoice } }
fun say(s: String) = tts.speak(s, QUEUE_FLUSH, null, s)
```
- 验收：英文提示发音自然、地道；离线场景仍可播报基础提示。

### 6.10 上传 / 存储
- 本地：分段落盘（断电不丢），元数据入本地 DB（Room）。
- 上传：WorkManager 队列 + 断点续传；视频/照片/录音/GPS 轨迹按项目归档。
- 验收：弱网自动重传；上传进度可见；本地空间不足时提示并停录（对齐源 App "Insufficient storage space, Video recording stopped"）。

---

## 7. 数据模型 & 配置

### 7.1 键映射配置（JSON，可热更）
```json
{
  "keys": {
    "lolaage.video1":      { "short": "TOGGLE_VIDEO",  "long": "TOGGLE_UPLOAD" },
    "lolaage.take.picture":{ "short": "TAKE_PHOTO",     "long": "TOGGLE_FLASH" },
    "lolaage.audio":       { "short": "TOGGLE_AUDIO",   "long": "ADJUST_VOLUME" },
    "lolaage.sos":         { "short": "SEND_SOS",       "long": "WARNING_LIGHT" }
  },
  "longPressMs": 1000
}
```

### 7.2 录像文件元数据
```
{ id, projectId, startTime, endTime, path, codec(h264|h265),
  resolution, hasWatermark, gpsTrack:[{t,lat,lon,acc}], uploaded }
```

### 7.3 项目
```
{ id, name, code, voiceAliases:["XX项目",...], active }
```

---

## 8. 语音对话接 Claude 的方案

- **不要把 API Key 放进 App**；App → **你自有后端** → Claude API（保护密钥、可审计计费、可加业务上下文）。
- 模型：默认 `claude-sonnet-5`（速度/成本均衡），复杂场景 `claude-opus-4-8`。
- 交互：后端 SSE **流式**返回，App 边收边 TTS；支持多轮上下文（携带当前项目、位置等系统提示）。
- 离线兜底：无网时语音模块降级为本地关键词指令（选项目、开始/停止录像等）。

---

## 9. 非功能需求
| 维度 | 要求 |
|---|---|
| 性能 | 1080p@30 录制不丢帧；语音首响 <2s；启动到待命 <30s |
| 功耗 | 待命常驻低功耗；唤醒词离线监听 <5%/h 附加耗电 |
| 存储 | 分段落盘；空间阈值预警并安全停录 |
| 稳定 | 前台 Service 24h 不被杀；崩溃自恢复并续录 |
| 安全 | token 加密存储；传输 TLS；本地媒体可选加密 |
| 兼容 | F2SP(armeabi) 为主；普通机(arm64)降级；原生库需含 armeabi-v7a |

---

## 10. 技术选型汇总
| 领域 | 选型 |
|---|---|
| 语言/框架 | Kotlin（原生 Android） |
| 相机 | Camera2 + MediaCodec + MediaMuxer |
| 编码 | H265(video/hevc) 优先，回退 H264(video/avc) |
| 定位 | FusedLocation；F2SP 可接 RTK SDK |
| 水印 | OpenGL 叠加 / Canvas 合成 + (可选)MP4 geotag |
| 音频 | AudioRecord + AAC；RNNoise/WebRTC AEC 可选 |
| 唤醒 | Picovoice Porcupine |
| STT | SpeechRecognizer / Vosk(离线) |
| LLM | Claude（经自有后端） |
| TTS | 预录/云端英文 wav + Android TextToSpeech 兜底 |
| 后台 | Foreground Service + WorkManager |
| 存储 | Room + 加密 SharedPreferences |
| 硬件抽象 | DeviceCapability（F2SP / Generic） |

---

## 11. 里程碑建议
1. **M1 基础骨架**：工程脚手架、前台 Service、开机自启 + 自动登录、项目选择。
2. **M2 采集闭环**：Camera2 预览/录像(H265)/拍照 + 录音 + GPS 水印 + 物理键联动 + 红/绿灯。
3. **M3 语音**：唤醒 + STT + 语音选项目 + Claude 语音对话 + 英文 TTS 提示。
4. **M4 上传与稳定性**：断点续传、存储管理、夜视自动化、功耗优化、灰度测试。

---

## 12. 风险与依赖
- **厂商依赖（高）**：`police_system` SDK、自启白名单、armeabi 兼容——须 F2SP 供应商配合。
- **硬件探测**：H265 硬编、相机数量、麦克风数须在真机探测，不可硬编码。
- **合规**：不得复用闭源代码；第三方 SDK（Picovoice/云 TTS/地图/RTK）需各自许可。
- **降级完备性**：普通手机无 police_system/IR，需保证功能优雅降级不崩。

---

## 附录 A：`lolaage.*` 广播全表（源 App 注册）
`ptt.down/up`、`video1.down/up`、`video.down/up`、`take.picture.down/up`、`audio.down/up`、`sos.down/up`、`light.down/up`、`volume.down/up`、`switch.group.down/up`、`ir.led`、`ircut.switch`、`rayled.set`
> F2SP 的 `ZfySDJWF2S.onReceive` 实际处理其中 ptt / video1 / take.picture / audio / sos 五组；其余供灯光/群组/夜视等扩展。

## 附录 B：`MediaRecorder.AudioSource` 常量对照
| 值 | 常量 | 用途 |
|---|---|---|
| 1 | MIC | 通用麦（默认） |
| 5 | CAMCORDER | 朝摄像头方向麦（录像） |
| 7 | VOICE_COMMUNICATION | 带回声消除（通话/语音助手） |

## 附录 C：F2SP native 库清单（`lib/armeabi/`）与用途
| 库 | 用途 |
|---|---|
| `libpoc.so` / `libvocoder.so` | 核心引擎 + 内置离线 TTS/语音编解码 |
| `libspeexcodec-jni.so` / `libresample.so` | Speex 编解码 / 重采样 |
| `librnnoise.so` / `libwebrtc_apms.so` | 降噪 / 回声消除(AEC) |
| `libyuv.so` / `libyuvc.so` | YUV 处理（水印烧录、旋转） |
| `libcamera.so` / `libuvc.so` / `libusb_100.so` | 相机 / USB 外接摄像头 |
| `libSeetaFace*` / `libTenniS.so` / `libonnxruntime*.so` | 人脸检测/识别、推理引擎 |
| `libPoseEstimation600.so` / `libQualityAssessor300.so` | 姿态估计 / 图像质量评估 |
| `libBaiduMapSDK_*` / `liblocSDK8b.so` / `libmapbox-gl.so` | 地图 / 定位 |
| `libqxinertial.so` / `libqxsignature-*.so` | 千寻 RTK 高精度定位 |
| `libopencv_java3.so` / `libjpeg_turbo_150.so` / `libpl_droidsonroids_gif.so` | 图像处理 |
| `libsetGPIO.so` / `libhexwrnativelib.so` / `libandroid_serial_port.so` / `libserial_port.so` | GPIO / 串口（灯光、外设） |
| `libcrypto.so` / `libssl.so` | 加密 / TLS |

## 附录 D：逆向源码索引（关键类 → 方法）
| 主题 | 类 → 方法 |
|---|---|
| 设备识别 | `Config.isSDJWF2SDevice()`、`DeviceHandlerManager.InitDeviceHandler()` |
| F2SP 键处理 | `ZfySDJWF2S.onReceive()`、`ZfySDJWF2S$LongClickCallback.run()`、`PocService.registerDynamicReceiver()` |
| 相机 | `VideoCamera2Manager.{openCamera,setPreferredResolution,setPreviewFpsRange,realStartVideoRecord}`、`VideoRecoderManager.{getNextCameraId,getCameraPixelSet,isUsingSupportNightVisionCamera,setNightVision}` |
| H265 | `Constant.getDefaultEnableH265()`、`Config.needH265Encoder()` |
| 水印/GPS | `VideoRecoderManager.{getVideoWatermark,addWatermark}`、`MyLocationManager`、`QianxunLocationManager` |
| 录音 | `AudioRecordManager.{getTargetAudioSource,realStartRecord,RecordThread}`、`Config.{getPTTAudioSource,getOneWayVideoAudioSource,getBidirectionalVideoAudioSource,ChannelConfiguration,AudioEncoding,Frequency}` |
| TTS | `Tts.speak()`、`PocService.EncodeTTS()/PlayData()`、`BaiduSpeechSynthesizer` |
| 灯光/夜视 | `ZfySDJWF2S.{setGreenLed,setRedLed,setYellowLed,setRedWarningLight,setBlueWarningLight,setNightVision}` |
| 传感器 | `MySensorManager.registerLightSensorListener()` |

---
*本 SPEC 基于对 `SMART-PTT v10.1.7.124`（`com.corget`）的静态逆向分析整理，供自研 App 设计参考。*
