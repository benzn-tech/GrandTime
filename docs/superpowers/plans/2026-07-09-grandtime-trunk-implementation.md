# GrandTime 子项目1「主干」Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 F2SP(Android 13)上跑通「开机自启 → 前台 Service → lolaage.* 物理键监听(可配置映射)→ 桩动作 + 按键探针」主干。

**Architecture:** 单 Gradle 模块 `:app`,按包分层(hardware/keymap/auth/service/boot/ui/core)。ROM 广播经 PressTypeDetector(1 秒短/长按状态机)变成 KeyPress 流,KeyActionDispatcher 查「DataStore 覆盖 → 代码默认表」派发到桩处理器;Service 与 Compose UI 通过 AppState 单例(StateFlow)共享状态。

**Tech Stack:** Kotlin 2.1.0 / AGP 8.7.3 / Gradle 8.11.1、Jetpack Compose(BOM 2024.12.01, Material3)、DataStore Preferences 1.1.1、kotlinx-coroutines 1.9.0、JUnit4 + kotlinx-coroutines-test。

Spec(唯一事实来源):`docs/superpowers/specs/2026-07-09-grandtime-trunk-design.md`

## Global Constraints

- 仓库根 = Android 工程根:`C:/Users/camil/Dropbox/GrandTime`
- 包名/applicationId:`com.benzn.grandtime`;App 显示名「GrandTime」
- `minSdk = 33`、`targetSdk = 33`、`compileSdk = 35`、JVM target 17
- **不注册 `lolaage.ptt.*`**(去对讲化)
- 动态注册广播必须用 `Context.RECEIVER_EXPORTED`(否则收不到 ROM/adb 广播)
- 短/长按判定:down 起 1000ms 定时器;<1s 收到 up = SHORT;定时器到点立即发 LONG,其后 up 忽略
- UI 文案用中文(键名/动作名见 `ui/Labels.kt`)
- 所有 gradle 命令前缀(Bash):`export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`
- adb 完整路径:`C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe`(下文简写 `$ADB`);模拟器:`C:/Users/camil/AppData/Local/Android/Sdk/emulator/emulator.exe -avd Pixel_6`
- Windows + `core.autocrlf=true`:.gitattributes 必须先于代码落库(Task 1)
- Dropbox 会同步仓库目录:构建报文件锁错误时先暂停 Dropbox 同步;`build/`、`.gradle/` 已被 .gitignore 排除
- 每个 Task 结束必须 commit(带 Claude co-author footer)

---

### Task 0: 环境体检(M0,无提交)

**Files:** 无(只读验证)

- [ ] **Step 1: 验证 JBR / adb / AVD**

```bash
"C:/Program Files/Android/Android Studio/jbr/bin/java.exe" -version
"C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe" version
"C:/Users/camil/AppData/Local/Android/Sdk/emulator/emulator.exe" -list-avds
```

Expected: java 17 或 21;adb 版本号;AVD 列表含 `Pixel_6`。

- [ ] **Step 2: 启动模拟器(后台)并确认在线**

```bash
"C:/Users/camil/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Pixel_6 &
"C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe" wait-for-device
"C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe" devices
```

Expected: `emulator-5554  device`。
若后续构建报 SDK license 错:**【用户手动步骤】**在 Android Studio 里打开 SDK Manager 接受 license(或运行 `sdkmanager --licenses` 逐条 y)。

---

### Task 1: 仓库重整 + 行尾/忽略规则

**Files:**
- Create: `.gitignore`、`.gitattributes`
- Move: `SMART_PTT-F2SP-v02-10.1.7.124.apk`、`SP_Flash_Tool_exe_Windows_v5.2136.00.000/`、`fw upgrade guidance.xlsx` → `reference/`

- [ ] **Step 1: 确认追踪状态**

```bash
cd "C:/Users/camil/Dropbox/GrandTime" && git ls-files
```

Expected: 列出 APK、xlsx、spec/plan 文档;SP_Flash_Tool 的文件可能部分未追踪——已追踪用 `git mv`,未追踪用 `mv` 后统一 add。

- [ ] **Step 2: 移动文件**

```bash
cd "C:/Users/camil/Dropbox/GrandTime" && mkdir -p reference
git mv "SMART_PTT-F2SP-v02-10.1.7.124.apk" reference/ 2>/dev/null || mv "SMART_PTT-F2SP-v02-10.1.7.124.apk" reference/
git mv "fw upgrade guidance.xlsx" reference/ 2>/dev/null || mv "fw upgrade guidance.xlsx" reference/
git mv "SP_Flash_Tool_exe_Windows_v5.2136.00.000" reference/ 2>/dev/null || mv "SP_Flash_Tool_exe_Windows_v5.2136.00.000" reference/
```

- [ ] **Step 3: 写 `.gitattributes`(必须先于一切代码)**

```
* text=auto
gradlew text eol=lf
*.sh text eol=lf
*.bat text eol=crlf
*.apk -text
*.jar -text
*.xlsx -text
*.png -text
*.zip -text
*.exe -text
*.dll -text
```

- [ ] **Step 4: 写 `.gitignore`**

```
.gradle/
build/
app/build/
local.properties
.idea/
*.iml
.kotlin/
captures/
.externalNativeBuild/
.cxx/
```

- [ ] **Step 5: Commit**

```bash
git add -A reference .gitattributes .gitignore && git status --short && git commit -m "chore: move vendor artifacts to reference/, add gitignore/gitattributes"
```

(此仓库无既有源码,`git add -A reference` 仅限 reference 目录,安全。)

---

### Task 2: Gradle 脚手架 + wrapper + 占位 App(最早冒烟门)

**Files:**
- Create: `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`、`app/build.gradle.kts`、`app/proguard-rules.pro`、`app/src/main/AndroidManifest.xml`、`app/src/main/java/com/benzn/grandtime/GrandTimeApp.kt`、`app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values/themes.xml`、`app/src/main/res/drawable/ic_launcher_foreground.xml`、`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`、`gradlew`、`gradlew.bat`、`gradle/wrapper/*`

**Interfaces:**
- Produces: 可构建可安装的空 App;后续所有 Task 在此工程内加文件。

- [ ] **Step 1: `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
composeBom = "2024.12.01"
datastore = "1.1.1"
coroutines = "1.9.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
datastore-preferences-core = { group = "androidx.datastore", name = "datastore-preferences-core", version.ref = "datastore" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GrandTime"
include(":app")
```

- [ ] **Step 3: 根 `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

- [ ] **Step 4: `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

(不写 `org.gradle.java.home`——机器相关路径不入库,统一用 JAVA_HOME 环境变量。)

- [ ] **Step 5: `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.benzn.grandtime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.benzn.grandtime"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // 保证 JVM 单测能直接用 PreferenceDataStoreFactory(KMP jvm 变体)
    testImplementation(libs.datastore.preferences.core)
}
```

`app/proguard-rules.pro` 建空文件即可。

- [ ] **Step 6: `app/src/main/AndroidManifest.xml`(占位版,后续 Task 增补)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".GrandTimeApp"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.GrandTime">
        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 7: 占位 Kotlin 与资源**

`app/src/main/java/com/benzn/grandtime/GrandTimeApp.kt`:

```kotlin
package com.benzn.grandtime

import android.app.Application

class GrandTimeApp : Application()
```

`app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`(占位,Task 11 重写):

```kotlin
package com.benzn.grandtime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Text("GrandTime") }
        }
    }
}
```

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">GrandTime</string>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.GrandTime" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#1565C0"
        android:pathData="M54,24 A30,30 0 1,1 53.9,24 Z" />
    <path android:fillColor="#FFFFFF"
        android:pathData="M50,38 h8 v20 h14 v8 h-22 Z" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/white" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 8: 生成 Gradle wrapper**

```bash
cd "C:/Users/camil/AppData/Local/Temp/claude/C--Users-camil-Dropbox/5b532859-da77-4835-9863-74d0929c7327/scratchpad"
curl -L -o gradle-8.11.1-bin.zip https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
unzip -q gradle-8.11.1-bin.zip
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd "C:/Users/camil/Dropbox/GrandTime"
"C:/Users/camil/AppData/Local/Temp/claude/C--Users-camil-Dropbox/5b532859-da77-4835-9863-74d0929c7327/scratchpad/gradle-8.11.1/bin/gradle" wrapper --gradle-version 8.11.1
```

Expected: 生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`。
兜底:下载受阻时,用 Android Studio 新建任意临时工程,把它的 wrapper 四件套拷过来并把 `gradle-wrapper.properties` 的 distributionUrl 改为 8.11.1。

- [ ] **Step 9: 构建 + 安装冒烟**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd "C:/Users/camil/Dropbox/GrandTime" && ./gradlew assembleDebug
./gradlew installDebug && ./gradlew test
```

Expected: `BUILD SUCCESSFUL` × 3;模拟器 app 列表出现 GrandTime,打开显示 "GrandTime" 文本。首次构建要下载依赖,耗时数分钟属正常。

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle app gradlew gradlew.bat
git commit -m "feat: scaffold Android project (Kotlin 2.1, AGP 8.7, Compose, minSdk 33)"
```

---

### Task 3: 键/动作类型 + 默认映射(TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/hardware/KeyEvents.kt`、`app/src/main/java/com/benzn/grandtime/keymap/KeyAction.kt`、`app/src/main/java/com/benzn/grandtime/keymap/KeyMapping.kt`
- Test: `app/src/test/java/com/benzn/grandtime/keymap/KeyMappingTest.kt`

**Interfaces:**
- Produces: `HardKey{VIDEO,PHOTO,AUDIO,SOS}`、`PressType{SHORT,LONG}`、`RawDirection{DOWN,UP}`、`data class KeyPress(key, pressType)`;`KeyAction` 枚举;`KeyMapping.resolve(press: KeyPress, overrides: Map<String, KeyAction>): KeyAction`;`KeyMapping.overrideKeyOf(key, pressType): String`(形如 `"VIDEO_SHORT"`)。

- [ ] **Step 1: 写失败测试 `KeyMappingTest.kt`**

```kotlin
package com.benzn.grandtime.keymap

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMappingTest {

    @Test
    fun `default table matches reverse-engineered mapping`() {
        assertEquals(KeyAction.START_STOP_VIDEO,
            KeyMapping.resolve(KeyPress(HardKey.VIDEO, PressType.SHORT), emptyMap()))
        assertEquals(KeyAction.TOGGLE_VIDEO_UPLOAD,
            KeyMapping.resolve(KeyPress(HardKey.VIDEO, PressType.LONG), emptyMap()))
        assertEquals(KeyAction.TAKE_PHOTO,
            KeyMapping.resolve(KeyPress(HardKey.PHOTO, PressType.SHORT), emptyMap()))
        assertEquals(KeyAction.TOGGLE_TORCH,
            KeyMapping.resolve(KeyPress(HardKey.PHOTO, PressType.LONG), emptyMap()))
        assertEquals(KeyAction.ADJUST_VOLUME,
            KeyMapping.resolve(KeyPress(HardKey.AUDIO, PressType.SHORT), emptyMap()))
        assertEquals(KeyAction.START_STOP_AUDIO,
            KeyMapping.resolve(KeyPress(HardKey.AUDIO, PressType.LONG), emptyMap()))
        assertEquals(KeyAction.SEND_SOS,
            KeyMapping.resolve(KeyPress(HardKey.SOS, PressType.SHORT), emptyMap()))
        assertEquals(KeyAction.TOGGLE_WARNING_LIGHT,
            KeyMapping.resolve(KeyPress(HardKey.SOS, PressType.LONG), emptyMap()))
    }

    @Test
    fun `override wins over default`() {
        val overrides = mapOf("VIDEO_SHORT" to KeyAction.TAKE_PHOTO)
        assertEquals(KeyAction.TAKE_PHOTO,
            KeyMapping.resolve(KeyPress(HardKey.VIDEO, PressType.SHORT), overrides))
        assertEquals(KeyAction.TOGGLE_VIDEO_UPLOAD,
            KeyMapping.resolve(KeyPress(HardKey.VIDEO, PressType.LONG), overrides))
    }

    @Test
    fun `overrideKeyOf format`() {
        assertEquals("SOS_LONG", KeyMapping.overrideKeyOf(HardKey.SOS, PressType.LONG))
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && cd "C:/Users/camil/Dropbox/GrandTime" && ./gradlew test
```

Expected: FAIL(unresolved reference)。

- [ ] **Step 3: 实现**

`hardware/KeyEvents.kt`:

```kotlin
package com.benzn.grandtime.hardware

enum class HardKey { VIDEO, PHOTO, AUDIO, SOS }

enum class PressType { SHORT, LONG }

enum class RawDirection { DOWN, UP }

data class KeyPress(val key: HardKey, val pressType: PressType)
```

`keymap/KeyAction.kt`:

```kotlin
package com.benzn.grandtime.keymap

enum class KeyAction {
    START_STOP_VIDEO,
    TOGGLE_VIDEO_UPLOAD,
    TAKE_PHOTO,
    TOGGLE_TORCH,
    ADJUST_VOLUME,
    START_STOP_AUDIO,
    SEND_SOS,
    TOGGLE_WARNING_LIGHT,
    NONE,
}
```

`keymap/KeyMapping.kt`:

```kotlin
package com.benzn.grandtime.keymap

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType

object KeyMapping {

    val DEFAULTS: Map<Pair<HardKey, PressType>, KeyAction> = mapOf(
        (HardKey.VIDEO to PressType.SHORT) to KeyAction.START_STOP_VIDEO,
        (HardKey.VIDEO to PressType.LONG) to KeyAction.TOGGLE_VIDEO_UPLOAD,
        (HardKey.PHOTO to PressType.SHORT) to KeyAction.TAKE_PHOTO,
        (HardKey.PHOTO to PressType.LONG) to KeyAction.TOGGLE_TORCH,
        (HardKey.AUDIO to PressType.SHORT) to KeyAction.ADJUST_VOLUME,
        (HardKey.AUDIO to PressType.LONG) to KeyAction.START_STOP_AUDIO,
        (HardKey.SOS to PressType.SHORT) to KeyAction.SEND_SOS,
        (HardKey.SOS to PressType.LONG) to KeyAction.TOGGLE_WARNING_LIGHT,
    )

    fun overrideKeyOf(key: HardKey, pressType: PressType): String =
        "${key.name}_${pressType.name}"

    fun resolve(press: KeyPress, overrides: Map<String, KeyAction>): KeyAction =
        overrides[overrideKeyOf(press.key, press.pressType)]
            ?: DEFAULTS[press.key to press.pressType]
            ?: KeyAction.NONE
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew test
```

Expected: PASS(3 tests)。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/hardware/KeyEvents.kt app/src/main/java/com/benzn/grandtime/keymap app/src/test
git commit -m "feat: key/action types and default key mapping with resolve"
```

---

### Task 4: KeyMapStore(DataStore 覆盖层,TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/keymap/KeyMapStore.kt`
- Test: `app/src/test/java/com/benzn/grandtime/keymap/KeyMapStoreTest.kt`

**Interfaces:**
- Consumes: `KeyMapping.overrideKeyOf`、`KeyAction`、`HardKey`、`PressType`(Task 3)
- Produces: `class KeyMapStore(dataStore: DataStore<Preferences>)`:`overrides: Flow<Map<String, KeyAction>>`、`suspend setOverride(key, pressType, action)`、`suspend resetToDefaults()`;顶层 `val Context.keymapDataStore: DataStore<Preferences>`(name="keymap",Service 与 UI 共用同一实例)。

- [ ] **Step 1: 写失败测试 `KeyMapStoreTest.kt`**

```kotlin
package com.benzn.grandtime.keymap

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.PressType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeyMapStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newStore(): KeyMapStore {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
        ) { File(tmp.root, "keymap.preferences_pb") }
        return KeyMapStore(ds)
    }

    @Test
    fun `set override then read back`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.setOverride(HardKey.VIDEO, PressType.SHORT, KeyAction.TAKE_PHOTO)
        assertEquals(
            mapOf("VIDEO_SHORT" to KeyAction.TAKE_PHOTO),
            store.overrides.first(),
        )
    }

    @Test
    fun `reset clears all overrides`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.setOverride(HardKey.SOS, PressType.LONG, KeyAction.TAKE_PHOTO)
        store.resetToDefaults()
        assertEquals(emptyMap<String, KeyAction>(), store.overrides.first())
    }

    @Test
    fun `unknown stored value is ignored not crash`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.setOverride(HardKey.AUDIO, PressType.SHORT, KeyAction.NONE)
        // 直接读也不抛异常
        assertEquals(mapOf("AUDIO_SHORT" to KeyAction.NONE), store.overrides.first())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew test
```

Expected: FAIL(KeyMapStore 不存在)。

- [ ] **Step 3: 实现 `keymap/KeyMapStore.kt`**

```kotlin
package com.benzn.grandtime.keymap

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.PressType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.keymapDataStore: DataStore<Preferences> by preferencesDataStore(name = "keymap")

class KeyMapStore(private val dataStore: DataStore<Preferences>) {

    val overrides: Flow<Map<String, KeyAction>> = dataStore.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (prefKey, value) ->
            val action = (value as? String)
                ?.let { name -> KeyAction.entries.firstOrNull { it.name == name } }
            action?.let { prefKey.name to it }
        }.toMap()
    }

    suspend fun setOverride(key: HardKey, pressType: PressType, action: KeyAction) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(KeyMapping.overrideKeyOf(key, pressType))] = action.name
        }
    }

    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew test
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: DataStore-backed keymap override store"
```

---

### Task 5: KeyActionDispatcher(TDD)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/keymap/KeyActionDispatcher.kt`
- Test: `app/src/test/java/com/benzn/grandtime/keymap/KeyActionDispatcherTest.kt`

**Interfaces:**
- Consumes: `KeyMapping.resolve`(Task 3)
- Produces: `class KeyActionDispatcher(currentOverrides: () -> Map<String, KeyAction>, handler: (KeyAction, KeyPress) -> Unit)`,方法 `dispatch(press: KeyPress)`;`NONE` 不调 handler。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.benzn.grandtime.keymap

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyActionDispatcherTest {

    @Test
    fun `dispatch resolves via defaults and calls handler`() {
        val calls = mutableListOf<Pair<KeyAction, KeyPress>>()
        val dispatcher = KeyActionDispatcher({ emptyMap() }) { a, p -> calls.add(a to p) }
        val press = KeyPress(HardKey.PHOTO, PressType.SHORT)
        dispatcher.dispatch(press)
        assertEquals(listOf(KeyAction.TAKE_PHOTO to press), calls)
    }

    @Test
    fun `dispatch respects live overrides`() {
        var overrides = mapOf("PHOTO_SHORT" to KeyAction.SEND_SOS)
        val calls = mutableListOf<KeyAction>()
        val dispatcher = KeyActionDispatcher({ overrides }) { a, _ -> calls.add(a) }
        dispatcher.dispatch(KeyPress(HardKey.PHOTO, PressType.SHORT))
        overrides = emptyMap()
        dispatcher.dispatch(KeyPress(HardKey.PHOTO, PressType.SHORT))
        assertEquals(listOf(KeyAction.SEND_SOS, KeyAction.TAKE_PHOTO), calls)
    }

    @Test
    fun `NONE action does not call handler`() {
        val calls = mutableListOf<KeyAction>()
        val dispatcher = KeyActionDispatcher({ mapOf("SOS_SHORT" to KeyAction.NONE) }) { a, _ -> calls.add(a) }
        dispatcher.dispatch(KeyPress(HardKey.SOS, PressType.SHORT))
        assertTrue(calls.isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现 `keymap/KeyActionDispatcher.kt`**

```kotlin
package com.benzn.grandtime.keymap

import com.benzn.grandtime.hardware.KeyPress

class KeyActionDispatcher(
    private val currentOverrides: () -> Map<String, KeyAction>,
    private val handler: (KeyAction, KeyPress) -> Unit,
) {
    fun dispatch(press: KeyPress) {
        val action = KeyMapping.resolve(press, currentOverrides())
        if (action != KeyAction.NONE) handler(action, press)
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: key action dispatcher with live override lookup"
```

---

### Task 6: PressTypeDetector 短/长按状态机(TDD,核心)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/hardware/PressTypeDetector.kt`
- Test: `app/src/test/java/com/benzn/grandtime/hardware/PressTypeDetectorTest.kt`

**Interfaces:**
- Consumes: `HardKey`、`PressType`、`RawDirection`、`KeyPress`(Task 3)
- Produces: `class PressTypeDetector(scope: CoroutineScope, longPressMillis: Long = 1000L)`:`val keyPresses: SharedFlow<KeyPress>`、`fun onRawEvent(key: HardKey, direction: RawDirection)`。**线程约束:所有 onRawEvent 调用必须来自同一调度器(生产 = 主线程),状态机内部不加锁。**

- [ ] **Step 1: 写失败测试**

```kotlin
package com.benzn.grandtime.hardware

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PressTypeDetectorTest {

    private fun kotlinx.coroutines.test.TestScope.collectInto(
        detector: PressTypeDetector,
    ): MutableList<KeyPress> {
        val events = mutableListOf<KeyPress>()
        backgroundScope.launch { detector.keyPresses.collect { events.add(it) } }
        runCurrent()
        return events
    }

    @Test
    fun `up within 1s emits SHORT`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.VIDEO, RawDirection.DOWN)
        advanceTimeBy(900)
        detector.onRawEvent(HardKey.VIDEO, RawDirection.UP)
        runCurrent()
        assertEquals(listOf(KeyPress(HardKey.VIDEO, PressType.SHORT)), events)
    }

    @Test
    fun `timer firing at 1s emits LONG immediately, later up ignored`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.SOS, RawDirection.DOWN)
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(listOf(KeyPress(HardKey.SOS, PressType.LONG)), events)
        detector.onRawEvent(HardKey.SOS, RawDirection.UP)
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test
    fun `up without down emits nothing`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.AUDIO, RawDirection.UP)
        runCurrent()
        assertEquals(0, events.size)
    }

    @Test
    fun `repeated down resets the timer`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.PHOTO, RawDirection.DOWN)
        advanceTimeBy(800)
        detector.onRawEvent(HardKey.PHOTO, RawDirection.DOWN) // 重复 down,重置
        advanceTimeBy(800)
        runCurrent()
        assertEquals(0, events.size) // 两段都没到 1s,无 LONG
        detector.onRawEvent(HardKey.PHOTO, RawDirection.UP)
        runCurrent()
        assertEquals(listOf(KeyPress(HardKey.PHOTO, PressType.SHORT)), events)
    }

    @Test
    fun `two keys interleaved are independent`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.VIDEO, RawDirection.DOWN)
        advanceTimeBy(500)
        detector.onRawEvent(HardKey.SOS, RawDirection.DOWN)
        advanceTimeBy(600) // VIDEO 到 1.1s → LONG;SOS 才 0.6s
        runCurrent()
        detector.onRawEvent(HardKey.SOS, RawDirection.UP) // SOS SHORT
        detector.onRawEvent(HardKey.VIDEO, RawDirection.UP) // 已 LONG,忽略
        runCurrent()
        assertEquals(
            listOf(
                KeyPress(HardKey.VIDEO, PressType.LONG),
                KeyPress(HardKey.SOS, PressType.SHORT),
            ),
            events,
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现 `hardware/PressTypeDetector.kt`**

```kotlin
package com.benzn.grandtime.hardware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * down 起 [longPressMillis] 定时器:期间收到 up = SHORT;
 * 定时器到点立即发 LONG(不等松手),其后的 up 只清状态。
 * 所有调用必须来自同一调度器(生产 = 主线程),内部不加锁。
 */
class PressTypeDetector(
    private val scope: CoroutineScope,
    private val longPressMillis: Long = 1000L,
) {
    private val _keyPresses = MutableSharedFlow<KeyPress>(extraBufferCapacity = 16)
    val keyPresses: SharedFlow<KeyPress> = _keyPresses

    private class Pending(val timer: Job) {
        var longFired = false
    }

    private val pending = mutableMapOf<HardKey, Pending>()

    fun onRawEvent(key: HardKey, direction: RawDirection) {
        when (direction) {
            RawDirection.DOWN -> onDown(key)
            RawDirection.UP -> onUp(key)
        }
    }

    private fun onDown(key: HardKey) {
        pending.remove(key)?.timer?.cancel()
        lateinit var entry: Pending
        entry = Pending(scope.launch {
            delay(longPressMillis)
            if (pending[key] === entry) {
                entry.longFired = true
                _keyPresses.tryEmit(KeyPress(key, PressType.LONG))
            }
        })
        pending[key] = entry
    }

    private fun onUp(key: HardKey) {
        val entry = pending.remove(key) ?: return
        entry.timer.cancel()
        if (!entry.longFired) {
            _keyPresses.tryEmit(KeyPress(key, PressType.SHORT))
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS(5 tests)。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: 1s short/long press state machine (PressTypeDetector)"
```

---

### Task 7: 广播解析 + KeyEventSource 三实现

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/hardware/KeyEventSource.kt`、`.../hardware/F2spKeyEventSource.kt`、`.../hardware/OnScreenKeyEventSource.kt`、`.../hardware/DeviceProfile.kt`
- Test: `app/src/test/java/com/benzn/grandtime/hardware/F2spActionParserTest.kt`、`app/src/test/java/com/benzn/grandtime/hardware/DeviceProfileTest.kt`

**Interfaces:**
- Consumes: `PressTypeDetector`(Task 6)
- Produces:
  - `data class RawBroadcast(action: String, timestampMillis: Long)`
  - `interface KeyEventSource { val keyPresses: SharedFlow<KeyPress>; val rawEvents: SharedFlow<RawBroadcast>; fun start(); fun stop() }`
  - `F2spKeyEventSource(context, scope)`:动态注册 `RECEIVER_EXPORTED`;companion `parse(action): Pair<HardKey, RawDirection>?`(纯函数)与 action 常量表
  - `OnScreenKeyEventSource(scope)`:额外 `fun onScreenKey(key, direction)`
  - `DeviceProfile.isF2spFamily(model: String): Boolean`

- [ ] **Step 1: 写失败测试**

`F2spActionParserTest.kt`:

```kotlin
package com.benzn.grandtime.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class F2spActionParserTest {

    @Test
    fun `known key actions parse to key and direction`() {
        assertEquals(HardKey.VIDEO to RawDirection.DOWN, F2spKeyEventSource.parse("lolaage.video1.down"))
        assertEquals(HardKey.VIDEO to RawDirection.UP, F2spKeyEventSource.parse("lolaage.video1.up"))
        assertEquals(HardKey.PHOTO to RawDirection.DOWN, F2spKeyEventSource.parse("lolaage.take.picture.down"))
        assertEquals(HardKey.AUDIO to RawDirection.UP, F2spKeyEventSource.parse("lolaage.audio.up"))
        assertEquals(HardKey.SOS to RawDirection.DOWN, F2spKeyEventSource.parse("lolaage.sos.down"))
    }

    @Test
    fun `ptt and probe-only and unknown actions parse to null`() {
        assertNull(F2spKeyEventSource.parse("lolaage.ptt.down"))
        assertNull(F2spKeyEventSource.parse("lolaage.light"))
        assertNull(F2spKeyEventSource.parse("whatever.else"))
    }
}
```

`DeviceProfileTest.kt`:

```kotlin
package com.benzn.grandtime.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun `f2sp family models detected`() {
        listOf("SDJW-F2SP", "F2S-A", "SDJW-F2S", "XB-15").forEach {
            assertTrue(it, DeviceProfile.isF2spFamily(it))
        }
        assertFalse(DeviceProfile.isF2spFamily("Pixel 6"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现**

`hardware/KeyEventSource.kt`:

```kotlin
package com.benzn.grandtime.hardware

import kotlinx.coroutines.flow.SharedFlow

data class RawBroadcast(val action: String, val timestampMillis: Long)

interface KeyEventSource {
    val keyPresses: SharedFlow<KeyPress>
    val rawEvents: SharedFlow<RawBroadcast>
    fun start()
    fun stop()
}
```

`hardware/F2spKeyEventSource.kt`:

```kotlin
package com.benzn.grandtime.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * F2SP ROM 私有广播源。合规说明:lolaage.* 是 ROM 对外广播接口,
 * 此处为干净重写,不含任何 com.corget 反编译代码。
 * 不注册 lolaage.ptt.*(去对讲化)。
 */
class F2spKeyEventSource(
    private val context: Context,
    scope: CoroutineScope,
) : KeyEventSource {

    private val detector = PressTypeDetector(scope)
    override val keyPresses: SharedFlow<KeyPress> = detector.keyPresses

    private val _rawEvents = MutableSharedFlow<RawBroadcast>(extraBufferCapacity = 64)
    override val rawEvents: SharedFlow<RawBroadcast> = _rawEvents

    companion object {
        /** action 前缀(.down/.up 之前的部分)→ 键。真机若有出入只改这张表。 */
        val KEY_ACTION_PREFIXES: Map<String, HardKey> = mapOf(
            "lolaage.video1" to HardKey.VIDEO,
            "lolaage.take.picture" to HardKey.PHOTO,
            "lolaage.audio" to HardKey.AUDIO,
            "lolaage.sos" to HardKey.SOS,
        )

        /** 只进探针、不进状态机的广播(逆向记录名 + 可能的带前缀变体都注册,收到多少算多少)。 */
        val PROBE_ONLY_ACTIONS: List<String> = listOf(
            "lolaage.light",
            "lolaage.switch.group", "switch.group",
            "lolaage.volume", "volume",
            "lolaage.ir.led", "ir.led",
            "lolaage.ircut.switch", "ircut.switch",
            "lolaage.rayled.set", "rayled.set",
        )

        fun parse(action: String): Pair<HardKey, RawDirection>? {
            val (prefix, direction) = when {
                action.endsWith(".down") -> action.removeSuffix(".down") to RawDirection.DOWN
                action.endsWith(".up") -> action.removeSuffix(".up") to RawDirection.UP
                else -> return null
            }
            val key = KEY_ACTION_PREFIXES[prefix] ?: return null
            return key to direction
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            _rawEvents.tryEmit(RawBroadcast(action, System.currentTimeMillis()))
            parse(action)?.let { (key, direction) -> detector.onRawEvent(key, direction) }
        }
    }

    override fun start() {
        val filter = IntentFilter().apply {
            KEY_ACTION_PREFIXES.keys.forEach {
                addAction("$it.down")
                addAction("$it.up")
            }
            PROBE_ONLY_ACTIONS.forEach {
                addAction(it)
                addAction("$it.down")
                addAction("$it.up")
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
```

`hardware/OnScreenKeyEventSource.kt`:

```kotlin
package com.benzn.grandtime.hardware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 屏幕按钮源:普通手机/模拟器退化方案,复用同一套短/长按判定。 */
class OnScreenKeyEventSource(scope: CoroutineScope) : KeyEventSource {

    private val detector = PressTypeDetector(scope)
    override val keyPresses: SharedFlow<KeyPress> = detector.keyPresses

    private val _rawEvents = MutableSharedFlow<RawBroadcast>(extraBufferCapacity = 64)
    override val rawEvents: SharedFlow<RawBroadcast> = _rawEvents

    override fun start() {}
    override fun stop() {}

    fun onScreenKey(key: HardKey, direction: RawDirection) {
        _rawEvents.tryEmit(
            RawBroadcast("screen.${key.name.lowercase()}.${direction.name.lowercase()}", System.currentTimeMillis())
        )
        detector.onRawEvent(key, direction)
    }
}
```

`hardware/DeviceProfile.kt`:

```kotlin
package com.benzn.grandtime.hardware

import android.os.Build

object DeviceProfile {
    private val F2SP_MODELS = setOf("SDJW-F2SP", "F2S-A", "SDJW-F2S", "XB-15")

    fun isF2spFamily(model: String = Build.MODEL): Boolean = model in F2SP_MODELS
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: lolaage broadcast source, on-screen source, device profile"
```

---

### Task 8: ProbeLog 轮转(TDD)+ AppState + Auth 桩

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/util/ProbeLog.kt`、`app/src/main/java/com/benzn/grandtime/core/AppState.kt`、`app/src/main/java/com/benzn/grandtime/auth/AuthManager.kt`、`app/src/main/java/com/benzn/grandtime/auth/StubAuthManager.kt`
- Test: `app/src/test/java/com/benzn/grandtime/util/ProbeLogTest.kt`

**Interfaces:**
- Produces:
  - `class ProbeLog(dir: File, maxBytes: Long = 1_000_000)`:`fun append(line: String)`;超过 maxBytes 时轮转为 `probe.1.log`(只保留 1 个旧文件,共 2 个)
  - `object AppState`:`serviceRunning: MutableStateFlow<Boolean>`、`loginState: MutableStateFlow<LoginState>`、`overrides: MutableStateFlow<Map<String, KeyAction>>`、`probeEntries: MutableStateFlow<List<ProbeEntry>>`(上限 200,新的在前)、`lastAction: MutableStateFlow<String?>`、`screenKeyEvents: MutableSharedFlow<Pair<HardKey, RawDirection>>`、`fun addProbe(entry: ProbeEntry)`
  - `data class ProbeEntry(timestampMillis: Long, text: String)`
  - `sealed interface LoginState`(`LoggedOut` / `LoggedIn(displayName)`)
  - `interface AuthManager { val loginState: StateFlow<LoginState>; suspend fun silentLogin(): Boolean }`;`StubAuthManager` 固定登录成功为「测试账号」

- [ ] **Step 1: 写失败测试 `ProbeLogTest.kt`**

```kotlin
package com.benzn.grandtime.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProbeLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `append writes lines to active file`() {
        val log = ProbeLog(tmp.root)
        log.append("hello")
        log.append("world")
        assertEquals("hello\nworld\n", File(tmp.root, "probe.log").readText())
    }

    @Test
    fun `rotates when active file exceeds max`() {
        val log = ProbeLog(tmp.root, maxBytes = 10)
        log.append("0123456789ABC") // 超限
        log.append("next")           // 触发轮转后写入新文件
        assertTrue(File(tmp.root, "probe.1.log").exists())
        assertEquals("next\n", File(tmp.root, "probe.log").readText())
    }

    @Test
    fun `old rotated file is replaced not accumulated`() {
        val log = ProbeLog(tmp.root, maxBytes = 5)
        log.append("aaaaaaaa")
        log.append("bbbbbbbb")
        log.append("c")
        assertFalse(File(tmp.root, "probe.2.log").exists())
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test` → FAIL。

- [ ] **Step 3: 实现**

`util/ProbeLog.kt`:

```kotlin
package com.benzn.grandtime.util

import java.io.File

/** 探针文本日志:单文件超过 maxBytes 时轮转为 probe.1.log,共保留 2 个文件。 */
class ProbeLog(private val dir: File, private val maxBytes: Long = 1_000_000L) {

    private val active: File get() = File(dir, "probe.log")

    @Synchronized
    fun append(line: String) {
        dir.mkdirs()
        if (active.exists() && active.length() > maxBytes) rotate()
        active.appendText(line + "\n")
    }

    private fun rotate() {
        val old = File(dir, "probe.1.log")
        old.delete()
        active.renameTo(old)
    }
}
```

`core/AppState.kt`:

```kotlin
package com.benzn.grandtime.core

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.RawDirection
import com.benzn.grandtime.keymap.KeyAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class ProbeEntry(val timestampMillis: Long, val text: String)

sealed interface LoginState {
    data object LoggedOut : LoginState
    data class LoggedIn(val displayName: String) : LoginState
}

/** Service 写、Compose 读的全局状态仓。 */
object AppState {
    const val PROBE_LIMIT = 200

    val serviceRunning = MutableStateFlow(false)
    val loginState = MutableStateFlow<LoginState>(LoginState.LoggedOut)
    val overrides = MutableStateFlow<Map<String, KeyAction>>(emptyMap())
    val probeEntries = MutableStateFlow<List<ProbeEntry>>(emptyList())
    val lastAction = MutableStateFlow<String?>(null)

    /** UI 屏幕按键 → Service(down/up 原始事件)。 */
    val screenKeyEvents = MutableSharedFlow<Pair<HardKey, RawDirection>>(extraBufferCapacity = 16)

    fun addProbe(entry: ProbeEntry) {
        probeEntries.value = (listOf(entry) + probeEntries.value).take(PROBE_LIMIT)
    }
}
```

`auth/AuthManager.kt`:

```kotlin
package com.benzn.grandtime.auth

import com.benzn.grandtime.core.LoginState
import kotlinx.coroutines.flow.StateFlow

interface AuthManager {
    val loginState: StateFlow<LoginState>
    suspend fun silentLogin(): Boolean
}
```

`auth/StubAuthManager.kt`:

```kotlin
package com.benzn.grandtime.auth

import com.benzn.grandtime.core.LoginState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 子项目1 打桩:恒登录成功。子项目2 换 Cognito 实现,接口不变。 */
class StubAuthManager : AuthManager {
    private val _loginState = MutableStateFlow<LoginState>(LoginState.LoggedOut)
    override val loginState: StateFlow<LoginState> = _loginState

    override suspend fun silentLogin(): Boolean {
        _loginState.value = LoginState.LoggedIn("测试账号")
        return true
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test` → PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: probe log with rotation, AppState singleton, stub auth"
```

---

### Task 9: CoreService(前台化 + 键管线 + 通知)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/service/CoreService.kt`
- Modify: `app/src/main/AndroidManifest.xml`(加权限 + service 声明)
- Create: `app/src/main/res/drawable/ic_stat_grandtime.xml`

**Interfaces:**
- Consumes: `F2spKeyEventSource` / `OnScreenKeyEventSource`(Task 7)、`KeyActionDispatcher`(Task 5)、`KeyMapStore` + `keymapDataStore`(Task 4)、`AppState` / `ProbeEntry` / `StubAuthManager` / `ProbeLog`(Task 8)
- Produces: `CoreService`(LifecycleService,START_STICKY);启动即 `startForeground`;桩动作 = 更新通知 + `AppState.lastAction` + 探针。

- [ ] **Step 1: 通知小图标 `res/drawable/ic_stat_grandtime.xml`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFF"
        android:pathData="M12,2 A10,10 0 1,1 11.9,2 Z M11,7 h2 v6 h4 v2 h-6 Z" />
</vector>
```

- [ ] **Step 2: 实现 `service/CoreService.kt`**

```kotlin
package com.benzn.grandtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.benzn.grandtime.R
import com.benzn.grandtime.auth.AuthManager
import com.benzn.grandtime.auth.StubAuthManager
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.ProbeEntry
import com.benzn.grandtime.hardware.F2spKeyEventSource
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.OnScreenKeyEventSource
import com.benzn.grandtime.keymap.KeyAction
import com.benzn.grandtime.keymap.KeyActionDispatcher
import com.benzn.grandtime.keymap.KeyMapStore
import com.benzn.grandtime.keymap.keymapDataStore
import com.benzn.grandtime.ui.actionLabel
import com.benzn.grandtime.util.ProbeLog
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CoreService : LifecycleService() {

    companion object {
        private const val TAG = "GrandTime"
        private const val CHANNEL_ID = "core"
        private const val NOTIFICATION_ID = 1
    }

    private var pipelineStarted = false
    private var f2spSource: F2spKeyEventSource? = null
    private lateinit var probeLog: ProbeLog
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须在 startForegroundService 后尽快前台化,先于任何 suspend 工作
        startForeground(NOTIFICATION_ID, buildNotification("待命"))
        probeLog = ProbeLog(File(filesDir, "probe"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!pipelineStarted) {
            pipelineStarted = true
            startPipeline()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        val auth: AuthManager = StubAuthManager()
        val keyMapStore = KeyMapStore(applicationContext.keymapDataStore)

        val f2sp = F2spKeyEventSource(this, lifecycleScope).also { it.start() }
        f2spSource = f2sp
        val onScreen = OnScreenKeyEventSource(lifecycleScope)

        lifecycleScope.launch {
            AppState.screenKeyEvents.collect { (key, direction) -> onScreen.onScreenKey(key, direction) }
        }
        lifecycleScope.launch {
            auth.silentLogin()
            auth.loginState.collect { AppState.loginState.value = it }
        }
        lifecycleScope.launch {
            keyMapStore.overrides.collect { AppState.overrides.value = it }
        }

        val dispatcher = KeyActionDispatcher({ AppState.overrides.value }, ::handleAction)
        lifecycleScope.launch {
            merge(f2sp.keyPresses, onScreen.keyPresses).collect { press ->
                probe("${press.key.name} ${press.pressType.name}")
                dispatcher.dispatch(press)
            }
        }
        lifecycleScope.launch {
            merge(f2sp.rawEvents, onScreen.rawEvents).collect { probe(it.action) }
        }

        AppState.serviceRunning.value = true
        probe("service started")
    }

    private fun handleAction(action: KeyAction, press: KeyPress) {
        val text = "[桩] ${actionLabel(action)}"
        AppState.lastAction.value = text
        probe("${press.key.name} ${press.pressType.name} → ${action.name}")
        notifyStatus(text)
    }

    private fun probe(text: String) {
        val now = System.currentTimeMillis()
        AppState.addProbe(ProbeEntry(now, text))
        probeLog.append("${timeFormat.format(Date(now))} $text")
        Log.i(TAG, text)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "GrandTime 常驻", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_grandtime)
            .setContentTitle("GrandTime")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun notifyStatus(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        AppState.serviceRunning.value = false
        f2spSource?.stop()
        super.onDestroy()
    }
}
```

注意:`actionLabel` 在 Task 11 的 `ui/Labels.kt` 中定义——**本 Task 先创建 `ui/Labels.kt`**(见 Step 3),避免前向引用。

- [ ] **Step 3: 创建 `ui/Labels.kt`(中文文案,UI 与通知共用)**

```kotlin
package com.benzn.grandtime.ui

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.PressType
import com.benzn.grandtime.keymap.KeyAction

fun keyLabel(key: HardKey): String = when (key) {
    HardKey.VIDEO -> "录像键"
    HardKey.PHOTO -> "拍照键"
    HardKey.AUDIO -> "录音键"
    HardKey.SOS -> "SOS键"
}

fun pressLabel(pressType: PressType): String = when (pressType) {
    PressType.SHORT -> "短按"
    PressType.LONG -> "长按"
}

fun actionLabel(action: KeyAction): String = when (action) {
    KeyAction.START_STOP_VIDEO -> "开始/停止录像"
    KeyAction.TOGGLE_VIDEO_UPLOAD -> "切换视频上传"
    KeyAction.TAKE_PHOTO -> "拍照"
    KeyAction.TOGGLE_TORCH -> "开/关手电筒"
    KeyAction.ADJUST_VOLUME -> "调节音量"
    KeyAction.START_STOP_AUDIO -> "开始/停止录音"
    KeyAction.SEND_SOS -> "发送SOS报警"
    KeyAction.TOGGLE_WARNING_LIGHT -> "切换警灯闪烁"
    KeyAction.NONE -> "无"
}
```

- [ ] **Step 4: Manifest 增补(权限 + service)**

在 `<manifest>` 根下加:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

在 `<application>` 内加:

```xml
<service
    android:name=".service.CoreService"
    android:exported="false" />
```

- [ ] **Step 5: 临时启动入口(Task 11 会替换)**

MainActivity 的 `onCreate` 末尾加一行(占位启动,方便本 Task 验证;Task 11 重写整个文件):

```kotlin
startForegroundService(android.content.Intent(this, com.benzn.grandtime.service.CoreService::class.java))
```

- [ ] **Step 6: 构建 + 模拟器验证**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd "C:/Users/camil/Dropbox/GrandTime" && ./gradlew installDebug
ADB="C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" shell am start -n com.benzn.grandtime/.ui.MainActivity
"$ADB" shell dumpsys activity services com.benzn.grandtime | grep -i foreground
```

Expected: `isForeground=true`。下拉通知栏(`"$ADB" shell cmd statusbar expand-notifications`)可见「GrandTime 待命」(未授通知权限时通知可能不显示,Task 11 处理授权;dumpsys 是硬判据)。

- [ ] **Step 7: 全量测试仍绿** — `./gradlew test` → PASS。

- [ ] **Step 8: Commit**

```bash
git add app/src && git commit -m "feat: foreground CoreService wiring key pipeline, stub actions, probe"
```

---

### Task 10: BootReceiver(开机自启链路)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/boot/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `CoreService`(Task 9)

- [ ] **Step 1: 实现 `boot/BootReceiver.kt`**

```kotlin
package com.benzn.grandtime.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benzn.grandtime.service.CoreService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, CoreService::class.java))
        }
    }
}
```

- [ ] **Step 2: Manifest 增补**

`<manifest>` 根下加:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

`<application>` 内加(BOOT_COMPLETED 是受保护系统广播,只有系统能发,exported=true 安全且 targetSdk 31+ 必须显式声明):

```xml
<receiver
    android:name=".boot.BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

- [ ] **Step 3: 模拟器重启验证**

关键前提:新装 App 处于 stopped state,收不到 BOOT_COMPLETED——先打开一次 App 再重启。快照恢复不发 BOOT_COMPLETED,必须真重启:

```bash
./gradlew installDebug
"$ADB" shell am start -n com.benzn.grandtime/.ui.MainActivity
"$ADB" reboot
"$ADB" wait-for-device
sleep 30
"$ADB" shell dumpsys activity services com.benzn.grandtime | grep -i foreground
```

Expected: 开机后未点任何东西,`isForeground=true`。

- [ ] **Step 4: Commit**

```bash
git add app/src && git commit -m "feat: boot receiver auto-starts CoreService"
```

---

### Task 11: Compose UI 三页 + 通知权限请求

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/ui/theme/Theme.kt`、`.../ui/StatusScreen.kt`、`.../ui/ProbeScreen.kt`、`.../ui/KeymapScreen.kt`
- Modify(重写): `app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `AppState`、`LoginState`(Task 8)、`KeyMapping` / `KeyMapStore` / `keymapDataStore`(Task 3/4)、`keyLabel/pressLabel/actionLabel`(Task 9)、`DeviceProfile`(Task 7)、`CoreService`(Task 9)
- Produces: 三页 Tab UI;首启请求 POST_NOTIFICATIONS 后启动 Service。

- [ ] **Step 1: `ui/theme/Theme.kt`**

```kotlin
package com.benzn.grandtime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun GrandTimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(), content = content)
}
```

- [ ] **Step 2: 重写 `ui/MainActivity.kt`**

```kotlin
package com.benzn.grandtime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.benzn.grandtime.service.CoreService
import com.benzn.grandtime.ui.theme.GrandTimeTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startCore() // 授权与否都启动:通知不可见不影响 FGS 运行
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GrandTimeTheme { MainScreen() } }

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
private fun MainScreen() {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("状态", "探针", "改键")
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> StatusScreen()
            1 -> ProbeScreen()
            2 -> KeymapScreen()
        }
    }
}
```

- [ ] **Step 3: `ui/StatusScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.hardware.DeviceProfile
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType
import com.benzn.grandtime.keymap.KeyMapping

@Composable
fun StatusScreen() {
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    val login by AppState.loginState.collectAsStateWithLifecycle()
    val overrides by AppState.overrides.collectAsStateWithLifecycle()
    val lastAction by AppState.lastAction.collectAsStateWithLifecycle()

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("设备:${if (DeviceProfile.isF2spFamily()) "F2SP 家族" else "通用(屏幕按键)"}")
        Text("服务:${if (running) "运行中" else "未运行"}")
        Text(
            "登录:" + when (val s = login) {
                is LoginState.LoggedIn -> s.displayName
                LoginState.LoggedOut -> "未登录"
            }
        )
        Text("最近动作:${lastAction ?: "—"}")
        Spacer(Modifier.height(16.dp))
        Text("当前映射", style = MaterialTheme.typography.titleMedium)
        HardKey.entries.forEach { key ->
            PressType.entries.forEach { pressType ->
                val action = KeyMapping.resolve(KeyPress(key, pressType), overrides)
                val overridden = KeyMapping.overrideKeyOf(key, pressType) in overrides
                Text("${keyLabel(key)} ${pressLabel(pressType)} → ${actionLabel(action)}" +
                    if (overridden) "(已改)" else "")
            }
        }
    }
}
```

- [ ] **Step 4: `ui/ProbeScreen.kt`(含屏幕按键)**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.RawDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProbeScreen() {
    val entries by AppState.probeEntries.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HardKey.entries.forEach { key ->
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(key) {
                            awaitEachGesture {
                                // requireUnconsumed=false:不与按钮自身的点击手势竞争
                                awaitFirstDown(requireUnconsumed = false)
                                AppState.screenKeyEvents.tryEmit(key to RawDirection.DOWN)
                                waitForUpOrCancellation()
                                AppState.screenKeyEvents.tryEmit(key to RawDirection.UP)
                            }
                        },
                ) { Text(keyLabel(key)) }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries) { entry ->
                Text(
                    "${timeFormat.format(Date(entry.timestampMillis))}  ${entry.text}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
```

(注:`pointerInput` 挂在 OutlinedButton 上、onClick 留空——down/up 由 pointerInput 捕获,长短按交给 Service 端 PressTypeDetector 判定,与物理键同一条管线。)

- [ ] **Step 5: `ui/KeymapScreen.kt`**

```kotlin
package com.benzn.grandtime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

@Composable
fun KeymapScreen() {
    val context = LocalContext.current
    val store = remember { KeyMapStore(context.applicationContext.keymapDataStore) }
    val scope = rememberCoroutineScope()
    val overrides by AppState.overrides.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        HardKey.entries.forEach { key ->
            PressType.entries.forEach { pressType ->
                val current = KeyMapping.resolve(KeyPress(key, pressType), overrides)
                var menuOpen by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${keyLabel(key)} ${pressLabel(pressType)}", Modifier.weight(1f))
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text(actionLabel(current))
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
        Button(
            onClick = { scope.launch { store.resetToDefaults() } },
            Modifier.padding(top = 16.dp),
        ) { Text("恢复默认映射") }
    }
}
```

- [ ] **Step 6: 构建 + 模拟器手动走查**

```bash
./gradlew installDebug && "$ADB" shell am start -n com.benzn.grandtime/.ui.MainActivity
```

核对:①首启弹通知权限,允许后通知栏出现「GrandTime 待命」;②探针页四个屏幕键:快点 = SHORT 行 + 桩动作行,按住 >1s = LONG 行(松手前就出现);③改键页把「录像键 短按」改成「拍照」→ 状态页映射表联动显示「(已改)」→ 再按屏幕录像键,桩动作变「[桩] 拍照」;④「恢复默认映射」后回落。

- [ ] **Step 7: 全量测试仍绿** — `./gradlew test` → PASS。

- [ ] **Step 8: Commit**

```bash
git add app/src && git commit -m "feat: Compose UI (status/probe/keymap) with notification permission flow"
```

---

### Task 12: 模拟器端到端回归(adb 广播,M7 完成门)

**Files:** 无新文件(纯验证;发现问题回对应 Task 修)

- [ ] **Step 1: 短按/长按广播回归(四键逐个)**

```bash
ADB="C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe"
# 短按:0.3s 抬起 → SHORT → START_STOP_VIDEO
"$ADB" shell am broadcast -a lolaage.video1.down; sleep 0.3; "$ADB" shell am broadcast -a lolaage.video1.up
# 长按:1s 到点即发 LONG(探针先出 LONG,再无 SHORT)
"$ADB" shell am broadcast -a lolaage.video1.down; sleep 1.5; "$ADB" shell am broadcast -a lolaage.video1.up
# 其余三键同法
"$ADB" shell am broadcast -a lolaage.take.picture.down; sleep 0.3; "$ADB" shell am broadcast -a lolaage.take.picture.up
"$ADB" shell am broadcast -a lolaage.take.picture.down; sleep 1.5; "$ADB" shell am broadcast -a lolaage.take.picture.up
"$ADB" shell am broadcast -a lolaage.audio.down; sleep 0.3; "$ADB" shell am broadcast -a lolaage.audio.up
"$ADB" shell am broadcast -a lolaage.audio.down; sleep 1.5; "$ADB" shell am broadcast -a lolaage.audio.up
"$ADB" shell am broadcast -a lolaage.sos.down; sleep 0.3; "$ADB" shell am broadcast -a lolaage.sos.up
"$ADB" shell am broadcast -a lolaage.sos.down; sleep 1.5; "$ADB" shell am broadcast -a lolaage.sos.up
```

Expected(探针页 + `"$ADB" logcat -s GrandTime -d`):每键短按出 `SHORT → 默认动作`,长按出 `LONG → 默认动作`,长按后的 up 不产生 SHORT;通知文字随桩动作变化。

- [ ] **Step 2: probe-only 广播**

```bash
"$ADB" shell am broadcast -a lolaage.light
```

Expected: 探针只出原始行 `lolaage.light`,无动作派发。

- [ ] **Step 3: 改键回归**

改键页把「录像键 短按」→「拍照」,重发短按广播 → 探针出 `VIDEO SHORT → TAKE_PHOTO`;恢复默认后再验回 `START_STOP_VIDEO`。

- [ ] **Step 4: 不注册 ptt 验证**

```bash
"$ADB" shell am broadcast -a lolaage.ptt.down
```

Expected: 探针无任何新行(未注册)。

- [ ] **Step 5: 开机回归**

```bash
"$ADB" reboot && "$ADB" wait-for-device && sleep 30
"$ADB" shell dumpsys activity services com.benzn.grandtime | grep -i foreground
```

Expected: `isForeground=true`,通知自动出现。

- [ ] **Step 6: 全量单测收尾** — `./gradlew test` → PASS。

- [ ] **Step 7: Commit(如有修正)+ 打标记**

```bash
git add -u && git commit -m "test: emulator e2e regression fixes" # 仅当有改动
git tag subproject-1-emulator-done
```

---

### Task 13: 真机验收(F2SP,M8 = 子项目1完成门)

**Files:** 可能修改 `F2spKeyEventSource.kt` 的 action 常量表(仅当真机 action 与逆向不符)

- [ ] **Step 1【用户手动步骤】: 打开 USB 调试**
  - F2SP:设置 → 关于手机 → 连点「版本号」7 次开启开发者模式
  - 开发者选项 → 打开「USB 调试」
  - USB 连电脑,设备上勾选「始终允许此电脑调试」

- [ ] **Step 2: 确认连接 + 记录设备信息**

```bash
"$ADB" devices                        # 出现真机序列号
"$ADB" shell getprop ro.product.model # 记录:应为 SDJW-F2SP/F2S-A/SDJW-F2S/XB-15 之一
```

若型号不在 DeviceProfile 列表内:把实际型号加进 `F2SP_MODELS` 并 commit。

- [ ] **Step 3: 停用 SMART-PTT**

```bash
"$ADB" shell pm list packages | grep -i corget   # 确认包名
"$ADB" shell pm disable-user --user 0 com.corget # 以实际包名为准
```

(如 pm disable-user 无权限,**【用户手动步骤】**设置 → 应用 → SMART-PTT → 停用。)

- [ ] **Step 4【用户手动步骤】: 自启白名单**
  - 在 ROM 设置里找「自启动管理」/「电池优化」,把 GrandTime 加入允许自启/不优化名单(位置因 ROM 而异)。

- [ ] **Step 5: 安装 + 首次打开**

```bash
./gradlew installDebug
```

手动打开 App 一次(解除 stopped state)+ 允许通知权限。

- [ ] **Step 6: 物理键逐格核对**

逐个按四个物理键,短按/长按各一次,对照探针页与 spec §4 逆向表逐格核对(action 原文、短/长判定、映射动作)。
**若某键无反应**:

```bash
"$ADB" logcat -d | grep -i lolaage
```

找 ROM 实际 action 名 → 修 `F2spKeyEventSource.KEY_ACTION_PREFIXES`/`PROBE_ONLY_ACTIONS` → `./gradlew installDebug` 复验 → commit 修正。

- [ ] **Step 7: 开机自启终验**

重启设备,**不碰屏幕**,等待开机完成 → 通知栏出现「GrandTime 待命」。

- [ ] **Step 8: 验收 Commit + 标记**

```bash
git add -u && git commit -m "fix: align lolaage action constants with real device" # 仅当有修正
git tag subproject-1-accepted
git push origin main --tags
```

---

## 验证总览

| 层次 | 手段 | 覆盖 |
|---|---|---|
| JVM 单测 | `./gradlew test` | 短/长按状态机、映射解析、覆盖存储、派发、action 解析、日志轮转 |
| 模拟器 | adb 广播 + 三页 UI 走查 + reboot | 广播→判定→映射→桩动作→通知/探针 全链路、开机自启 |
| 真机 F2SP | Task 13 清单 | ROM 真实广播、自启白名单、SMART-PTT 共存排除 |

## 风险与缓解(摘自 spec §10 + 环境事实)

- **action 字符串与逆向不符** → 探针页 + logcat 定位,常量表单点修正(Task 13 Step 6 内置回路)
- **ROM 自启管控拦 BOOT_COMPLETED** → 白名单手动步骤;无效时 logcat 观察广播到达情况再定
- **Dropbox 同步与构建冲突** → build/.gradle 已 ignore;构建报文件锁时暂停 Dropbox 同步
- **CRLF** → Task 1 的 .gitattributes 先于一切代码落库
