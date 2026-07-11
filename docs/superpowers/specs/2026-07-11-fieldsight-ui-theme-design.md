# FieldSight 手机端 UI 设计:产品化信息架构 + fieldsight-ui 同设计语言

日期:2026-07-11(v2,按用户产品反馈重写信息架构)
状态:待用户审阅
前置:子项目1「主干」已验收合并(tag `subproject-1-accepted`)

## 1. 背景与目标

本 App 即 **FieldSight 的手机端**(GrandTime 仅为仓库代号)。子项目1交付的三页
(Status/Probe/Keymap)是调试骨架 UI;本子项目把界面翻新为**产品形态**:极简、
与 fieldsight-ui(`C:/Users/camil/Dropbox/fieldsight-ui`,令牌源 `styles/tokens.css`)
同设计语言,并为后续子项目(SP2 登录、SP3 采集、SP4 上传)留好 UI 骨架。

**已确认决策**:
- 信息架构翻新:一级导航 **Home / Files / Settings**;原 Probe(调试仪器)与
  Keymap(改键)降级为 Settings 二级页;删除 Last action 卡与映射摘要卡;
  删除设备型号 chip
- 登录语义 = **one-off**:登录一次永久有效,除非用户在 Settings 主动 Sign out
  (后端语义 SP2 实现;本次 UI 预留入口)
- 录制参数进 Settings(视频清晰度/分段时长/照片质量),本次做 UI + 持久化,
  SP3 采集读取生效;**上传不设开关,固定实时上传**(SP4 实现)
- 只做浅色主题;界面语言英文;品牌 = FieldSight(显示名、wordmark、网页端 logo)
- 技术 = M3 `lightColorScheme` 映射 + `FsColors` 扩展;组件用 M3 现成;
  包名 `com.benzn.grandtime` 不变

## 2. 令牌映射(来源 fieldsight-ui styles/tokens.css)

### 2.1 M3 colorScheme(浅色)

| M3 槽位 | 值 | web 令牌 |
|---|---|---|
| primary / onPrimary | `#102A43` / `#FFFFFF` | primary-900(顶栏、强调) |
| secondary / onSecondary | `#FFD966` / `#111827` | accent-500(CTA;黄底永远深字) |
| background / onBackground | `#F9FAFB` / `#111827` | app bg / text primary |
| surface / onSurface | `#FFFFFF` / `#111827` | 卡片 / 正文 |
| surfaceVariant / onSurfaceVariant | `#F0F4F8` / `#4B5563` | primary-50 / text secondary |
| outline / outlineVariant | `#D1D5DB` / `#E5E7EB` | border default / subtle |
| error / onError | `#DC2626` / `#FFFFFF` | danger |

### 2.2 FsColors 扩展(CompositionLocal 下发)

accentHover `#FFC107` · accentActive `#FF8F00` · surfaceSelected `#FFF7DB` ·
success `#22C55E` / successText `#15803D` · warning `#B45309` · info `#2563EB` ·
textTertiary `#6B7280` · sidebarNavy `#111827`

### 2.3 排版

系统默认字体(不打包 Inter)。页面标题 20sp/600;卡片小标题 14sp/600 次级色;
正文 16sp/400;次级 14sp/400;说明/时间戳 12sp/400 textTertiary;大状态字 24sp/700;
日志/技术文本 13sp Monospace。

### 2.4 形状与节奏

按钮/输入圆角 8dp、卡片 12dp、弹层 16dp;卡片=边框优先(1dp `#E5E7EB`、无阴影、
白底、内边距 16dp);卡片间距 12dp、页面边距 16dp;触控目标 ≥48dp。

## 3. 信息架构与页面布局(简图)

### 3.0 全局框架

```
┌────────────────────────────────────┐
│ ███ 顶栏 #102A43(海军蓝)56dp     │ ← 一级页:wordmark「Field」白+「Sight」黄
│  FieldSight              ● 绿点   │    + 右侧服务状态圆点(绿=运行/灰=停止)
├────────────────────────────────────┤    二级页:← 返回箭头 + 页面标题(白字)
│         页面内容(#F9FAFB)        │
├────────────────────────────────────┤
│  [Home]     [Files]    [Settings]  │ ← M3 NavigationBar 白底,顶部 1dp 分隔线
│   ▔▔▔▔                             │    选中项黄色指示胶囊(复刻 web 侧栏选中态)
└────────────────────────────────────┘    仅一级页显示;二级页隐藏底栏
```

图标:Home=Outlined.Home、Settings=Outlined.Settings(material-icons-core 现成);
Files=自绘 folder 简易 vector(core 集无合适图标,~10 行 path)。

### 3.1 Home 页(极简:一张卡起步,SP3/SP4 再长)

```
├────────────────────────────────────┤
│ ┌─ DEVICE ─────────────────────┐   │ ← 唯一一张卡:
│ │ ● Standing by                │   │   服务状态大字(24sp/700,绿点)
│ │ Signed in as Test account    │   │   登录身份次行(14sp 次级色)
│ └──────────────────────────────┘   │
│                                    │   (SP3 加「录制中」态与存储卡,
│                                    │    SP4 加上传/同步卡——本次不做)
```

### 3.2 Files 页(本地媒体库;本次页面+空态,SP3 落真文件)

```
├────────────────────────────────────┤
│ (All) (Video) (Audio) (Photo)     │ ← 过滤 chips:胶囊,选中=黄底深字
│ ┌──────────────────────────────┐   │
│ │      ▢ (folder 图标灰)       │   │ ← 空态(本次唯一真实状态):
│ │   No recordings yet          │   │   居中图标+主句 16sp+说明 12sp
│ │   Recordings will appear     │   │
│ │   here after you record      │   │
│ └──────────────────────────────┘   │
│  [有文件后的形态(SP3)]:          │
│  Today ────────────────            │ ← 日期分组头(12sp/600 灰)
│  ▶ video_153748.mp4  02:31  38 MB  │ ← 行:类型图标+文件名(mono)+时长+大小
│  ♪ audio_141002.m4a  05:00  4 MB   │   行高 48dp;点击 = 系统播放器打开
│  ▣ photo_120115.jpg        2.1 MB  │
```

数据源:扫描 App 媒体目录(目录契约 SP3 定,本次读一个常量路径,不存在即空态)。

### 3.3 Settings 页(一级)

```
├────────────────────────────────────┤
│ RECORDING                          │ ← 分组头(12sp/600 textTertiary)
│ ┌──────────────────────────────┐   │
│ │ Video quality        1080P ▸ │   │ ← 行高 48dp,右侧当前值+箭头,
│ │ Segment length       5 min ▸ │   │   点击弹 M3 选择对话框
│ │ Photo quality         High ▸ │   │
│ └──────────────────────────────┘   │
│ KEYS                               │
│ ┌──────────────────────────────┐   │
│ │ Key bindings               ▸ │   │ → 二级页(原 Keymap)
│ └──────────────────────────────┘   │
│ SYSTEM                             │
│ ┌──────────────────────────────┐   │
│ │ Diagnostics                ▸ │   │ → 二级页(原 Probe)
│ │ About              v0.2.0    │   │   About 只显示版本号,不可点
│ └──────────────────────────────┘   │
│ ACCOUNT                            │
│ ┌──────────────────────────────┐   │
│ │ Signed in as Test account    │   │
│ │ Sign out(SP2 起可用,当前灰)│   │ ← 预留 one-off 登录的唯一出口
│ └──────────────────────────────┘   │
```

**录制参数**(本次做 UI+持久化,SP3 生效;DataStore 文件 name="settings"):

| 键 | 选项 | 默认 |
|---|---|---|
| video_quality | 1080P / 720P / 480P | 1080P |
| video_segment_minutes | 1 / 3 / 5 / 10 | 5(执法记录仪常见默认,可随时改) |
| photo_quality | High / Standard | High |

上传行为:固定实时上传,不提供开关(产品决定,SP4 实现)。

### 3.4 Key bindings 页(二级;原 Keymap 换装)

一张卡 8 行(左:键名+按法;右:下拉按钮白底描边 8dp),改过的行 surfaceSelected
黄高亮+左 3dp 黄条+「edited」标;底部全宽黄色 CTA「Reset to defaults」(48dp)。

### 3.5 Diagnostics 页(二级;原 Probe 换装)

顶部 4 个屏幕键按钮(48dp、白底描边、按住变黄底);下方 EVENT LOG 卡:等宽 13sp,
时间戳 textTertiary、内容 onSurface,新条目在上,行高 32dp;空态「No events yet —
press a key」。

## 4. 导航实现

不引 navigation-compose(极简):`MainActivity` 持有
`Screen{Home, Files, Settings, KeyBindings, Diagnostics}` 状态 + 手写两级返回
(`BackHandler`:二级→Settings,一级→系统默认)。二级页顶栏变返回箭头+标题,隐藏底栏。

## 5. 品牌资产

- **启动图标**:自适应图标;前景 = fieldsight-ui `assets/logo.png`(710×650 透明 PNG)
  缩放居中至安全区;背景 `#102A43`。替换现有临时图标。
- **通知小图标**:重画简化「FS」单色白 vector(意象即可,替换 ic_stat_grandtime)。
- **显示名**:strings.xml `app_name` = `FieldSight`。
- **通知文案**:`FieldSight` / `Standing by` / `[stub] Start/stop video` 等英文。

## 6. 文案英文化(Labels.kt 单点)

Video key / Photo key / Audio key / SOS key;Short press / Long press;
Start/stop video · Toggle video upload · Take photo · Torch on/off · Adjust volume ·
Start/stop audio · Send SOS · Toggle warning light · None;`[桩]` → `[stub]`。

## 7. 工程结构变化

```
ui/theme/Color.kt        新增:令牌常量 + FsColors
ui/theme/Theme.kt        重写:colorScheme/Typography/Shapes + LocalFsColors
ui/AppTopBar.kt          新增:品牌顶栏(wordmark/返回态 + 状态圆点)
ui/FsCard.kt             新增:边框卡片封装(唯一自建小组件)
ui/MainActivity.kt       重写:Scaffold + 手写两级导航 + 底部 NavigationBar
ui/HomeScreen.kt         新增(替代 StatusScreen,后者删除)
ui/FilesScreen.kt        新增:过滤 chips + 空态 + 文件列表骨架
ui/SettingsScreen.kt     新增:分组设置列表 + 选择对话框
ui/KeyBindingsScreen.kt  重写自 KeymapScreen(改名)
ui/DiagnosticsScreen.kt  重写自 ProbeScreen(改名)
ui/Labels.kt             改:全英文
core/SettingsStore.kt    新增:录制参数 DataStore(video_quality 等 3 键)
service/CoreService.kt   改:通知文案英文(仅字符串)
res/mipmap-*/            新:logo 图标;res/values/strings.xml:app_name=FieldSight
app/build.gradle.kts     versionName 0.1.0 → 0.2.0(About 行经 PackageInfo 动态读取)
```

逻辑层(hardware/keymap/auth/boot)零改动;`AppState` 不变。

## 8. 错误处理与边界

- Files 扫描目录不存在/为空 → 空态(本次交付的唯一真实状态);列表行渲染骨架
  随本次落地,但**文件点击/打开行为随 SP3 真数据一起做**(FileProvider 或
  MediaStore 的选型属 SP3 存储契约)。
- 长文件名/长动作名单行截断省略。
- 屏幕键按住时切页:pointerInput 销毁时发 UP(既有保障,行为不变)。

## 9. 测试与验收

- 单测:既有 20 个零改动全绿;SettingsStore 新增 JVM 测试(默认值/写读/枚举
  非法值兜底)。
- 模拟器走查:五个页面对照 §3 简图;设置项改后杀进程重启仍保留;改键高亮;
  Diagnostics 屏幕键短/长按;Files 空态。
- 真机:图标/顶栏/48dp 触控;物理键在 Diagnostics 页可见新样式。
- 不做:截图像素比对、暗色、平板、多语言。

## 10. 范围外

登录页与真实 Sign out(SP2)、录制与 Files 真数据(SP3)、上传状态 UI(SP4)、
暗色主题、中文资源、Inter 字体打包。
