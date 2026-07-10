# FieldSight 手机端 UI 设计:极简换装(与 fieldsight-ui 同设计语言)

日期:2026-07-11
状态:已与用户逐节确认
前置:子项目1「主干」已验收合并(tag `subproject-1-accepted`)

## 1. 背景与目标

本 App 即 **FieldSight 的手机端**(GrandTime 仅为仓库代号)。当前三页 UI 是无设计的功能
堆叠;本子项目把界面换装为 fieldsight-ui(`C:/Users/camil/Dropbox/fieldsight-ui`,设计
令牌源 `styles/tokens.css`)的同款设计语言,保持极简,不加新功能。

**已确认决策**:
- 范围 = 换皮 + 轻重构(Status 页卡片化、底部导航替代顶部 TabRow)
- 只做浅色主题(户外可读性优先;暗色令牌已存在,以后需要再加)
- 品牌 = FieldSight:显示名改「FieldSight」,顶栏双色 wordmark,启动图标用网页端 logo
- 界面语言 = 英文(与产品线一致;文案集中在 Labels.kt/strings.xml)
- 技术 = 方案 A:M3 `lightColorScheme` 映射 + `FsColors` 语义扩展(CompositionLocal),
  组件用 M3 现成的,不自建组件库
- 包名 `com.benzn.grandtime`、仓库名不变(用户不可见)

## 2. 令牌映射(来源 fieldsight-ui styles/tokens.css)

### 2.1 M3 colorScheme(浅色)

| M3 槽位 | 值 | web 令牌 |
|---|---|---|
| primary / onPrimary | `#102A43` / `#FFFFFF` | primary-900(顶栏、强调) |
| secondary / onSecondary | `#FFD966` / `#111827` | accent-500(CTA;黄底永远深字,不用白字) |
| background / onBackground | `#F9FAFB` / `#111827` | app bg / text primary |
| surface / onSurface | `#FFFFFF` / `#111827` | 卡片 / 正文 |
| surfaceVariant / onSurfaceVariant | `#F0F4F8` / `#4B5563` | primary-50 / text secondary |
| outline / outlineVariant | `#D1D5DB` / `#E5E7EB` | border default / subtle |
| error / onError | `#DC2626` / `#FFFFFF` | danger btn |

### 2.2 FsColors 扩展(M3 无对应槽位)

| 名称 | 值 | 用途 |
|---|---|---|
| accentHover | `#FFC107` | 黄按钮按压态 |
| accentActive | `#FF8F00` | 黄按钮激活态 |
| surfaceSelected | `#FFF7DB`(≈ rgba(255,217,102,0.15) 落白底) | 改键行高亮 |
| success / successText | `#22C55E` / `#15803D` | 运行中圆点、成功文字 |
| warning | `#B45309` | 警示文字 |
| info | `#2563EB` | 链接/信息 |
| textTertiary | `#6B7280` | 时间戳、说明 |
| sidebarNavy | `#111827` | 保留(通知图标背景等) |

### 2.3 排版

系统默认字体(web 用 Inter 无 CJK 栈;安卓 Roboto 视觉同源,不打包字体)。

| 角色 | 规格 | web 对应 |
|---|---|---|
| 页面标题 | 20sp / 600 | text-xl heading |
| 卡片标题 | 14sp / 600,text secondary | label 风格 |
| 正文 | 16sp / 400 | text-base |
| 次级 | 14sp / 400 | text-sm |
| 说明/时间戳 | 12sp / 400,textTertiary | text-xs caption |
| 大状态字 | 24sp / 700 | KPI stat(缩配手机) |
| 探针日志 | 13sp Monospace | JetBrains Mono 角色 |

### 2.4 形状与节奏

- 圆角:按钮/输入 8dp、卡片 12dp、弹层 16dp、圆点/胶囊 full
- 卡片 = **边框优先**:1dp `#E5E7EB` 描边、无 elevation 阴影、白底、内边距 16dp
- 间距 4dp 节奏:卡片间 12dp、页面边距 16dp、区块间 24dp
- 触控目标 ≥48dp(戴手套可点)

## 3. 页面布局(简图)

### 3.0 全局框架

```
┌────────────────────────────────────┐
│ ███ 顶栏 #102A43(海军蓝)高56dp   │ ← 左:wordmark「Field」白+「Sight」黄(#FFD966)
│  FieldSight              ● 绿点   │   右:服务状态圆点(绿=Running/灰=Stopped)
├────────────────────────────────────┤
│                                    │
│         页面内容(白/灰底)        │ ← background #F9FAFB
│                                    │
├────────────────────────────────────┤
│  [Status]    [Probe]    [Keys]     │ ← M3 NavigationBar,白底,顶部 1dp 分隔线
│   ▔▔▔▔                             │   选中项:黄色指示胶囊 + primary 色图标文字
└────────────────────────────────────┘   (复刻 web 侧栏「黄底选中」语义)
图标用 material-icons-core 现成的:Status=Outlined.Home、Probe=Outlined.List、
Keys=Outlined.Settings(不引 icons-extended 大包)。
```

### 3.1 Status 页

```
├────────────────────────────────────┤
│ ┌─ SERVICE ────────────────────┐   │ ← 卡1:左上小标题(14sp/600/灰)
│ │ ● Running        [SDJW-F2SP] │   │   绿点+大字状态(24sp/700);右侧设备型号
│ │ Signed in · Test account     │   │   胶囊 chip(surfaceVariant 底)
│ └──────────────────────────────┘   │   次行:登录态(14sp 次级色)
│ ┌─ LAST ACTION ────────────────┐   │
│ │ [stub] Start/stop video      │   │ ← 卡2:最近动作大字(20sp/600);
│ │ 15:37:48                     │   │   下行时间戳(12sp mono 灰)
│ └──────────────────────────────┘   │
│ ┌─ KEY MAPPING ────────────────┐   │
│ │ Video  short  Start/stop vid │   │ ← 卡3:8 行映射(键 600 字重、按法
│ │ Video  long   Toggle upload  │   │   12sp 灰、动作右对齐 14sp)
│ │▌Photo  short  Send SOS  edited│  │ ← 被改行:整行 surfaceSelected 黄底
│ │ …(共 8 行,行高 40dp)       │   │   + 左侧 3dp 黄条 + 尾部“edited”标
│ └──────────────────────────────┘   │
```

### 3.2 Probe 页

```
├────────────────────────────────────┤
│ ┌──────┐┌──────┐┌──────┐┌──────┐   │ ← 4 个屏幕键按钮:48dp 高、白底、
│ │Video ││Photo ││Audio ││ SOS  │   │   1dp 描边、圆角 8(secondary 按钮
│ └──────┘└──────┘└──────┘└──────┘   │   样式);按住变 surfaceSelected 黄底
│ ┌─ EVENT LOG ──────────────────┐   │
│ │ 15:37:48.046  VIDEO LONG     │   │ ← 卡内日志列表:等宽 13sp;
│ │ 15:37:48.043  lolaage.video1…│   │   时间戳 textTertiary、内容 onSurface
│ │ 15:37:47.039  lolaage.video1…│   │   新条目在最上;行高 32dp(compact)
│ │ …                            │   │
│ └──────────────────────────────┘   │
```

### 3.3 Keys 页

```
├────────────────────────────────────┤
│ ┌─ KEY BINDINGS ───────────────┐   │
│ │ Video · short   [Start/stop▾]│   │ ← 8 行:左侧键名+按法,右侧下拉按钮
│ │ Video · long    [Toggle up.▾]│   │   (白底描边 8dp 圆角,点开 M3 菜单)
│ │ …(行高 48dp)                │   │   改过的行同 Status 页黄高亮
│ └──────────────────────────────┘   │
│                                    │
│ ┌──────────────────────────────┐   │ ← 黄色主 CTA:secondary 色填充、
│ │      Reset to defaults       │   │   深色字、48dp 高、全宽、圆角 8
│ └──────────────────────────────┘   │
```

## 4. 品牌资产

- **启动图标**:自适应图标;前景 = fieldsight-ui `assets/logo.png`(710×650 透明 PNG,
  黄橙 FS 图形)缩放居中至安全区(108dp 画布中约 66dp);背景纯色 `#102A43`。
  替换现有临时图标(ic_launcher_foreground.xml 弃用)。
- **通知小图标**:系统要求单色;极简处理:重画一个简化的「FS」单色白 vector path
  (近似 logo 轮廓的意象即可,不追求像素还原;替换现有 ic_stat_grandtime)。
- **显示名**:strings.xml `app_name` = `FieldSight`。
- **通知文案**:`FieldSight` / `Standing by` / `[stub] Start/stop video` 等英文。

## 5. 文案英文化(Labels.kt 单点)

| 枚举 | 英文文案 |
|---|---|
| VIDEO/PHOTO/AUDIO/SOS | Video key / Photo key / Audio key / SOS key |
| SHORT/LONG | Short press / Long press |
| START_STOP_VIDEO | Start/stop video |
| TOGGLE_VIDEO_UPLOAD | Toggle video upload |
| TAKE_PHOTO | Take photo |
| TOGGLE_TORCH | Torch on/off |
| ADJUST_VOLUME | Adjust volume |
| START_STOP_AUDIO | Start/stop audio |
| SEND_SOS | Send SOS |
| TOGGLE_WARNING_LIGHT | Toggle warning light |
| NONE | None |

桩前缀 `[桩]` → `[stub]`。页面名:Status / Probe / Keys。

## 6. 工程结构变化

```
ui/theme/Color.kt        新增:web 令牌常量(Navy900、Accent500…)+ FsColors 类
ui/theme/Theme.kt        重写:lightColorScheme 映射 + LocalFsColors + Typography + Shapes
ui/theme/Type.kt         新增:排版刻度
ui/AppTopBar.kt          新增:海军蓝品牌顶栏(wordmark + 状态圆点)
ui/MainActivity.kt       改:TabRow → Scaffold(topBar + NavigationBar);页面名英文
ui/StatusScreen.kt       重写:三卡布局(§3.1)
ui/ProbeScreen.kt        重写:按钮行 + 日志卡(§3.2)
ui/KeymapScreen.kt       重写:绑定卡 + 黄色 CTA(§3.3)
ui/Labels.kt             改:全英文(§5)
ui/FsCard.kt             新增:边框卡片封装(唯一自建的小组件,~15 行)
res/mipmap-*/            新:logo 前景 PNG 各密度 + 背景色
res/values/strings.xml   app_name = FieldSight
service/CoreService.kt   改:通知文案英文(仅字符串)
```

逻辑层(hardware/keymap/auth/core/boot)**零改动**。

## 7. 错误处理与边界

- 屏幕键按住时导航切页:pointerInput 的 waitForUpOrCancellation 在组件销毁时发 UP,
  行为与现状一致(已有该保障)。
- 日志空态:Probe 卡内显示「No events yet — press a key」(textTertiary)。
- 长设备型号/长动作名:单行截断加省略号。

## 8. 测试与验收

- **单测**:20 个既有测试零改动全绿(文案不在断言里)。
- **模拟器走查**:三页布局与本 spec §3 简图逐一比对;改键后 Status/Keys 黄高亮出现、
  reset 后消失;屏幕键按住 >1s 出 LONG。
- **真机**:装 F2SP 看启动图标、顶栏 wordmark、48dp 触控目标;按两个物理键看探针新样式。
- **不做**:截图像素比对、暗色模式、平板适配(YAGNI)。

## 9. 范围外(明确不做)

- 登录页/账号 UI(SP2)、录制相关 UI(SP3)、上传状态 UI(SP4)
- 暗色主题(令牌已备,后续一次性加)
- 中文/多语言资源(以后国际化再做)
- Inter 字体打包
