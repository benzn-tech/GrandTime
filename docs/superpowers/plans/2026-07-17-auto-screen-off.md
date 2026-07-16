# Auto screen-off Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the in-app "Auto screen-off" setting actually sleep the display at the chosen minutes (recording and idle), by driving the system screen-off timeout, while keeping recording alive with a partial wakelock.

**Architecture:** Replace the passive `FLAG_KEEP_SCREEN_ON` scheme with two pieces: (1) write the system `Settings.System.SCREEN_OFF_TIMEOUT` to the user's chosen minutes (WRITE_SETTINGS) so the OS sleeps naturally; (2) hold a `PARTIAL_WAKE_LOCK` while any capture is active so recording survives screen-off (probe-confirmed viable on this ROM). Remove the old keep-awake timer/flag path.

**Tech Stack:** Kotlin, Android framework only (PowerManager, Settings.System), Compose, DataStore.

## Global Constraints
- All Android framework; **no new Gradle deps / native libs**; no Google Play Services.
- **English** for all new code/comments/commits; Chinese only for user-facing copy already in that style.
- Flavored gradle task names: unit test = `testProdDebugUnitTest`; build = `assembleProdDebug` / `assembleDevDebug`.
- Build env: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`; Dropbox build-lock → rerun once.
- `screenOffMinutes` options are `1,3,5,0` (`0` = "Never"), default `3` (existing `SettingsStore`).
- WAKE_LOCK = normal permission (auto-granted); WRITE_SETTINGS = special permission (system settings page).
- Recording lives in `CoreService` (a `LifecycleService`), independent of any UI screen. `AppState.captureState` is a `StateFlow<CaptureState>` with `Idle` / `RecordingVideo` / `RecordingAudio`.

---

### Task 1: System screen-off timeout writer + WRITE_SETTINGS permission

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/core/ScreenTimeout.kt`
- Create: `app/src/main/java/com/benzn/grandtime/service/ScreenTimeoutController.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/service/CoreService.kt` (apply on start + on change)
- Modify: `app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt` (permission prompt)
- Modify: `app/src/main/AndroidManifest.xml` (WRITE_SETTINGS)
- Test: `app/src/test/java/com/benzn/grandtime/core/ScreenTimeoutTest.kt`

**Interfaces:**
- Produces: `screenOffTimeoutMillis(minutes: Int): Int` (pure); `ScreenTimeoutController.apply(context, minutes): Boolean`.

- [ ] **Step 1: Write the failing test** — `ScreenTimeoutTest.kt`

```kotlin
package com.benzn.grandtime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeoutTest {
    @Test fun `one minute maps to 60000 ms`() = assertEquals(60_000, screenOffTimeoutMillis(1))
    @Test fun `three minutes maps to 180000 ms`() = assertEquals(180_000, screenOffTimeoutMillis(3))
    @Test fun `five minutes maps to 300000 ms`() = assertEquals(300_000, screenOffTimeoutMillis(5))
    @Test fun `never (0) maps to Int MAX (effectively never sleeps)`() =
        assertEquals(Int.MAX_VALUE, screenOffTimeoutMillis(0))
    @Test fun `negative is treated as never`() = assertEquals(Int.MAX_VALUE, screenOffTimeoutMillis(-1))
}
```

- [ ] **Step 2: Run it, verify it fails** — `./gradlew testProdDebugUnitTest --tests '*ScreenTimeoutTest*'` → FAIL (unresolved reference `screenOffTimeoutMillis`).

- [ ] **Step 3: Implement the pure mapping** — `ScreenTimeout.kt`

```kotlin
package com.benzn.grandtime.core

/**
 * Maps the app's "Auto screen-off" setting (minutes; 0 = Never) to the value written to the
 * system Settings.System.SCREEN_OFF_TIMEOUT (milliseconds). 0/negative -> Int.MAX_VALUE, which
 * the OS treats as effectively never sleeping.
 */
fun screenOffTimeoutMillis(minutes: Int): Int =
    if (minutes <= 0) Int.MAX_VALUE else minutes * 60_000
```

- [ ] **Step 4: Run it, verify it passes** — same command → PASS (5/5).

- [ ] **Step 5: Implement the controller** — `ScreenTimeoutController.kt` (Android side, verified on device)

```kotlin
package com.benzn.grandtime.service

import android.content.Context
import android.provider.Settings
import com.benzn.grandtime.core.screenOffTimeoutMillis

/**
 * Writes the system screen-off timeout to match the app's "Auto screen-off" setting, so the OS
 * sleeps the display naturally (recording and idle). Requires the special WRITE_SETTINGS
 * permission; when it isn't granted this is a no-op returning false (the UI surfaces the grant).
 */
object ScreenTimeoutController {
    fun apply(context: Context, minutes: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                screenOffTimeoutMillis(minutes),
            )
        }.getOrDefault(false)
    }
}
```

- [ ] **Step 6: Apply on service start + on setting change** — in `CoreService.startPipeline()`, after the `SiteStore` collector block (around line 174), add:

```kotlin
// Drive the system screen-off timeout from the app setting so the display sleeps at the
// chosen minutes (recording and idle). No-op until WRITE_SETTINGS is granted; re-applied on
// every change and on start (the setting is global and may have drifted).
lifecycleScope.launch {
    SettingsStore(applicationContext.settingsDataStore).settings
        .map { it.screenOffMinutes }
        .distinctUntilChanged()
        .collect { ScreenTimeoutController.apply(applicationContext, it) }
}
```

Add imports to `CoreService.kt`: `import kotlinx.coroutines.flow.map` and `import kotlinx.coroutines.flow.distinctUntilChanged`.

- [ ] **Step 7: Add the permission prompt** — in `MainActivity.kt`, add a method mirroring `maybeRequestAllFiles()`:

```kotlin
private fun maybeRequestWriteSettings() {
    if (!Settings.System.canWrite(this)) {
        Toast.makeText(
            this,
            "Allow \"Modify system settings\" so the screen can auto-sleep at your chosen time",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
        )
    }
}
```

Call it in both spots that already call `maybeRequestOverlay()` / `maybeRequestAllFiles()` — the `permissionLauncher` callback (after line 69) and the `onCreate` `required.isEmpty()` branch (after line 86). (`android.provider.Settings`, `Toast`, `Intent`, `Uri` are already imported for the sibling methods.)

- [ ] **Step 8: Declare the permission** — in `AndroidManifest.xml`, add next to the other `<uses-permission>` entries:

```xml
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

- [ ] **Step 9: Build + test** — `./gradlew testProdDebugUnitTest` (all green incl. ScreenTimeoutTest) and `./gradlew assembleProdDebug`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/core/ScreenTimeout.kt \
  app/src/main/java/com/benzn/grandtime/service/ScreenTimeoutController.kt \
  app/src/test/java/com/benzn/grandtime/core/ScreenTimeoutTest.kt \
  app/src/main/java/com/benzn/grandtime/service/CoreService.kt \
  app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt \
  app/src/main/AndroidManifest.xml
git commit -m "feat(screen-off): drive system SCREEN_OFF_TIMEOUT from app setting (WRITE_SETTINGS)"
```

---

### Task 2: Partial wakelock during capture + remove obsolete keep-awake path

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/service/CaptureWakeLock.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/service/CoreService.kt` (hold wakelock by capture state)
- Modify: `app/src/main/AndroidManifest.xml` (WAKE_LOCK)
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt` (remove screenOffTimer)
- Modify: `app/src/main/java/com/benzn/grandtime/core/AppState.kt` (remove screenOffRequest)
- Modify: `app/src/main/java/com/benzn/grandtime/ui/RecordingScreen.kt` (remove FLAG_KEEP_SCREEN_ON)

**Interfaces:**
- Consumes: `AppState.captureState: StateFlow<CaptureState>` (`Idle`/`RecordingVideo`/`RecordingAudio`).
- Produces: `CaptureWakeLock(context)` with idempotent `acquire()` / `release()`.

- [ ] **Step 1: Declare WAKE_LOCK** — in `AndroidManifest.xml` add:

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

- [ ] **Step 2: Implement the wakelock wrapper** — `CaptureWakeLock.kt`

```kotlin
package com.benzn.grandtime.service

import android.content.Context
import android.os.PowerManager

/**
 * A PARTIAL_WAKE_LOCK held while any capture is active, so recording keeps running when the
 * display sleeps (the screen-off timeout no longer keeps the CPU awake for us). Idempotent:
 * acquire()/release() guard on isHeld so repeated calls from the state collector are safe.
 */
class CaptureWakeLock(context: Context) {
    private val wl = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GrandTime:capture")

    fun acquire() {
        if (!wl.isHeld) wl.acquire()
    }

    fun release() {
        if (wl.isHeld) runCatching { wl.release() }
    }
}
```

- [ ] **Step 3: Hold the wakelock by capture state** — in `CoreService`:
  - Add a field near `overlayGuard`: `private lateinit var captureWakeLock: CaptureWakeLock`.
  - In `onCreate()`, after `overlayGuard = OverlayGuard(this)`: `captureWakeLock = CaptureWakeLock(this)`.
  - In `startPipeline()`, add a collector (with the LED/state collectors):

```kotlin
// Keep the CPU awake only while a capture is active, so recording survives the screen sleeping.
lifecycleScope.launch {
    AppState.captureState.collect { state ->
        val recording = state is com.benzn.grandtime.capture.CaptureState.RecordingVideo ||
            state is com.benzn.grandtime.capture.CaptureState.RecordingAudio
        if (recording) captureWakeLock.acquire() else captureWakeLock.release()
    }
}
```

  - In `onDestroy()`, before `super.onDestroy()`: `captureWakeLock.release()` (guard against a leak if the service dies mid-recording).

- [ ] **Step 4: Remove the obsolete screen-off timer from `CaptureManager.kt`**
  - Delete the field `private var screenOffTimer: Job? = null` (line 66).
  - Delete the functions `startScreenOffTimer(minutes: Int)` and `stopScreenOffTimer()` (lines ~304-317).
  - Delete the call `startScreenOffTimer(settings.screenOffMinutes)` (line 247) — leave `sounds.startRecording()` in the `if (cmd.segmentIndex == 1)` block.
  - Delete every `stopScreenOffTimer()` call: in `pipeline.onCameraLost` (line ~82), in the finalize callback error branch (line ~203), in the Idle branch (line ~215), and in the `result == null` failure branch (line ~224).
  - In `shutdown()` (lines ~121-131): delete `screenOffTimer?.cancel()` and `AppState.screenOffRequest.value = false`.

- [ ] **Step 5: Remove `screenOffRequest` from `AppState.kt`** — delete `val screenOffRequest = MutableStateFlow(false)` (line ~38). Confirm no other references remain: `grep -rn screenOffRequest app/src` returns nothing.

- [ ] **Step 6: Remove keep-screen-on from `RecordingScreen.kt`**
  - Delete `val screenOff by AppState.screenOffRequest.collectAsStateWithLifecycle()` (line 34).
  - Delete the entire `LaunchedEffect(screenOff) { ... addFlags/clearFlags(FLAG_KEEP_SCREEN_ON) }` block (lines 61-65).
  - In the `DisposableEffect(Unit) { onDispose { ... } }`, delete the safety-net line `activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` (line 58) and its comment (56-57); keep `AppState.previewSurface.value = null`.
  - Remove now-unused imports (`WindowManager` if no longer referenced; keep `activity`/`context` usage otherwise). Let the compiler guide: build and delete only genuinely unused imports.

- [ ] **Step 7: Build + test** — `./gradlew testProdDebugUnitTest` (all green; no test asserts the removed machinery — if one does, it is dead and should be removed) and `./gradlew assembleProdDebug assembleDevDebug`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/service/CaptureWakeLock.kt \
  app/src/main/java/com/benzn/grandtime/service/CoreService.kt \
  app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt \
  app/src/main/java/com/benzn/grandtime/core/AppState.kt \
  app/src/main/java/com/benzn/grandtime/ui/RecordingScreen.kt \
  app/src/main/AndroidManifest.xml
git commit -m "feat(screen-off): partial wakelock during capture; drop passive FLAG_KEEP_SCREEN_ON path"
```

---

### Task 3: Device acceptance (F2SP)

Not a code task — a device-verification checklist run on `F2S202503103054` after Tasks 1-2. Disable SMART-PTT first (`pm disable com.corget`). Install `assembleProdDebug`.

- [ ] **Grant + settings:** Open app → grant "Modify system settings" when prompted. Set Auto screen-off = 1 min. Verify `adb shell settings get system screen_off_timeout` → `60000`. Set 3 → `180000`, 5 → `300000`, Never → a very large value (Int.MAX_VALUE).
- [ ] **Idle sleep:** Auto screen-off = 1 min, sit on Home untouched → screen sleeps at ~1 min.
- [ ] **Recording sleep + survival:** Auto screen-off = 1 min, start a video recording, don't touch → screen sleeps at ~1 min while recording; confirm `adb shell dumpsys power | grep GrandTime` shows the held `GrandTime:capture` wakelock; let it run past a segment rollover, wake, stop, pull the segments and `ffprobe` → all non-truncated, audio+video present. Repeat for an audio-only recording (wakelock is the only thing keeping the CPU up there).
- [ ] **Never:** Auto screen-off = Never → screen stays on indefinitely.
- [ ] **Revoked path:** Revoke "Modify system settings" in system settings → app doesn't crash; reopening re-prompts.
- [ ] **No regression:** SP-Ask voice still answers; physical keys still record; LED still cycles.

---

## Self-Review notes
- Spec coverage: G-mechanism (Task 1), wakelock + remove-old (Task 2), acceptance incl. probe follow-through (Task 3). ✓
- Type consistency: `screenOffTimeoutMillis` used by `ScreenTimeoutController`; `CaptureWakeLock.acquire/release` used by the `captureState` collector; `CaptureState.RecordingVideo/RecordingAudio` are the existing state types (see `CoreService` LED loop). ✓
- No placeholders; every code step shows the code. ✓
