# Key Swap + Hold-to-Talk + Bindings Cleanup — Implementation Plan

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Swap the SOS and PTT physical keys' functions (SOS → Ask agent, PTT → Site voice), add a ~1s hold-to-activate debounce to both (avoid accidental triggers), and clean up the Key Bindings screen to read-only-show the two hold-to-talk keys instead of the dead SOS keymap rows.

**Architecture:** Replace the two near-identical `PttKeySource`/`SosKeySource` broadcast receivers with one shared `HoldToTalkKeySource(downAction, upAction, holdMillis)` that emits `Direction.DOWN` only after the key has been held `holdMillis` (default 1000ms) and `Direction.UP` on release-after-activation (a short tap emits nothing). CoreService wires `lolaage.sos.*` → AskManager (unconditional) and `lolaage.ptt.*` → SiteVoiceManager (gated by `SITE_VOICE_ENABLED`) — the swap. The cores (`AskCore`/`SiteVoiceCore`) are unchanged; the 1s gate lives entirely in the source.

**Tech Stack:** Kotlin, Android BroadcastReceiver, coroutines (hold timer via injected scope, testable with virtual time like `PressTypeDetector`), Compose (bindings screen).

## Global Constraints
- No new Gradle deps. English-only dev artifacts. Build: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug testProdDebugUnitTest`. Dropbox lock: rerun once.
- The swap: **SOS physical key (`lolaage.sos.*`) → Ask; PTT physical key (`lolaage.ptt.*`) → Site voice.**
- Hold threshold default 1000ms; a release before the threshold emits NOTHING (ignored).
- Site-voice wiring stays gated by `BuildConfig.SITE_VOICE_ENABLED`; Ask wiring stays unconditional. After the swap this means: SOS→Ask works on all flavors; PTT→site-voice is dark on prod until the flag flips (intended dark-launch state).
- Preserve the "subscribe (collect) before start()" ordering (MutableSharedFlow replay=0).

---

## Task 1: `HoldToTalkKeySource` (shared source + 1s hold debounce)

**IMPLEMENTATION NOTE (testability — follow this over the sketch below):** Split into (a) a PURE `HoldToTalkGate(scope: CoroutineScope, holdMillis: Long = 1000L)` holding the timer state machine — `fun onDown()`, `fun onUp()`, `val events: SharedFlow<HoldDirection>` — with NO `Context` dependency, so it is JVM-unit-testable with a `TestScope`/virtual time (this is what the tests target); and (b) a thin `HoldToTalkKeySource(context, downAction, upAction, gate: HoldToTalkGate)` (or constructing its own gate) BroadcastReceiver that just maps `downAction→gate.onDown()`, `upAction→gate.onUp()` and does register/unregister in `start()`/`stop()` (device-verified, no JVM test). This mirrors the repo's "pure core + thin Android wrapper" pattern (SiteVoiceCore/SiteVoiceManager). The test code below should target `HoldToTalkGate` (construct it with just a `TestScope` — no Context/mock needed). Check how `PressTypeDetectorTest` sets up its `TestScope` and mirror it exactly.

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/hardware/HoldToTalkGate.kt` (pure timer state machine + `HoldDirection`)
- Create: `app/src/main/java/com/benzn/grandtime/hardware/HoldToTalkKeySource.kt` (thin BroadcastReceiver wrapping a gate)
- Create: `app/src/test/java/com/benzn/grandtime/hardware/HoldToTalkGateTest.kt`

**Interfaces:**
- Produces: `enum class HoldDirection { DOWN, UP }`; `class HoldToTalkKeySource(context: Context, private val downAction: String, private val upAction: String, private val scope: CoroutineScope, private val holdMillis: Long = 1000L)` with `val events: SharedFlow<HoldDirection>`, `fun start()`, `fun stop()`, and a package-visible `fun onAction(action: String)` for unit testing the timer logic without a real broadcast.
- Consumes: nothing.

Model the timer on `hardware/PressTypeDetector.kt` (read it — same "start timer on down, fire at threshold, cancel on early up" shape, tested with a `TestScope`/virtual time). All state (`pendingTimer`, `activated`) is touched only from `onAction` (Main/broadcast thread) and the timer coroutine on `scope` — single-threaded, no lock needed.

- [ ] **Step 1: Write the failing tests** (`HoldToTalkKeySourceTest.kt`), using a coroutine test scheduler so the 1000ms delay is virtual:

```kotlin
package com.benzn.grandtime.hardware

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HoldToTalkKeySourceTest {
    private fun source(scope: TestScope) =
        HoldToTalkKeySource(
            context = null as android.content.Context? ?: throw AssertionError(), // not used by onAction path
            downAction = "x.down", upAction = "x.up", scope = scope, holdMillis = 1000L,
        )

    @Test fun hold_past_threshold_emits_DOWN_then_UP() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val src = HoldToTalkKeySource(fakeContext(), "x.down", "x.up", scope, 1000L)
        val got = mutableListOf<HoldDirection>()
        val job = scope.launch { src.events.toList(got) }
        src.onAction("x.down")
        scope.testScheduler.advanceTimeBy(1001); scope.testScheduler.runCurrent()
        src.onAction("x.up")
        scope.testScheduler.runCurrent()
        assertEquals(listOf(HoldDirection.DOWN, HoldDirection.UP), got)
        job.cancel()
    }

    @Test fun short_tap_before_threshold_emits_nothing() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val src = HoldToTalkKeySource(fakeContext(), "x.down", "x.up", scope, 1000L)
        val got = mutableListOf<HoldDirection>()
        val job = scope.launch { src.events.toList(got) }
        src.onAction("x.down")
        scope.testScheduler.advanceTimeBy(500); scope.testScheduler.runCurrent()
        src.onAction("x.up")
        scope.testScheduler.advanceTimeBy(1000); scope.testScheduler.runCurrent()
        assertEquals(emptyList<HoldDirection>(), got)
        job.cancel()
    }

    @Test fun unknown_action_is_ignored() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val src = HoldToTalkKeySource(fakeContext(), "x.down", "x.up", scope, 1000L)
        val got = mutableListOf<HoldDirection>()
        val job = scope.launch { src.events.toList(got) }
        src.onAction("y.down")
        scope.testScheduler.advanceTimeBy(2000); scope.testScheduler.runCurrent()
        assertEquals(emptyList<HoldDirection>(), got)
        job.cancel()
    }
}
```

Note: the tests only exercise `onAction()` + the timer; `context` is never touched on that path. Provide a minimal `fakeContext()` helper (e.g. `org.mockito`/`mock()` if already a test dep, else `Robolectric`—check what the repo uses; if neither, make the constructor accept `context` but only use it in `start()`/`stop()`, and pass a `mock()`/a trivial subclass). Match the repo's existing test infra (see how `PressTypeDetectorTest`/`SosKeySourceTest` obtain a Context — reuse that exact approach).

- [ ] **Step 2: Run tests → fail** (`... --tests "com.benzn.grandtime.hardware.HoldToTalkKeySourceTest"`) — expected FAIL (class missing).

- [ ] **Step 3: Implement `HoldToTalkKeySource.kt`:**

```kotlin
package com.benzn.grandtime.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Direction of a hold-to-talk key after the hold threshold has been applied. */
enum class HoldDirection { DOWN, UP }

/**
 * A hold-to-talk physical-key source (SOS or PTT). Emits [HoldDirection.DOWN] only once the key has
 * been held for [holdMillis] (default 1000ms) — a shorter tap is ignored (debounce against accidental
 * presses) — and [HoldDirection.UP] on release AFTER activation. Reads a ROM `lolaage.*` broadcast
 * pair ([downAction]/[upAction]); the SHORT/LONG [PressTypeDetector] cannot express hold-until-release,
 * so this bypasses the keymap (same design as the sources it replaces). Collectors MUST subscribe to
 * [events] before [start] — MutableSharedFlow(replay=0) drops emissions with no subscribers.
 * All state is touched only on the broadcast/Main thread + the [scope] timer coroutine (single-threaded).
 */
class HoldToTalkKeySource(
    private val context: Context,
    private val downAction: String,
    private val upAction: String,
    private val scope: CoroutineScope,
    private val holdMillis: Long = 1000L,
) {
    private val _events = MutableSharedFlow<HoldDirection>(extraBufferCapacity = 16)
    val events: SharedFlow<HoldDirection> = _events

    private var pendingTimer: Job? = null
    private var activated = false

    /** Package-visible for unit tests; also the body of [receiver]'s onReceive. */
    internal fun onAction(action: String) {
        when (action) {
            downAction -> {
                pendingTimer?.cancel()
                activated = false
                pendingTimer = scope.launch {
                    delay(holdMillis)
                    activated = true
                    _events.tryEmit(HoldDirection.DOWN)
                }
            }
            upAction -> {
                pendingTimer?.cancel(); pendingTimer = null
                if (activated) _events.tryEmit(HoldDirection.UP)
                activated = false
            }
            // else: not ours — ignore
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onAction(intent.action ?: return)
        }
    }

    fun start() {
        val filter = IntentFilter().apply { addAction(downAction); addAction(upAction) }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun stop() {
        pendingTimer?.cancel(); pendingTimer = null; activated = false
        runCatching { context.unregisterReceiver(receiver) }
    }
}
```

- [ ] **Step 4: Run tests → pass.**
- [ ] **Step 5: Commit** `HoldToTalkKeySource.kt` + test: `feat(hardware): HoldToTalkKeySource with ~1s hold-to-activate debounce`

---

## Task 2: Swap the wiring in `CoreService` + delete the old sources

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/service/CoreService.kt` (fields ~71-73; `startPipeline()` ~294-354)
- Delete: `app/src/main/java/com/benzn/grandtime/ask/PttKeySource.kt`, `app/src/main/java/com/benzn/grandtime/sitevoice/SosKeySource.kt`, and their tests `app/src/test/.../sitevoice/SosKeySourceTest.kt` (+ any `PttKeySourceTest`).

**Interfaces:**
- Consumes: `HoldToTalkKeySource`, `HoldDirection` (Task 1); `AskManager.onPttDown/onPttUp`; `SiteVoiceManager.onSosDown/onSosUp`.

- [ ] **Step 1:** grep to confirm `PttKeySource`/`SosKeySource`/`PttDirection`/`SosDirection` are referenced ONLY in `CoreService.kt` and their own files/tests: `grep -rn "PttKeySource\|SosKeySource\|PttDirection\|SosDirection" app/src`.

- [ ] **Step 2:** Replace the field decls (`pttSource`/`sosSource` typed to the old classes) with:
```kotlin
    private var sosKey: com.benzn.grandtime.hardware.HoldToTalkKeySource? = null
    private var pttKey: com.benzn.grandtime.hardware.HoldToTalkKeySource? = null
```

- [ ] **Step 3:** Replace the PTT block (lines ~294-304) — the physical **SOS** key now drives **Ask** (unconditional):
```kotlin
        val sos = com.benzn.grandtime.hardware.HoldToTalkKeySource(
            this, "lolaage.sos.down", "lolaage.sos.up", lifecycleScope,
        )
        sosKey = sos
        lifecycleScope.launch {
            sos.events.collect { dir ->
                probe("sos-key(ask) ${dir.name}")
                when (dir) {
                    com.benzn.grandtime.hardware.HoldDirection.DOWN -> ask.onPttDown()
                    com.benzn.grandtime.hardware.HoldDirection.UP -> ask.onPttUp()
                }
            }
        }
```

- [ ] **Step 4:** In the `if (BuildConfig.SITE_VOICE_ENABLED)` block, replace the SosKeySource wiring (lines ~320-330) — the physical **PTT** key now drives **Site voice**:
```kotlin
                val ptt = com.benzn.grandtime.hardware.HoldToTalkKeySource(
                    this, "lolaage.ptt.down", "lolaage.ptt.up", lifecycleScope,
                )
                pttKey = ptt
                lifecycleScope.launch {
                    ptt.events.collect { dir ->
                        probe("ptt-key(sitevoice) ${dir.name}")
                        when (dir) {
                            com.benzn.grandtime.hardware.HoldDirection.DOWN -> siteVoice.onSosDown()
                            com.benzn.grandtime.hardware.HoldDirection.UP -> siteVoice.onSosUp()
                        }
                    }
                }
```
(Keep the `siteVoiceReplayRequests.collect` + `siteVoice.start()` lines as-is.)

- [ ] **Step 5:** Update the `start()` calls (lines ~352-354): replace `ptt.start()` / `sosSource?.start()` with `sosKey?.start()` and `pttKey?.start()` (both after `f2sp.start()`, preserving subscribe-before-start).

- [ ] **Step 6:** `onDestroy()` (~418-421): replace `pttSource?.stop()` / `sosSource?.stop()` with `sosKey?.stop()` / `pttKey?.stop()`.

- [ ] **Step 7:** Delete `ask/PttKeySource.kt`, `sitevoice/SosKeySource.kt`, `sitevoice/SosKeySourceTest.kt` (and `PttKeySourceTest` if present). Remove any now-unused imports in CoreService (`PttDirection`, etc.).

- [ ] **Step 8:** Build + full suite: `./gradlew assembleDevDebug testProdDebugUnitTest` → BUILD SUCCESSFUL, all green (the deleted SosKeySourceTest's cases are replaced by HoldToTalkKeySourceTest).

- [ ] **Step 9: Commit** (both the CoreService rewire and the deletions in one commit): `feat(service): swap SOS<->PTT (SOS=Ask, PTT=Site voice) via HoldToTalkKeySource`

---

## Task 3: Key Bindings screen — drop the dead SOS row, show the hold-to-talk keys read-only

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ui/KeyBindingsScreen.kt`

**Interfaces:** Consumes: `HardKey`, `KeyMapping`, `KeyMapStore` (unchanged). This is display-only.

- [ ] **Step 1:** In the row loop (`~line 62`), skip `HardKey.SOS` (it no longer maps to a keymap action — the physical SOS key is now the Ask hold-to-talk key, handled outside the keymap). E.g. `HardKey.entries.filter { it != HardKey.SOS }.forEach { ... }`. (Leave `HardKey.SOS` in the enum for now to avoid touching `KeyMapping.DEFAULTS`/`KeyMapStore` tests — it's just no longer rendered.)

- [ ] **Step 2:** Add a read-only informational section (below the bindable rows, above the reset button) showing the two fixed hold-to-talk keys — match the screen's existing `Text`/row styling:
```kotlin
        Spacer(Modifier.height(16.dp))
        Text("Hold-to-talk keys (fixed)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Text("SOS  →  Ask agent (hold ~1s)", style = MaterialTheme.typography.bodyMedium)
        Text("PTT  →  Site voice (hold ~1s to talk)", style = MaterialTheme.typography.bodyMedium)
```
(Read the real file for the exact imports/spacing tokens in use; do not introduce new styles.)

- [ ] **Step 3:** Build: `./gradlew assembleDevDebug` → SUCCESSFUL. (Compose; visual check on device.)
- [ ] **Step 4: Commit:** `feat(ui): key bindings — hide dead SOS row, show fixed hold-to-talk mapping`

---

## Final verification (device, after all 3 tasks)
Prod app stopped on the test device (two-app interference removed). Dev build, logged in + site selected.
- **SOS key held ~1s** → Ask activates (listening cue + records); released → Ask sends. A quick SOS tap (<1s) → nothing. Verify via `adb ... am broadcast -a lolaage.sos.down` (wait &gt;1s) `... lolaage.sos.up` and logcat `sos-key(ask) DOWN/UP` + `ask:` probes.
- **PTT key held ~1s** → Site-voice talk (talk cue + records); released → sends; the other device hears it. Quick PTT tap → nothing.
- Key Bindings screen: no SOS row; shows the two read-only hold-to-talk lines.
- Borrow-during-recording still works on the PTT key (site-voice), since that's the same SiteVoiceCore path.
