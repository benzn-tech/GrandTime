# GrandTime 子项目1「主干」设计:常驻前台 Service + 物理键监听 + 开机自启

日期:2026-07-09
状态:已与用户逐节确认

## 1. 背景与目标

GrandTime 是运行在 F2SP 执法对讲终端(MT6768,Android 13 / API 33)上的现场作业记录仪
App,替代商用 App SMART-PTT(com.corget)。物理键机制已经逆向确认:ROM 通过厂商私有
广播 `lolaage.*` 发键事件(每键 `.down`/`.up`),短/长按由「按下起 1 秒定时器」判定。

**合规前提**:com.corget / lolaage / PoliceSystem 属商业闭源产品与厂商私有 SDK,不得
反编译照搬其代码;`lolaage.*` 是 ROM 对外广播接口,动态注册即可合法监听。本项目所有
代码干净重写,仅复用广播协议这一事实。

**与 SMART-PTT 的方向差异**:去掉对讲(不注册 `lolaage.ptt.*`);保留录像/拍照/录音/
灯光/SOS 物理键;新增开机自动登录、项目选择、AI 语音交互。

**后端**:对接 FieldSight,用用户自己的 AWS 账号(SAM pipeline,Cognito 用户池认证),
暂不接公司账号。

## 2. 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| 工程形态 | 单 Gradle 模块(`:app`),按包分层;以后需要再拆 |
| 键映射配置 | 代码默认映射表 + Jetpack DataStore 用户覆盖层,支持恢复默认 |
| UI 技术 | Jetpack Compose |
| minSdk / targetSdk | 33 / 33(只服务 F2SP Android 13,不做老版本兼容) |
| 包名 | `com.benzn.grandtime`,App 显示名「GrandTime」 |
| 首期登录 | 打桩(AuthManager 接口 + Stub 实现);Cognito 真登录放子项目2 |
| 仓库布局 | Android 工程建在 GrandTime 仓库根;现有 APK/刷机工具/固件文档移入 `reference/` |

## 3. 子项目路线图

每个子项目独立走 设计→计划→实现 循环。本 spec 只覆盖子项目1。

| # | 子项目 | 内容 |
|---|---|---|
| 1 | 主干(本 spec) | 前台 Service + lolaage.* 键监听(可配置映射)+ 开机自启 + 登录桩 + 按键探针页 |
| 2 | 真登录 | Cognito 用户名密码登录 + refresh token 加密保存(EncryptedSharedPreferences)+ 开机静默刷新 |
| 3 | 采集 | Camera2 录像/拍照 + MediaRecorder 录音 + police_system 灯光/红外夜视/警灯 |
| 4 | 上传 | GPS 定位 + 文件上传 FieldSight + 项目选择(顺带解决录制数据无 site 归属问题) |
| 5 | 语音 | 唤醒词(Porcupine)+ STT + Claude 问答(经自建后端中转)+ TTS |

## 4. 物理键 → 广播 → 功能映射(默认表,来自逆向)

| 物理键 | 广播 action | 短按(<1s) | 长按(≥1s) |
|---|---|---|---|
| 录像键 | `lolaage.video1.down/.up` | 开始/停止录像 | 切换视频上传 |
| 拍照键 | `lolaage.take.picture.down/.up` | 拍照 | 开/关手电筒 |
| 录音键 | `lolaage.audio.down/.up` | 调节音量 | 开始/停止录音 |
| SOS 键 | `lolaage.sos.down/.up` | 发送 SOS 报警 | 切换警灯闪烁 |
| 对讲 PTT | `lolaage.ptt.down/.up` | **不注册(去 PTT 化)** | — |

ROM 另有 `lolaage.light` / `switch.group` / `volume` / `ir.led` / `ircut.switch` /
`rayled.set` 等广播,首期全部注册进探针以便观察,不做功能。

首期所有 KeyAction 处理器均为桩:更新前台通知文字 + 写探针日志。

## 5. 项目结构

```
GrandTime/
├── reference/                     # SMART-PTT APK、SP Flash Tool、固件升级 xlsx(移入)
├── docs/superpowers/specs/        # 设计文档(本文件)
├── app/src/main/java/com/benzn/grandtime/
│   ├── GrandTimeApp.kt            # Application 入口
│   ├── boot/BootReceiver.kt       # BOOT_COMPLETED → startForegroundService(CoreService)
│   ├── service/CoreService.kt     # 常驻前台 Service(主干中枢)
│   ├── hardware/
│   │   ├── KeyEventSource.kt      # 接口:物理键事件流 Flow<KeyPress>
│   │   ├── F2spKeyEventSource.kt  # lolaage.* 动态广播接收 + 1 秒短/长按判定
│   │   ├── OnScreenKeyEventSource.kt # 屏幕按钮实现(模拟器/普通手机退化)
│   │   └── DeviceProfile.kt       # 按 Build.MODEL(SDJW-F2SP/F2S-A/SDJW-F2S/XB-15)选实现
│   ├── keymap/
│   │   ├── KeyAction.kt           # 动作枚举(START_STOP_VIDEO、TAKE_PHOTO、TORCH、SOS…)
│   │   ├── KeyMapping.kt          # 代码默认映射表((键, 短|长按) → KeyAction)
│   │   ├── KeyMapStore.kt         # DataStore 覆盖层 + 恢复默认
│   │   └── KeyActionDispatcher.kt # 查覆盖→查默认→派发;首期动作为桩
│   ├── auth/
│   │   ├── AuthManager.kt         # 接口:silentLogin() / loginState: StateFlow
│   │   └── StubAuthManager.kt     # 首期假实现,固定返回已登录
│   └── ui/
│       ├── MainActivity.kt        # Compose 宿主,三个页面
│       ├── StatusScreen.kt        # 服务状态/登录态/当前映射
│       ├── ProbeScreen.kt         # 按键探针:action、时间戳、短/长按、映射动作实时滚动
│       └── KeymapScreen.kt        # 改键设置页(写 DataStore 覆盖)
└── build.gradle.kts / settings.gradle.kts / app/src/main/AndroidManifest.xml 等
```

## 6. 核心组件设计

### CoreService(常驻前台 Service)
启动时:`startForeground()` 挂常驻通知(状态文字:待命/桩动作)→ `AuthManager.silentLogin()`
→ 注册 KeyEventSource。`START_STICKY`;被杀后系统重启,重启流程幂等。Android 13 需
POST_NOTIFICATIONS 运行时权限,首次打开 App 引导授权。

### BootReceiver(开机自启)
静态注册 `RECEIVE_BOOT_COMPLETED`,收到后 `startForegroundService(CoreService)`,不弹
Activity(符合 Android 10+ 后台启动限制)。厂商 ROM 自启管控:首次部署需在设备设置里
把 GrandTime 加入自启/省电白名单(写入验收步骤)。

### F2spKeyEventSource(物理键监听)
- Service 内**动态注册** BroadcastReceiver(不在 Manifest 静态声明),IntentFilter 一次
  装入全部已知 `lolaage.*` action(不含 `ptt.*`)。
- Android 13 动态注册必须声明导出性:用 `RECEIVER_EXPORTED`,既能收 ROM 系统广播,
  也允许 `adb shell am broadcast` 手工模拟按键(模拟器可测)。
- 短/长按判定(照逆向结论):`.down` 起 1 秒协程定时器;1 秒内收到 `.up` = 短按;
  定时器到点 = 长按立即触发(不等松手),其后的 `.up` 忽略。
- 产出统一 `KeyPress(key, pressType)` 事件流。
- **已知限制**:安卓广播无法通配监听,只能收 IntentFilter 列名的 action。若真机 action
  字符串与逆向结果有出入,兜底:adb logcat 观察 ROM 实际广播,修正 action 常量表(一处改)。

### KeyActionDispatcher + 可配置映射
`KeyPress` → 先查 DataStore 用户覆盖 → 查不到用代码默认表 → `KeyAction` → 动作处理器。
首期处理器全为桩(通知文字 + 探针日志)。子项目3 起逐个替换为真实现,接口不变。

### ProbeScreen(按键探针,首期验收工具)
实时滚动:原始 action、时间戳、短/长按判定、映射动作。同时落文本日志到 App 私有目录
(单文件 1MB 轮转,保留 2 个),不接电脑也能排查。

### StubAuthManager
`AuthManager` 接口不变,桩实现固定返回「已登录(测试账号)」。子项目2 换 Cognito 实现。

### 硬件抽象层
`DeviceProfile` 按 `Build.MODEL` 匹配 F2SP 家族(SDJW-F2SP / F2S-A / SDJW-F2S / XB-15)
→ `F2spKeyEventSource`;其它设备 → `OnScreenKeyEventSource`(屏幕按钮),UI 同一套。

## 7. 数据流

```
ROM 广播 lolaage.video1.down/.up
  → F2spKeyEventSource(1 秒定时判定短/长按)
  → KeyPress 事件流
  → KeyActionDispatcher(DataStore 覆盖 → 代码默认表)
  → KeyAction 桩处理器(更新通知 + 写探针日志)
  → StatusScreen / ProbeScreen 经 StateFlow 实时刷新
```

开机流:`BOOT_COMPLETED → BootReceiver → CoreService 前台化 → 桩登录 → 注册键监听
→ 通知栏「待命」`。UI 与 Service 通过单例状态仓(StateFlow)共享状态,不用 AIDL/绑定。

## 8. 错误处理

- Service 被杀 → START_STICKY 重启,流程幂等。
- 未知广播/未映射按键 → 不崩溃,原样记探针日志(异常输入是信息不是错误)。
- 登录失败(子项目2 起真实存在)→ 通知栏「未登录」,物理键功能不受影响(采集不依赖
  登录,仅上传依赖)。
- 探针日志轮转防写满存储。

## 9. 测试与验收

**单元测试(JVM)**:短/长按判定(协程测试时钟模拟 0.9s/1.1s)、映射解析(默认表/
覆盖/恢复默认)、Dispatcher 派发。

**模拟器/普通手机**:`adb shell am broadcast -a lolaage.video1.down` 模拟按键全流程;
屏幕按钮源二重验证。

**真机验收(子项目1 完成标准)**:
1. F2SP 开发者模式 + USB 调试打通,Android Studio 直接安装;
2. 停用 SMART-PTT(避免双 App 抢键/抢摄像头),GrandTime 加自启白名单;
3. 逐个按物理键,探针页 action/短长按/映射动作与逆向表一致(有出入按探针+logcat 修正);
4. 重启设备不碰屏幕,通知栏出现「GrandTime 待命」= 开机自启+静默登录链路通过。

## 10. 提前声明的风险(不影响首期)

1. **后台 FGS 摄像头/麦克风限制(影响子项目3)**:Android 11+ 限制「从后台启动的前台
   Service」使用 camera/mic;开机自启的 Service 直接录像会被拦。候选解法:引导授权
   「显示在其他应用上层」(SYSTEM_ALERT_WINDOW,授权即豁免)或把 App 设为设备 launcher。
   子项目3 设计时定。
2. **action 字符串风险**:键映射来自逆向,真机可能有出入;探针页 + logcat 是修正手段。
3. **ROM 自启管控**:需手工加白名单;若 ROM 无此设置项则观察 BOOT_COMPLETED 是否可达,
   必要时研究厂商预装应用的豁免机制。
