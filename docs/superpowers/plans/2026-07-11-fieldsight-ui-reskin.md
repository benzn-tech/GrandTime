# FieldSight UI 换装 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把调试骨架 UI 翻新为产品形态:Home/Files/Settings 三 tab + 二级页(Key bindings/Diagnostics),fieldsight-ui 同设计语言,英文文案,FieldSight 品牌。

**Architecture:** M3 `lightColorScheme` 映射 web 令牌 + `FsColors` CompositionLocal 扩展;组件用 M3 现成(唯一自建 FsCard);手写两级导航(无 navigation-compose);录制参数 SettingsStore(DataStore)本次持久化、SP3 生效。逻辑层(hardware/keymap/boot)零改动。

**Tech Stack:** 既有栈不变(Kotlin 2.1 / Compose BOM 2024.12.01 / DataStore 1.1.1);无新依赖。

Spec(唯一事实来源):`docs/superpowers/specs/2026-07-11-fieldsight-ui-theme-design.md`

## Global Constraints

- 工作分支:`feature/ui-reskin`(自 main 切出;控制器负责创建)
- 颜色/字号/圆角/间距值以 spec §2 为准,精确到 hex/sp/dp,不得自创
- 黄底(secondary `#FFD966`)上的内容永远用深色 `#111827`,不用白色
- 卡片=边框优先:1dp `outlineVariant`、无 elevation、圆角 12dp
- 英文文案;`[桩]` → `[stub]`;显示名 FieldSight;versionName 0.2.0
- 逻辑层零改动例外(spec §6 英文化意图管辖):`StubAuthManager` 的 displayName「测试账号」→「Test account」、`CoreService` 通知字符串
- 所有 gradle 命令前缀:`export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`
- **当前有两台设备在线**(emulator-5554 与真机 F2S202503103054):模拟器验证一律 `export ANDROID_SERIAL=emulator-5554` 后再跑 `./gradlew installDebug`,adb 命令一律带 `-s emulator-5554`;真机只在 Task 10 最后一步用 `-s F2S202503103054`
- Dropbox 文件锁导致构建瞬时失败:重跑一次;持续失败才报 BLOCKED
- 每个 Task 结束 commit(带 Claude co-author footer)

---

### Task 1: SettingsStore(录制参数持久化,TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/core/SettingsStore.kt`
- Test: `app/src/test/java/com/benzn/grandtime/core/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `val Context.settingsDataStore: DataStore<Preferences>`(name="settings");`enum VideoQuality(label){P1080("1080P"),P720("720P"),P480("480P")}`;`enum PhotoQuality(label){HIGH("High"),STANDARD("Standard")}`;`data class RecordingSettings(videoQuality=P1080, segmentMinutes=5, photoQuality=HIGH)`;`class SettingsStore(dataStore)`:`settings: Flow<RecordingSettings>`、`suspend setVideoQuality/setSegmentMinutes/setPhotoQuality`、`SettingsStore.SEGMENT_OPTIONS = listOf(1,3,5,10)`。非法存储值静默回落默认。

- [ ] **Step 1: 写失败测试 `SettingsStoreTest.kt`**

```kotlin
package com.benzn.grandtime.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newStore(): Pair<SettingsStore, DataStore<Preferences>> {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
        ) { File(tmp.root, "settings.preferences_pb") }
        return SettingsStore(ds) to ds
    }

    @Test
    fun `defaults when empty`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertEquals(
            RecordingSettings(VideoQuality.P1080, 5, PhotoQuality.HIGH),
            store.settings.first(),
        )
    }

    @Test
    fun `write and read back`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        store.setVideoQuality(VideoQuality.P480)
        store.setSegmentMinutes(10)
        store.setPhotoQuality(PhotoQuality.STANDARD)
        assertEquals(
            RecordingSettings(VideoQuality.P480, 10, PhotoQuality.STANDARD),
            store.settings.first(),
        )
    }

    @Test
    fun `invalid stored values fall back to defaults`() = runTest(UnconfinedTestDispatcher()) {
        val (store, ds) = newStore()
        ds.edit {
            it[stringPreferencesKey("video_quality")] = "BOGUS"
            it[intPreferencesKey("video_segment_minutes")] = 42
            it[stringPreferencesKey("photo_quality")] = "ULTRA"
        }
        assertEquals(RecordingSettings(), store.settings.first())
    }

    @Test
    fun `setSegmentMinutes rejects values outside options`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setSegmentMinutes(2) }
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && cd "C:/Users/camil/Dropbox/GrandTime" && ./gradlew test
```

Expected: FAIL(unresolved reference SettingsStore 等)。

- [ ] **Step 3: 实现 `core/SettingsStore.kt`**

```kotlin
package com.benzn.grandtime.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class VideoQuality(val label: String) { P1080("1080P"), P720("720P"), P480("480P") }

enum class PhotoQuality(val label: String) { HIGH("High"), STANDARD("Standard") }

/** 录制参数:本子项目仅持久化;SP3 采集读取生效。上传固定实时,无开关(产品决定)。 */
data class RecordingSettings(
    val videoQuality: VideoQuality = VideoQuality.P1080,
    val segmentMinutes: Int = 5,
    val photoQuality: PhotoQuality = PhotoQuality.HIGH,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        val SEGMENT_OPTIONS = listOf(1, 3, 5, 10)
        private val KEY_VIDEO_QUALITY = stringPreferencesKey("video_quality")
        private val KEY_SEGMENT_MINUTES = intPreferencesKey("video_segment_minutes")
        private val KEY_PHOTO_QUALITY = stringPreferencesKey("photo_quality")
    }

    val settings: Flow<RecordingSettings> = dataStore.data.map { prefs ->
        RecordingSettings(
            videoQuality = prefs[KEY_VIDEO_QUALITY]
                ?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } }
                ?: VideoQuality.P1080,
            segmentMinutes = prefs[KEY_SEGMENT_MINUTES]?.takeIf { it in SEGMENT_OPTIONS } ?: 5,
            photoQuality = prefs[KEY_PHOTO_QUALITY]
                ?.let { name -> PhotoQuality.entries.firstOrNull { it.name == name } }
                ?: PhotoQuality.HIGH,
        )
    }

    suspend fun setVideoQuality(value: VideoQuality) {
        dataStore.edit { it[KEY_VIDEO_QUALITY] = value.name }
    }

    suspend fun setSegmentMinutes(value: Int) {
        require(value in SEGMENT_OPTIONS) { "segment minutes must be one of $SEGMENT_OPTIONS" }
        dataStore.edit { it[KEY_SEGMENT_MINUTES] = value }
    }

    suspend fun setPhotoQuality(value: PhotoQuality) {
        dataStore.edit { it[KEY_PHOTO_QUALITY] = value.name }
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(新 4 个 + 既有 20 个)。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: recording settings store (video/segment/photo, DataStore)"
```

---

### Task 2: 主题层(令牌 → Compose)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/ui/theme/Color.kt`
- Rewrite: `app/src/main/java/com/benzn/grandtime/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`(仅把 `GrandTimeTheme` 引用改为 `FieldSightTheme`,其余不动)
- Modify: `app/src/main/res/values/themes.xml`(状态栏海军蓝)

**Interfaces:**
- Produces: `FieldSightTheme { }`(替代 GrandTimeTheme,旧名删除);`LocalFsColors: CompositionLocal<FsColors>`,`FsColors(accentHover, accentActive, surfaceSelected, successDot, successText, warningText, info, textTertiary, sidebarNavy)`;颜色常量 `Navy900/Navy50/Accent500/AppBg/SurfaceWhite/TextPrimary/TextSecondary/TextTertiary/BorderDefault/BorderSubtle/DangerBtn/SurfaceSelected/SuccessDot/...`;`MaterialTheme.typography` 刻度(titleLarge 20/600、titleSmall 14/600、bodyLarge 16、bodyMedium 14、bodySmall 12、headlineSmall 24/700、labelLarge 14/600);`MaterialTheme.shapes`(small 8dp / medium 12dp / large 16dp)。

- [ ] **Step 1: `ui/theme/Color.kt`**

```kotlin
package com.benzn.grandtime.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// fieldsight-ui styles/tokens.css 浅色令牌
val Navy900 = Color(0xFF102A43)
val Navy50 = Color(0xFFF0F4F8)
val Accent500 = Color(0xFFFFD966)
val AccentHover = Color(0xFFFFC107)
val AccentActive = Color(0xFFFF8F00)
val AppBg = Color(0xFFF9FAFB)
val SurfaceWhite = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF111827)
val TextSecondary = Color(0xFF4B5563)
val TextTertiary = Color(0xFF6B7280)
val BorderDefault = Color(0xFFD1D5DB)
val BorderSubtle = Color(0xFFE5E7EB)
val DangerBtn = Color(0xFFDC2626)
val SurfaceSelected = Color(0xFFFFF7DB)
val SuccessDot = Color(0xFF22C55E)
val SuccessText = Color(0xFF15803D)
val WarningText = Color(0xFFB45309)
val InfoLink = Color(0xFF2563EB)
val SidebarNavy = Color(0xFF111827)

/** M3 colorScheme 盖不住的 FieldSight 语义色。 */
data class FsColors(
    val accentHover: Color = AccentHover,
    val accentActive: Color = AccentActive,
    val surfaceSelected: Color = SurfaceSelected,
    val successDot: Color = SuccessDot,
    val successText: Color = SuccessText,
    val warningText: Color = WarningText,
    val info: Color = InfoLink,
    val textTertiary: Color = TextTertiary,
    val sidebarNavy: Color = SidebarNavy,
)

val LocalFsColors = staticCompositionLocalOf { FsColors() }
```

- [ ] **Step 2: 重写 `ui/theme/Theme.kt`**

```kotlin
package com.benzn.grandtime.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FsColorScheme = lightColorScheme(
    primary = Navy900,
    onPrimary = SurfaceWhite,
    secondary = Accent500,
    onSecondary = TextPrimary,
    background = AppBg,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = Navy50,
    onSurfaceVariant = TextSecondary,
    outline = BorderDefault,
    outlineVariant = BorderSubtle,
    error = DangerBtn,
    onError = SurfaceWhite,
)

private val FsTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

private val FsShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun FieldSightTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFsColors provides FsColors()) {
        MaterialTheme(
            colorScheme = FsColorScheme,
            typography = FsTypography,
            shapes = FsShapes,
            content = content,
        )
    }
}
```

- [ ] **Step 3: MainActivity 引用替换**

把 `import com.benzn.grandtime.ui.theme.GrandTimeTheme` 改为
`import com.benzn.grandtime.ui.theme.FieldSightTheme`,`GrandTimeTheme {` 改为
`FieldSightTheme {`。其余不动(整体重写在 Task 9)。

- [ ] **Step 4: `res/values/themes.xml`(状态栏)**

```xml
<resources>
    <style name="Theme.GrandTime" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:statusBarColor">#102A43</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

- [ ] **Step 5: 构建 + 测试**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: 双 BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add app/src app/src/main/res && git commit -m "feat: FieldSight theme (token colorScheme, FsColors, typography, shapes)"
```

---

### Task 3: 英文文案 + 品牌命名 + 版本号

**Files:**
- Rewrite: `app/src/main/java/com/benzn/grandtime/ui/Labels.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/service/CoreService.kt`(仅字符串)
- Modify: `app/src/main/java/com/benzn/grandtime/auth/StubAuthManager.kt`(仅 displayName)
- Modify: `app/src/main/res/values/strings.xml`、`app/build.gradle.kts`(versionName)

**Interfaces:**
- Produces: `keyLabel/pressLabel/actionLabel`(英文)+ 新增 `shortKeyLabel(HardKey): String`("Video"/"Photo"/"Audio"/"SOS")与 `shortPressLabel(PressType): String`("short"/"long")——Task 6/8 的屏幕键按钮与绑定行使用。

- [ ] **Step 1: 重写 `ui/Labels.kt`**

```kotlin
package com.benzn.grandtime.ui

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.PressType
import com.benzn.grandtime.keymap.KeyAction

fun keyLabel(key: HardKey): String = when (key) {
    HardKey.VIDEO -> "Video key"
    HardKey.PHOTO -> "Photo key"
    HardKey.AUDIO -> "Audio key"
    HardKey.SOS -> "SOS key"
}

fun shortKeyLabel(key: HardKey): String = when (key) {
    HardKey.VIDEO -> "Video"
    HardKey.PHOTO -> "Photo"
    HardKey.AUDIO -> "Audio"
    HardKey.SOS -> "SOS"
}

fun pressLabel(pressType: PressType): String = when (pressType) {
    PressType.SHORT -> "Short press"
    PressType.LONG -> "Long press"
}

fun shortPressLabel(pressType: PressType): String = when (pressType) {
    PressType.SHORT -> "short"
    PressType.LONG -> "long"
}

fun actionLabel(action: KeyAction): String = when (action) {
    KeyAction.START_STOP_VIDEO -> "Start/stop video"
    KeyAction.TOGGLE_VIDEO_UPLOAD -> "Toggle video upload"
    KeyAction.TAKE_PHOTO -> "Take photo"
    KeyAction.TOGGLE_TORCH -> "Torch on/off"
    KeyAction.ADJUST_VOLUME -> "Adjust volume"
    KeyAction.START_STOP_AUDIO -> "Start/stop audio"
    KeyAction.SEND_SOS -> "Send SOS"
    KeyAction.TOGGLE_WARNING_LIGHT -> "Toggle warning light"
    KeyAction.NONE -> "None"
}
```

- [ ] **Step 2: CoreService 字符串英文化(其余逻辑一行不动)**

- `startForeground(NOTIFICATION_ID, buildNotification("待命"))` → `buildNotification("Standing by")`
- `NotificationChannel(CHANNEL_ID, "GrandTime 常驻", …)` → `"FieldSight service"`
- `.setContentTitle("GrandTime")` → `.setContentTitle("FieldSight")`
- `val text = "[桩] ${actionLabel(action)}"` → `val text = "[stub] ${actionLabel(action)}"`

- [ ] **Step 3: StubAuthManager displayName**

`LoginState.LoggedIn("测试账号")` → `LoginState.LoggedIn("Test account")`。

- [ ] **Step 4: strings.xml 与版本号**

`<string name="app_name">GrandTime</string>` → `FieldSight`;
`app/build.gradle.kts` 中 `versionName = "0.1.0"` → `"0.2.0"`。

- [ ] **Step 5: 构建 + 全量测试**

```bash
./gradlew assembleDebug && ./gradlew test
```

Expected: 双 BUILD SUCCESSFUL(文案不在任何断言里)。

- [ ] **Step 6: Commit**

```bash
git add app/src app/build.gradle.kts && git commit -m "feat: English copy, FieldSight app name, version 0.2.0"
```

---

### Task 4: 品牌资产(启动图标 + 通知小图标)

**Files:**
- Create: `app/src/main/res/drawable-nodpi/fs_logo.png`(由 fieldsight-ui logo 方形化生成)
- Create: `app/src/main/res/drawable/ic_launcher_fg.xml`、`app/src/main/res/values/colors.xml`
- Rewrite: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/drawable/ic_stat_fieldsight.xml`
- Delete: `app/src/main/res/drawable/ic_launcher_foreground.xml`、`app/src/main/res/drawable/ic_stat_grandtime.xml`
- Modify: `app/src/main/java/com/benzn/grandtime/service/CoreService.kt`(smallIcon 引用一行)

- [ ] **Step 1: 方形化 logo(PowerShell .NET,原图 710×650 → 768×768 透明画布居中)**

用 PowerShell 工具执行:

```powershell
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile("C:\Users\camil\Dropbox\fieldsight-ui\assets\logo.png")
$size = 768
$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$scale = [Math]::Min(($size * 1.0) / $src.Width, ($size * 1.0) / $src.Height)
$w = [int]($src.Width * $scale); $h = [int]($src.Height * $scale)
$x = [int](($size - $w) / 2); $y = [int](($size - $h) / 2)
$g.DrawImage($src, $x, $y, $w, $h)
$g.Dispose()
New-Item -ItemType Directory -Force "C:\Users\camil\Dropbox\GrandTime\app\src\main\res\drawable-nodpi" | Out-Null
$bmp.Save("C:\Users\camil\Dropbox\GrandTime\app\src\main\res\drawable-nodpi\fs_logo.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose(); $src.Dispose()
```

Expected: 生成的 fs_logo.png 为 768×768(PowerShell 里再读一次尺寸核对)。

- [ ] **Step 2: 自适应图标三件套**

`res/values/colors.xml`:

```xml
<resources>
    <color name="ic_launcher_bg">#102A43</color>
</resources>
```

`res/drawable/ic_launcher_fg.xml`(fs_logo 已方形,inset 均匀缩进安全区):

```xml
<inset xmlns:android="http://schemas.android.com/apk/res/android"
    android:drawable="@drawable/fs_logo"
    android:inset="20%" />
```

`res/mipmap-anydpi-v26/ic_launcher.xml` 重写:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_bg" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />
</adaptive-icon>
```

删除 `res/drawable/ic_launcher_foreground.xml`。

- [ ] **Step 3: 通知小图标(眼睛意象,单色白,evenOdd 挖孔)**

`res/drawable/ic_stat_fieldsight.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:fillType="evenOdd"
        android:pathData="M12,5.5 C6.5,5.5 2.5,12 2.5,12 C2.5,12 6.5,18.5 12,18.5 C17.5,18.5 21.5,12 21.5,12 C21.5,12 17.5,5.5 12,5.5 Z M12,15.5 A3.5,3.5 0 1,1 12,8.5 A3.5,3.5 0 1,1 12,15.5 Z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M12,13.8 A1.8,1.8 0 1,0 12,10.2 A1.8,1.8 0 1,0 12,13.8 Z" />
</vector>
```

CoreService:`R.drawable.ic_stat_grandtime` → `R.drawable.ic_stat_fieldsight`;
删除 `res/drawable/ic_stat_grandtime.xml`。

- [ ] **Step 4: 构建 + 模拟器图标核验**

```bash
export ANDROID_SERIAL=emulator-5554 && ./gradlew installDebug
"$ADB" -s emulator-5554 shell am start -n com.benzn.grandtime/.ui.MainActivity
```

Expected: BUILD SUCCESSFUL;模拟器桌面/抽屉图标 = 海军蓝底 + 黄色 FS 图形,应用名 FieldSight;通知栏图标为眼睛剪影。

- [ ] **Step 5: 全量测试仍绿 + Commit**

```bash
./gradlew test
git add -A app/src/main/res app/src/main/java && git commit -m "feat: FieldSight launcher icon (web logo) and eye notification glyph"
```

---

### Task 5: 基础组件(Screen 枚举 + FsCard + AppTopBar + Files 图标)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/ui/Screen.kt`、`.../ui/FsCard.kt`、`.../ui/AppTopBar.kt`
- Create: `app/src/main/res/drawable/ic_nav_files.xml`

**Interfaces:**
- Produces: `enum Screen(title){HOME("Home"),FILES("Files"),SETTINGS("Settings"),KEY_BINDINGS("Key bindings"),DIAGNOSTICS("Diagnostics")}`;`FsCard(modifier, contentPadding: Dp = 16.dp, content: ColumnScope.() -> Unit)`;`ColumnScope.FsCardTitle(text)`(大写小标题+8dp 间隔);`AppTopBar(title: String?, showBack: Boolean, onBack: () -> Unit, serviceRunning: Boolean)`;`R.drawable.ic_nav_files`。

- [ ] **Step 1: `ui/Screen.kt`**

```kotlin
package com.benzn.grandtime.ui

enum class Screen(val title: String) {
    HOME("Home"),
    FILES("Files"),
    SETTINGS("Settings"),
    KEY_BINDINGS("Key bindings"),
    DIAGNOSTICS("Diagnostics"),
}
```

- [ ] **Step 2: `ui/FsCard.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** FieldSight 边框卡:1dp subtle 描边、白底、圆角 12、无阴影。 */
@Composable
fun FsCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun ColumnScope.FsCardTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}
```

- [ ] **Step 3: `ui/AppTopBar.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benzn.grandtime.ui.theme.LocalFsColors

/** 海军蓝品牌顶栏:一级页 wordmark(Field 白 + Sight 黄),二级页返回箭头+标题;右侧服务状态圆点。 */
@Composable
fun AppTopBar(title: String?, showBack: Boolean, onBack: () -> Unit, serviceRunning: Boolean) {
    val fs = LocalFsColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                title.orEmpty(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
        } else {
            Spacer(Modifier.width(8.dp))
            val accent = MaterialTheme.colorScheme.secondary
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("Field") }
                    withStyle(SpanStyle(color = accent)) { append("Sight") }
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(if (serviceRunning) fs.successDot else MaterialTheme.colorScheme.outline)
        )
    }
}
```

- [ ] **Step 4: `res/drawable/ic_nav_files.xml`(folder)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M3,7 a2,2 0 0 1 2,-2 h4 l2,2 h8 a2,2 0 0 1 2,2 v8 a2,2 0 0 1 -2,2 H5 a2,2 0 0 1 -2,-2 Z" />
</vector>
```

- [ ] **Step 5: 构建 + Commit**

```bash
./gradlew assembleDebug
git add app/src && git commit -m "feat: Screen enum, FsCard, brand top bar, files nav icon"
```

---

### Task 6: HomeScreen + FilesScreen

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/ui/HomeScreen.kt`、`.../ui/FilesScreen.kt`

**Interfaces:**
- Consumes: `FsCard/FsCardTitle`(Task 5)、`LocalFsColors`(Task 2)、`AppState.serviceRunning/loginState`、`LoginState`
- Produces: `HomeScreen()`、`FilesScreen()`(Task 9 的 MainActivity 调用)。媒体目录契约(SP3 落文件用):`getExternalFilesDir(null)/media/{video,audio,photo}`。

- [ ] **Step 1: `ui/HomeScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.ui.theme.LocalFsColors

@Composable
fun HomeScreen() {
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    val login by AppState.loginState.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        FsCard {
            FsCardTitle("Device")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (running) fs.successDot else MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (running) "Standing by" else "Service stopped",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (val s = login) {
                    is LoginState.LoggedIn -> "Signed in as ${s.displayName}"
                    LoginState.LoggedOut -> "Not signed in"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 2: `ui/FilesScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benzn.grandtime.R
import com.benzn.grandtime.ui.theme.LocalFsColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MediaFilter(val label: String) { ALL("All"), VIDEO("Video"), AUDIO("Audio"), PHOTO("Photo") }

data class MediaEntry(val name: String, val type: MediaFilter, val sizeBytes: Long, val modifiedMillis: Long)

@Composable
fun FilesScreen() {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    val entries = remember { scanMedia(context) }
    val filtered = if (filter == MediaFilter.ALL) entries else entries.filter { it.type == filter }
    val fs = LocalFsColors.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            MediaFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                )
            }
        }
        if (filtered.isEmpty()) {
            FsCard {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_nav_files),
                        contentDescription = null,
                        tint = fs.textTertiary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No recordings yet", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Recordings will appear here after you record",
                        style = MaterialTheme.typography.bodySmall,
                        color = fs.textTertiary,
                    )
                }
            }
        } else {
            val grouped = filtered.groupBy { dayLabel(it.modifiedMillis) }
            LazyColumn(Modifier.weight(1f)) {
                grouped.forEach { (day, dayItems) ->
                    item(key = "header-$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = fs.textTertiary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(dayItems, key = { "${it.type}-${it.name}" }) { entry -> MediaRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(entry: MediaEntry) {
    val fs = LocalFsColors.current
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                entry.type.label.first().toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            entry.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(formatSize(entry.sizeBytes), style = MaterialTheme.typography.bodySmall, color = fs.textTertiary)
    }
}

/** SP3 存储契约:录制文件落 getExternalFilesDir(null)/media/{video,audio,photo}。 */
private fun scanMedia(context: Context): List<MediaEntry> {
    val root = File(context.getExternalFilesDir(null), "media")
    val dirs = mapOf(
        "video" to MediaFilter.VIDEO,
        "audio" to MediaFilter.AUDIO,
        "photo" to MediaFilter.PHOTO,
    )
    return dirs.flatMap { (dirName, type) ->
        File(root, dirName).listFiles()?.filter { it.isFile }?.map { f ->
            MediaEntry(f.name, type, f.length(), f.lastModified())
        } ?: emptyList()
    }.sortedByDescending { it.modifiedMillis }
}

private fun dayLabel(millis: Long): String {
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
    val display = SimpleDateFormat("d MMM yyyy", Locale.US)
    return if (dayKey.format(Date(millis)) == dayKey.format(Date())) "Today" else display.format(Date(millis))
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
```

- [ ] **Step 3: 构建 + Commit**

```bash
./gradlew assembleDebug && ./gradlew test
git add app/src && git commit -m "feat: Home and Files screens (empty-state media library)"
```

---

### Task 7: SettingsScreen(分组设置 + 选择对话框)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SettingsStore/settingsDataStore/RecordingSettings/VideoQuality/PhotoQuality`(Task 1)、`FsCard`(Task 5)、`Screen`(Task 5)、`AppState.loginState`
- Produces: `SettingsScreen(onOpen: (Screen) -> Unit)`(Task 9 调用,onOpen 收 `Screen.KEY_BINDINGS` / `Screen.DIAGNOSTICS`)。

- [ ] **Step 1: `ui/SettingsScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.core.PhotoQuality
import com.benzn.grandtime.core.RecordingSettings
import com.benzn.grandtime.core.SettingsStore
import com.benzn.grandtime.core.VideoQuality
import com.benzn.grandtime.core.settingsDataStore
import com.benzn.grandtime.ui.theme.LocalFsColors
import kotlinx.coroutines.launch

private enum class SettingDialog { VIDEO_QUALITY, SEGMENT, PHOTO_QUALITY }

@Composable
fun SettingsScreen(onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext.settingsDataStore) }
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = RecordingSettings())
    val login by AppState.loginState.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        GroupHeader("Recording")
        FsCard(contentPadding = 0.dp) {
            SettingRow("Video quality", settings.videoQuality.label) { dialog = SettingDialog.VIDEO_QUALITY }
            RowDivider()
            SettingRow("Segment length", "${settings.segmentMinutes} min") { dialog = SettingDialog.SEGMENT }
            RowDivider()
            SettingRow("Photo quality", settings.photoQuality.label) { dialog = SettingDialog.PHOTO_QUALITY }
        }
        GroupHeader("Keys")
        FsCard(contentPadding = 0.dp) {
            SettingRow("Key bindings", null) { onOpen(Screen.KEY_BINDINGS) }
        }
        GroupHeader("System")
        FsCard(contentPadding = 0.dp) {
            SettingRow("Diagnostics", null) { onOpen(Screen.DIAGNOSTICS) }
            RowDivider()
            SettingRow("About", versionName, onClick = null)
        }
        GroupHeader("Account")
        FsCard(contentPadding = 0.dp) {
            SettingRow(
                when (val s = login) {
                    is LoginState.LoggedIn -> "Signed in as ${s.displayName}"
                    LoginState.LoggedOut -> "Not signed in"
                },
                null,
                onClick = null,
            )
            RowDivider()
            SettingRow("Sign out", "Available after account setup", enabled = false, onClick = null)
        }
        Spacer(Modifier.height(24.dp))
    }

    when (dialog) {
        SettingDialog.VIDEO_QUALITY -> RadioDialog(
            title = "Video quality",
            options = VideoQuality.entries,
            selected = settings.videoQuality,
            label = { it.label },
            onSelect = { scope.launch { store.setVideoQuality(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.SEGMENT -> RadioDialog(
            title = "Segment length",
            options = SettingsStore.SEGMENT_OPTIONS,
            selected = settings.segmentMinutes,
            label = { "$it min" },
            onSelect = { scope.launch { store.setSegmentMinutes(it) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.PHOTO_QUALITY -> RadioDialog(
            title = "Photo quality",
            options = PhotoQuality.entries,
            selected = settings.photoQuality,
            label = { it.label },
            onSelect = { scope.launch { store.setPhotoQuality(it) } },
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalFsColors.current.textTertiary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp),
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingRow(
    title: String,
    value: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val fs = LocalFsColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else fs.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else fs.textTertiary,
            )
        }
        if (onClick != null && enabled) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = fs.textTertiary,
            )
        }
    }
}

@Composable
private fun <T> RadioDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { onSelect(option); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}
```

- [ ] **Step 2: 构建 + Commit**

```bash
./gradlew assembleDebug && ./gradlew test
git add app/src && git commit -m "feat: Settings screen (recording prefs, keys/system/account groups)"
```

---

### Task 8: KeyBindingsScreen + DiagnosticsScreen(二级页,换装)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/ui/KeyBindingsScreen.kt`、`.../ui/DiagnosticsScreen.kt`
(旧 KeymapScreen/ProbeScreen 由 Task 9 删除)

**Interfaces:**
- Consumes: `FsCard/FsCardTitle`、`shortKeyLabel/shortPressLabel/actionLabel`(Task 3)、`KeyMapStore/keymapDataStore/KeyMapping`、`AppState.overrides/probeEntries/screenKeyEvents`、`LocalFsColors`
- Produces: `KeyBindingsScreen()`、`DiagnosticsScreen()`(Task 9 调用)。

- [ ] **Step 1: `ui/KeyBindingsScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType
import com.benzn.grandtime.keymap.KeyAction
import com.benzn.grandtime.keymap.KeyMapStore
import com.benzn.grandtime.keymap.KeyMapping
import com.benzn.grandtime.keymap.keymapDataStore
import com.benzn.grandtime.ui.theme.LocalFsColors
import kotlinx.coroutines.launch

@Composable
fun KeyBindingsScreen() {
    val context = LocalContext.current
    val store = remember { KeyMapStore(context.applicationContext.keymapDataStore) }
    val scope = rememberCoroutineScope()
    val overrides by AppState.overrides.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        FsCard(contentPadding = 0.dp) {
            HardKey.entries.forEach { key ->
                PressType.entries.forEach { pressType ->
                    val current = KeyMapping.resolve(KeyPress(key, pressType), overrides)
                    val overridden = KeyMapping.overrideKeyOf(key, pressType) in overrides
                    var menuOpen by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(if (overridden) fs.surfaceSelected else Color.Transparent)
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(if (overridden) MaterialTheme.colorScheme.secondary else Color.Transparent)
                        )
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${shortKeyLabel(key)} · ${shortPressLabel(pressType)}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (overridden) {
                                Text("edited", style = MaterialTheme.typography.bodySmall, color = fs.textTertiary)
                            }
                        }
                        Box {
                            OutlinedButton(onClick = { menuOpen = true }, shape = MaterialTheme.shapes.small) {
                                Text(actionLabel(current), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                KeyAction.entries.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(actionLabel(action)) },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch { store.setOverride(key, pressType, action) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { scope.launch { store.resetToDefaults() } },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
        ) { Text("Reset to defaults") }
    }
}
```

- [ ] **Step 2: `ui/DiagnosticsScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.RawDirection
import com.benzn.grandtime.ui.theme.LocalFsColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen() {
    val entries by AppState.probeEntries.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    var pressed by remember { mutableStateOf<HardKey?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HardKey.entries.forEach { key ->
                OutlinedButton(
                    onClick = {},
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (pressed == key) fs.surfaceSelected else MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .pointerInput(key) {
                            awaitEachGesture {
                                // requireUnconsumed=false:不与按钮自身的点击手势竞争
                                awaitFirstDown(requireUnconsumed = false)
                                pressed = key
                                AppState.screenKeyEvents.tryEmit(key to RawDirection.DOWN)
                                waitForUpOrCancellation()
                                pressed = null
                                AppState.screenKeyEvents.tryEmit(key to RawDirection.UP)
                            }
                        },
                ) { Text(shortKeyLabel(key), maxLines = 1) }
            }
        }
        FsCard(modifier = Modifier.weight(1f)) {
            FsCardTitle("Event log")
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "No events yet — press a key",
                        style = MaterialTheme.typography.bodySmall,
                        color = fs.textTertiary,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(entries) { entry ->
                        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                timeFormat.format(Date(entry.timestampMillis)),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = fs.textTertiary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                entry.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: 构建 + Commit**

```bash
./gradlew assembleDebug && ./gradlew test
git add app/src && git commit -m "feat: Key bindings and Diagnostics sub-screens (FieldSight styling)"
```

---

### Task 9: 导航壳切换 + 删旧屏

**Files:**
- Rewrite: `app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`
- Delete: `app/src/main/java/com/benzn/grandtime/ui/StatusScreen.kt`、`.../ui/ProbeScreen.kt`、`.../ui/KeymapScreen.kt`

**Interfaces:**
- Consumes: 前面全部任务的产出(Screen/AppTopBar/FsCard/五个页面/FieldSightTheme)。

- [ ] **Step 1: 重写 `ui/MainActivity.kt`**

```kotlin
package com.benzn.grandtime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.R
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.service.CoreService
import com.benzn.grandtime.ui.theme.FieldSightTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startCore() // 授权与否都启动:通知不可见不影响 FGS 运行
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FieldSightTheme { MainScaffold() } }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCore()
        } else {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startCore() {
        startForegroundService(Intent(this, CoreService::class.java))
    }
}

@Composable
private fun MainScaffold() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val isSubScreen = screen == Screen.KEY_BINDINGS || screen == Screen.DIAGNOSTICS
    BackHandler(enabled = isSubScreen) { screen = Screen.SETTINGS }
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = if (isSubScreen) screen.title else null,
                showBack = isSubScreen,
                onBack = { screen = Screen.SETTINGS },
                serviceRunning = running,
            )
        },
        bottomBar = {
            if (!isSubScreen) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val itemColors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondary,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NavigationBarItem(
                        selected = screen == Screen.HOME,
                        onClick = { screen = Screen.HOME },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Home") },
                        colors = itemColors,
                    )
                    NavigationBarItem(
                        selected = screen == Screen.FILES,
                        onClick = { screen = Screen.FILES },
                        icon = { Icon(painterResource(R.drawable.ic_nav_files), contentDescription = null) },
                        label = { Text("Files") },
                        colors = itemColors,
                    )
                    NavigationBarItem(
                        selected = screen == Screen.SETTINGS,
                        onClick = { screen = Screen.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        colors = itemColors,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen()
                Screen.FILES -> FilesScreen()
                Screen.SETTINGS -> SettingsScreen(onOpen = { screen = it })
                Screen.KEY_BINDINGS -> KeyBindingsScreen()
                Screen.DIAGNOSTICS -> DiagnosticsScreen()
            }
        }
    }
}
```

- [ ] **Step 2: 删除旧屏**

```bash
git rm app/src/main/java/com/benzn/grandtime/ui/StatusScreen.kt app/src/main/java/com/benzn/grandtime/ui/ProbeScreen.kt app/src/main/java/com/benzn/grandtime/ui/KeymapScreen.kt
```

- [ ] **Step 3: 构建 + 全量测试 + 安装**

```bash
./gradlew assembleDebug && ./gradlew test
export ANDROID_SERIAL=emulator-5554 && ./gradlew installDebug
```

Expected: 全绿;模拟器打开 = 新导航壳。

- [ ] **Step 4: Commit**

```bash
git add -A app/src && git commit -m "feat: product navigation shell (Home/Files/Settings + sub-screens), drop debug screens"
```

---

### Task 10: 端到端走查(模拟器 + 真机)+ 标记

**Files:** 无新文件(发现缺陷回对应 Task 修,fix commit)

- [ ] **Step 1: 模拟器五页走查**(`ADB="C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe"`,全部 `-s emulator-5554`)

对照 spec §3 简图逐一核对(uiautomator dump + 截图辅助):
1. 顶栏:海军蓝、FieldSight 双色 wordmark、右侧绿点;状态栏同色
2. Home:DEVICE 卡(绿点 + "Standing by" + "Signed in as Test account")
3. Files:四个过滤 chips(选中黄底)、空态卡(图标+两行文案)
4. Settings:四组卡;改 Video quality → 720P,杀进程重启 App 后仍是 720P
5. Keys 二级页:顶栏变返回箭头+标题、底栏隐藏;改一行 → 黄高亮+edited;Reset 回落
6. Diagnostics 二级页:按屏幕键短/长按 → 日志行出现(mono 字体);`adb shell am broadcast -a lolaage.video1.down` + sleep 1.5 + `.up` → LONG 行
7. 系统返回键:二级页回 Settings,一级页退出

- [ ] **Step 2: 开机回归**

```bash
"$ADB" -s emulator-5554 reboot && "$ADB" -s emulator-5554 wait-for-device && sleep 40
"$ADB" -s emulator-5554 shell dumpsys activity services com.benzn.grandtime | grep -i foreground
```

Expected: `isForeground=true`;通知 = FieldSight / Standing by(英文)。

- [ ] **Step 3: 真机安装核验**(仅此步用真机)

```bash
export ANDROID_SERIAL=F2S202503103054 && ./gradlew installDebug
"$ADB" -s F2S202503103054 shell am start -n com.benzn.grandtime/.ui.MainActivity
```

人工核对:启动图标(海军蓝底 FS logo)、应用名 FieldSight、顶栏、48dp 触控目标;
物理键按一下 → Diagnostics 页日志出现(样式为新版)。

- [ ] **Step 4: 全量测试收尾 + 标记**

```bash
./gradlew test
git add -u && git commit -m "test: e2e walkthrough fixes"   # 仅当有改动
git tag ui-reskin-accepted
```

---

## 验证总览

| 层次 | 手段 | 覆盖 |
|---|---|---|
| JVM 单测 | `./gradlew test` | SettingsStore(默认/读写/非法回落/选项校验)+ 既有 20 个零回归 |
| 模拟器 | 五页走查 + adb 广播 + reboot | 主题/导航/设置持久化/改键高亮/诊断管线/开机 |
| 真机 F2SP | Task 10 Step 3 | 图标/品牌/触控目标/物理键新样式 |

## 风险与缓解

- **两台设备同时在线** → 全程 ANDROID_SERIAL/-s 指定,Global Constraints 已定
- **BitmapDrawable 拉伸畸变** → Task 4 Step 1 先方形化 PNG,inset 均匀
- **Dropbox 文件锁** → 重跑一次;持续才 BLOCKED
- **rememberSaveable 枚举**(Screen/MediaFilter)→ 枚举可序列化,自动支持;如遇 Saver 报错,fallback 存 name 字符串(实施者可就地修,记报告)
