# Video Pause / Resume + End Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add Pause/Resume to video recording plus a deliberate End, on the physical VIDEO key (short = start/pause/resume, long = End) and the recording screen (Pause/Resume + End meeting buttons), keeping one session across the pause.

**Architecture:** A new `PausedVideo` state in the pure `CaptureCore` state machine; `StopVideo` carries a `StopReason` (ROLLOVER | PAUSE | END). Pause finalizes the current segment but stays open (no `session_close`); Resume starts the next segment under the SAME `sessionId`; End finalizes + closes with `intent="end"`. A new `END_VIDEO` KeyAction (VIDEO long-press) and a direct `AppState.screenCaptureActions` channel (recording-screen buttons) both feed the existing action pipeline. This supersedes P0-c's `endIntentPending` flag: End is now a first-class action, so the close intent is derived from `StopReason`.

**Tech Stack:** Kotlin, Android, Compose, JUnit4. No new dependencies. Design spec: `docs/superpowers/specs/2026-07-29-video-pause-resume-design.md`.

## Global Constraints

- Physical VIDEO **short** = START_STOP_VIDEO (Idle→start, Recording→pause, Paused→resume); VIDEO **long** = END_VIDEO.
- Pause: finalize current segment (chunk uploads), keep camera + session alive, go to `PausedVideo`, **fire no `session_close`**.
- Resume: start segment `segmentIndex + 1` under the same `sessionId` + same `startedAtMillis`.
- End: finalize (if a segment is live) + `session_close` with **`intent="end"`**; go Idle + release.
- `session_open` still fires ONLY at segment 1; `session_close` fires only on →Idle. Rollover unchanged.
- The `intent="idle"` close now fires only from failure/shutdown paths (no user idle-stop).
- Pause/Resume/End buttons and keys converge on `CaptureManager.handle(action)` — one pipeline.
- Audio recording (`RecordingAudio`) is unchanged; Pause is video-only.
- No new Gradle/native deps. Build/test: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` then `./gradlew testProdDebugUnitTest` / `assembleDevDebug`; a first-run Dropbox `Could not delete …build…` lock is transient, re-run once.

---

### Task 1: CaptureCore — PausedVideo state + StopReason + pause/resume/end (TDD)

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureState.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureCore.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/keymap/KeyAction.kt`
- Test: `app/src/test/java/com/benzn/grandtime/capture/CaptureCoreTest.kt`

**Interfaces (Produces — later tasks rely on these exactly):**
- `CaptureState.PausedVideo(val sessionId: String, val segmentIndex: Int, val startedAtMillis: Long)`
- `enum class StopReason { ROLLOVER, PAUSE, END }` (in CaptureCore.kt, same package)
- `CaptureCommand.StopVideo(val reason: StopReason)` (replaces `rollToNext: Boolean`)
- `CaptureCommand.EndPausedSession(val sessionId: String)` (new — Paused→End, no live segment)
- `KeyAction.END_VIDEO`
- `CaptureCore.onVideoFinalized(reason: StopReason)` (replaces `rollToNext: Boolean`)

- [ ] **Step 1: Write the failing tests**

Add to `CaptureCoreTest.kt` (match the file's existing construction of `CaptureCore` — it uses a fake clock and a fake `newId`; reuse that harness). Cover:

```kotlin
// helper in the test: build a core, drive it. Assume existing test harness exposes `core` with a
// deterministic clock() and newId()="sid". Adjust names to the file's existing helpers.

@Test fun short_press_while_recording_pauses_not_stops() {
    val core = newCore()                         // existing harness factory
    core.onAction(KeyAction.START_STOP_VIDEO)    // Idle -> RecordingVideo(seg 1)
    val cmds = core.onAction(KeyAction.START_STOP_VIDEO) // Recording -> pause
    assertTrue(cmds.any { it is CaptureCommand.StopVideo && it.reason == StopReason.PAUSE })
    // state becomes PausedVideo only after finalize:
    core.onVideoFinalized(StopReason.PAUSE)
    val s = core.state
    assertTrue(s is CaptureState.PausedVideo && s.segmentIndex == 1)
}

@Test fun short_press_while_paused_resumes_same_session_next_segment() {
    val core = newCore()
    core.onAction(KeyAction.START_STOP_VIDEO)              // Recording seg1
    core.onAction(KeyAction.START_STOP_VIDEO)              // -> pause cmd
    core.onVideoFinalized(StopReason.PAUSE)                // PausedVideo seg1
    val sid = (core.state as CaptureState.PausedVideo).sessionId
    val cmds = core.onAction(KeyAction.START_STOP_VIDEO)   // resume
    val start = cmds.filterIsInstance<CaptureCommand.StartVideoSegment>().single()
    assertEquals(sid, start.sessionId)                    // SAME session
    assertEquals(2, start.segmentIndex)                   // next segment
    assertTrue(core.state is CaptureState.RecordingVideo)
}

@Test fun long_press_while_recording_ends_with_end_reason() {
    val core = newCore()
    core.onAction(KeyAction.START_STOP_VIDEO)
    val cmds = core.onAction(KeyAction.END_VIDEO)
    assertTrue(cmds.any { it is CaptureCommand.StopVideo && it.reason == StopReason.END })
    core.onVideoFinalized(StopReason.END)
    assertTrue(core.state is CaptureState.Idle)
}

@Test fun long_press_while_paused_ends_session_directly() {
    val core = newCore()
    core.onAction(KeyAction.START_STOP_VIDEO)
    core.onAction(KeyAction.START_STOP_VIDEO)
    core.onVideoFinalized(StopReason.PAUSE)               // PausedVideo
    val sid = (core.state as CaptureState.PausedVideo).sessionId
    val cmds = core.onAction(KeyAction.END_VIDEO)
    assertTrue(cmds.any { it is CaptureCommand.EndPausedSession && it.sessionId == sid })
    assertTrue(core.state is CaptureState.Idle)
}

@Test fun segment_timer_rollover_still_advances_segment() {
    val core = newCore()
    core.onAction(KeyAction.START_STOP_VIDEO)
    core.onSegmentTimerFired()                            // StopVideo(ROLLOVER)
    core.onVideoFinalized(StopReason.ROLLOVER)
    val s = core.state
    assertTrue(s is CaptureState.RecordingVideo && s.segmentIndex == 2)
}

@Test fun startedAtMillis_preserved_through_pause_resume() {
    val core = newCore()                                  // clock starts at T0
    core.onAction(KeyAction.START_STOP_VIDEO)
    val t0 = (core.state as CaptureState.RecordingVideo).startedAtMillis
    core.onAction(KeyAction.START_STOP_VIDEO); core.onVideoFinalized(StopReason.PAUSE)
    assertEquals(t0, (core.state as CaptureState.PausedVideo).startedAtMillis)
    core.onAction(KeyAction.START_STOP_VIDEO)             // resume
    assertEquals(t0, (core.state as CaptureState.RecordingVideo).startedAtMillis)
}

@Test fun end_video_ignored_when_idle() {
    val core = newCore()
    assertTrue(core.onAction(KeyAction.END_VIDEO).isEmpty())
    assertTrue(core.state is CaptureState.Idle)
}
```

- [ ] **Step 2: Run tests, verify they fail**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.capture.CaptureCoreTest"` — expect compile failures (`PausedVideo`, `StopReason`, `END_VIDEO`, `EndPausedSession` unresolved).

- [ ] **Step 3: Implement**

`KeyAction.kt` — add `END_VIDEO` to the enum (after `START_STOP_VIDEO`).

`CaptureState.kt` — add:
```kotlin
    data class PausedVideo(val sessionId: String, val segmentIndex: Int, val startedAtMillis: Long) : CaptureState
```

`CaptureCore.kt`:
- Add `enum class StopReason { ROLLOVER, PAUSE, END }` at file top (below package/imports).
- Change `data class StopVideo(val rollToNext: Boolean)` → `data class StopVideo(val reason: StopReason)`.
- Add `data class EndPausedSession(val sessionId: String) : CaptureCommand`.
- `onAction(START_STOP_VIDEO)` — replace the RecordingVideo branch and add a PausedVideo branch:
```kotlin
        KeyAction.START_STOP_VIDEO -> when (val s = state) {
            is CaptureState.Idle -> {
                val session = newId()
                state = CaptureState.RecordingVideo(session, 1, clock())
                listOf(
                    CaptureCommand.StartVideoSegment(session, 1),
                    CaptureCommand.Vibrate(1),
                    CaptureCommand.Notify("Recording video"),
                )
            }
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(StopReason.PAUSE))
            is CaptureState.PausedVideo -> {
                val next = s.segmentIndex + 1
                state = CaptureState.RecordingVideo(s.sessionId, next, s.startedAtMillis)
                listOf(CaptureCommand.StartVideoSegment(s.sessionId, next), CaptureCommand.Vibrate(1), CaptureCommand.Notify("Recording video"))
            }
            is CaptureState.RecordingAudio -> listOf(
                CaptureCommand.Vibrate(2),
                CaptureCommand.Notify("Stop audio recording first"),
            )
        }
```
- Add an `END_VIDEO` branch to `onAction`:
```kotlin
        KeyAction.END_VIDEO -> when (val s = state) {
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(StopReason.END))
            is CaptureState.PausedVideo -> {
                state = CaptureState.Idle
                listOf(CaptureCommand.EndPausedSession(s.sessionId), CaptureCommand.Vibrate(1), CaptureCommand.Notify("Standing by"))
            }
            else -> emptyList()
        }
```
- `onSegmentTimerFired()`: `RecordingVideo -> listOf(CaptureCommand.StopVideo(StopReason.ROLLOVER))`.
- `onVideoFinalized(rollToNext: Boolean)` → `onVideoFinalized(reason: StopReason)`:
```kotlin
    fun onVideoFinalized(reason: StopReason): List<CaptureCommand> = when (val s = state) {
        is CaptureState.RecordingVideo -> when (reason) {
            StopReason.ROLLOVER -> {
                val next = s.segmentIndex + 1
                state = CaptureState.RecordingVideo(s.sessionId, next, s.startedAtMillis)
                listOf(CaptureCommand.StartVideoSegment(s.sessionId, next))
            }
            StopReason.PAUSE -> {
                state = CaptureState.PausedVideo(s.sessionId, s.segmentIndex, s.startedAtMillis)
                listOf(CaptureCommand.Vibrate(1), CaptureCommand.Notify("Paused"))
            }
            StopReason.END -> {
                state = CaptureState.Idle
                listOf(CaptureCommand.Vibrate(1), CaptureCommand.Notify("Standing by"))
            }
        }
        else -> emptyList()
    }
```

- [ ] **Step 4: Run tests, verify pass**

Run the CaptureCoreTest command from Step 2 — expect all green. Then run the FULL suite `./gradlew testProdDebugUnitTest`; it will FAIL to compile in CaptureManager (uses `StopVideo(rollToNext=…)` / `onVideoFinalized(Boolean)`) — that is expected and fixed in Task 2. So for THIS task's gate, only `CaptureCoreTest` must pass; note the CaptureManager breakage is Task 2's.

- [ ] **Step 5: Commit** (`git add` the four files; message `feat(capture): PausedVideo state + StopReason (pause/resume/end) in CaptureCore`).

---

### Task 2: CaptureManager — pause/resume/end wiring + session close intent by reason

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/core/AppState.kt` (remove `endIntentPending`; add `screenCaptureActions`)

**Interfaces:**
- Consumes Task 1's `StopReason`, `StopVideo(reason)`, `EndPausedSession`, `onVideoFinalized(reason)`, `PausedVideo`.
- Produces: `AppState.screenCaptureActions: MutableSharedFlow<KeyAction>` (Task 4 UI + Task 3 CoreService use it).

- [ ] **Step 1: AppState** — remove the `endIntentPending` flag (P0-c) and add the screen-action channel:
```kotlin
    /** Recording-screen buttons → service: a capture KeyAction to run directly (bypasses press-type
     *  detection, which can't express a long-press from a tap). CoreService dispatches to CaptureManager. */
    val screenCaptureActions = MutableSharedFlow<com.benzn.grandtime.keymap.KeyAction>(extraBufferCapacity = 8)
```

- [ ] **Step 2: CaptureManager** — thread `StopReason` and handle the new commands. Read the current `execute`, `startVideoSegment` (its `StopVideo` handling + `onFinalized` callback), `consumeEndIntent`/`fireSessionClose`, and the failure paths, and change:
  - Replace `pendingRoll: Boolean` with `pendingStopReason: StopReason = StopReason.ROLLOVER`.
  - `is CaptureCommand.StopVideo -> { segmentTimer?.cancel(); pendingStopReason = cmd.reason; pipeline.stopSegment(); true }`.
  - Add `is CaptureCommand.EndPausedSession -> { endPausedSession(cmd.sessionId); true }`.
  - In `startVideoSegment`'s `onFinalized` success branch, replace `val roll = pendingRoll; pendingRoll = false; execute(core.onVideoFinalized(roll))` with `val reason = pendingStopReason; pendingStopReason = StopReason.ROLLOVER; execute(core.onVideoFinalized(reason))`. The close now fires when the resulting state is Idle AND `reason == StopReason.END`:
    ```kotlin
    if (core.state is CaptureState.Idle) {
        sounds.stopRecording(); stopWatermarkTimer(); gps.stop(); pipeline.release()
        if (reason == StopReason.END && endingSessionId != null)
            fireSessionClose(endingSessionId, System.currentTimeMillis(), "end")
    }
    ```
    (Rollover keeps recording; PAUSE lands in PausedVideo — neither reaches this Idle branch, so no close. On PAUSE the finalize still runs, but do NOT release the pipeline or stop GPS — keep them for a fast resume; only `stopWatermarkTimer()` is fine to leave running or stop, match existing behavior by leaving the segment timer cancelled and watermark as-is.)
  - Remove `consumeEndIntent()` (P0-c). The three FAILURE paths keep `fireSessionClose(endingSessionId, System.currentTimeMillis())` (default `"idle"`) unchanged.
  - Add `private fun endPausedSession(sessionId: String)`: no live segment to finalize — fire close + release:
    ```kotlin
    private fun endPausedSession(sessionId: String) {
        stopWatermarkTimer(); gps.stop(); sounds.stopRecording()
        scope.launch { pipeline.release() }
        fireSessionClose(sessionId, System.currentTimeMillis(), "end")
    }
    ```
  - Add `END_VIDEO` to `handledActions`.

- [ ] **Step 3: Build + full suite.** `./gradlew testProdDebugUnitTest && ./gradlew assembleDevDebug` — both green (CaptureCoreTest from Task 1 + no regressions; CaptureManager now compiles against the new core API).

- [ ] **Step 4: Commit** (`feat(capture): pause keeps session open, resume continues it, end closes intent=end`).

---

### Task 3: Key mapping + CoreService dispatch of screen actions

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/keymap/KeyMapping.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/service/CoreService.kt`

- [ ] **Step 1: KeyMapping** — change the VIDEO long-press default:
```kotlin
        (HardKey.VIDEO to PressType.LONG) to KeyAction.END_VIDEO,
```
(Leave `(VIDEO, SHORT) → START_STOP_VIDEO`.)

- [ ] **Step 2: CoreService** — dispatch screen capture actions. Near the existing `AppState.screenKeyEvents.collect { … }` collector (line ~147), add another collector:
```kotlin
        scope.launch {
            AppState.screenCaptureActions.collect { action ->
                val mgr = captureManager
                if (mgr != null && action in mgr.handledActions) { bringAppToForeground(); mgr.handle(action) }
            }
        }
```
(Reads the same `captureManager` field `handleAction` uses. `bringAppToForeground()` is a no-op when already foreground — the recording screen is.)

- [ ] **Step 3: Build.** `./gradlew assembleDevDebug` — green. (No unit test; key map + service wiring are device-verified.)

- [ ] **Step 4: Commit** (`feat(keymap): VIDEO long-press = End; route recording-screen actions to capture`).

---

### Task 4: RecordingScreen UI — Pause/Resume + End; PausedVideo nav

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ui/RecordingScreen.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt`

- [ ] **Step 1: MainActivity nav** — include PausedVideo in the RECORDING screen. Replace the LaunchedEffect(capture) body condition:
```kotlin
        val recording = capture is CaptureState.RecordingVideo || capture is CaptureState.PausedVideo
        if (recording && screen != Screen.RECORDING) screen = Screen.RECORDING
        else if (!recording && screen == Screen.RECORDING) screen = Screen.HOME
```

- [ ] **Step 2: RecordingScreen** — read the paused flag + swap the button row. `RecordingScreen` already collects `AppState.captureState` as `capture`. Add:
```kotlin
    val paused = capture is CaptureState.PausedVideo
```
Header: show `PAUSED` when paused, else the running `REC mm:ss` (keep the existing REC row for the non-paused case; when `paused`, render a static `Text("PAUSED")` in its place). Replace the current `[Stop][End meeting]` Row (Task P0-c) with:
```kotlin
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { AppState.screenCaptureActions.tryEmit(KeyAction.START_STOP_VIDEO) },
                modifier = Modifier.height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) { Text(if (paused) "Resume" else "Pause") }
            Button(
                onClick = { AppState.screenCaptureActions.tryEmit(KeyAction.END_VIDEO) },
                modifier = Modifier.height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White,
                ),
            ) { Text("End meeting") }
        }
```
Add imports if missing: `com.benzn.grandtime.keymap.KeyAction`. The `onStop` param of RecordingScreen is now unused — drop it from the composable signature and from the MainActivity call site (`Screen.RECORDING -> RecordingScreen()`), OR keep it unused; prefer removing it and its `screenKeyEvents` emit in MainActivity to avoid dead code.

- [ ] **Step 3: Build + full suite.** `./gradlew testProdDebugUnitTest && ./gradlew assembleDevDebug` — green.

- [ ] **Step 4: Commit** (`feat(ui): Pause/Resume + End meeting buttons; PausedVideo screen`).

---

## Device acceptance (controller-run, after Task 4)

Record → **Pause** (short-press or button): confirm the segment finalizes + uploads (`_c0000`), screen shows `PAUSED`, and NO `session_close` fires. **Resume**: new segment same `_sid` with `_c0001`. **Pause again → End** (long-press or End button): `session_close intent=end ok=true`; app returns to Home. Separately, **Recording → End (long-press)**: finalize + `intent=end`. Confirm plain rollover across a >1-min recording still advances segments and the REC timer stays continuous.

## Notes / out of scope

- Audio pause; the idle-stop UX (removed — see spec). Long-pause-vs-backend-inactivity keepalive is a future hardening (spec §out-of-scope).
