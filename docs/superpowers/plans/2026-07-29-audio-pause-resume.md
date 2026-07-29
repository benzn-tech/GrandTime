# Audio Pause / Resume + End Implementation Plan

> Mirror of the shipped **video** Pause/Resume+End (see `CaptureCore` `RecordingVideo`/`PausedVideo`,
> `StopReason`, `KeyAction.END_VIDEO`, and `docs/.../2026-07-29-video-pause-resume-design.md`) applied
> to **audio**. Audio has no camera/GPS/watermark lifecycle — it's mic-only via `AudioRecorder`,
> so it's simpler; the one real subtlety is segment-index continuity across a pause.

**Goal:** Pause/Resume an audio recording (keeps the session open) + a deliberate End, on the physical
AUDIO key. New key scheme (state-dependent, like video's VIDEO key):

| AUDIO key | Idle | RecordingAudio | PausedAudio |
|---|---|---|---|
| **short** | Start recording | Pause | Resume |
| **long** | Adjust volume | End (intent=end) | End (intent=end) |

No screen UI (audio has no full-screen recording screen); control is the physical key + HomeScreen
status ("Recording audio mm:ss" / "Paused"). Volume moves from AUDIO-short to AUDIO-long-when-idle.

## Global constraints

- `session_open` fires only at audio session start (not on resume). `session_close` fires only on the
  transition to Idle: End → `intent="end"`; failures → `intent="idle"`. Pause → no close.
- Resume continues the SAME `sessionId` with the NEXT audio segment index (no S3 key collision).
- REC timer (HomeScreen) counts actual recording time — resume shifts the origin forward by the pause
  duration (same rule as video).
- No new deps. Build/test: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` then
  `./gradlew testProdDebugUnitTest` / `assembleDevDebug`. Dropbox first-run lock is transient, re-run.

---

### Task 1: CaptureCore audio state machine + tests (TDD)

**Files:** `CaptureState.kt`, `keymap/KeyAction.kt`, `capture/CaptureCore.kt`, test `CaptureCoreTest.kt`.

- `CaptureState.PausedAudio(val sessionId: String, val startedAtMillis: Long, val pausedAtMillis: Long)`.
- `KeyAction.END_AUDIO`.
- New `CaptureCommand`s: `PauseAudio`, `ResumeAudio(val sessionId: String)`, `EndAudio(val sessionId: String)`, `EndPausedAudio(val sessionId: String)`. Keep existing `StartAudio(sessionId)`, `CycleVolume`.
- `onAction(START_STOP_AUDIO)` when(state):
  - `Idle` → `RecordingAudio(newId(), clock())`; emit `StartAudio(session), Vibrate(1), Notify("Recording audio")`.
  - `RecordingAudio` → `PausedAudio(s.sessionId, s.startedAtMillis, clock())`; emit `PauseAudio, Vibrate(1), Notify("Paused")`.
  - `PausedAudio` → resume: `RecordingAudio(s.sessionId, s.startedAtMillis + (clock() - s.pausedAtMillis))` (shift origin forward by pause duration); emit `ResumeAudio(s.sessionId), Vibrate(1), Notify("Recording audio")`.
  - `RecordingVideo`/`PausedVideo` → `Vibrate(2), Notify("Stop video recording first")` (unchanged shape).
- `onAction(END_AUDIO)` when(state):
  - `Idle` → `listOf(CycleVolume)` (no state change).
  - `RecordingAudio` → `Idle`; emit `EndAudio(s.sessionId), Vibrate(1), Notify("Standing by")`.
  - `PausedAudio` → `Idle`; emit `EndPausedAudio(s.sessionId), Vibrate(1), Notify("Standing by")`.
  - else → `emptyList()`.
- Remove the old `RecordingAudio -> StopAudio` behavior from `START_STOP_AUDIO` (pause replaces it). `StopAudio` command can be deleted if nothing else uses it (check CaptureManager — the `CommandStopAudio` handler is replaced by Pause/End handling).
- Tests (mirror the video ones, deterministic `clock`): idle-short→RecordingAudio; recording-short→PauseAudio cmd + state PausedAudio; paused-short→ResumeAudio same session + RecordingAudio; idle-long→CycleVolume, state stays Idle; recording-long→EndAudio + Idle; paused-long→EndPausedAudio + Idle; a variable-clock test that resume shifts startedAt forward by the pause duration (timer excludes pause). Update/rename any pre-existing `START_STOP_AUDIO`/`ADJUST_VOLUME`/`StopAudio` tests that now assert obsolete behavior.

### Task 2: AudioRecorder segment-index continuity

**File:** `capture/AudioRecorder.kt`.
- Add `startIndex: Int = 1` param to `start(...)`; pass it into `runSegmentedWorker` so the worker's internal `var index = 1` becomes `var index = startIndex`.
- Expose the last index: change `private var lastSegmentIndex` to a public read (`var lastSegmentIndex: Int = 0; private set`) so CaptureManager can read it after `stop()` returns (stop() finalizes the last segment synchronously before returning) to compute the resume start index.

### Task 3: CaptureManager audio pause/resume/end wiring

**File:** `capture/CaptureManager.kt`.
- Add `END_AUDIO` to `handledActions`.
- Add field `private var audioResumeIndex: Int = 1`.
- `execute` command handling: replace the `StopAudio` case; add cases for `PauseAudio`, `ResumeAudio`, `EndAudio`, `EndPausedAudio`, and route `CycleVolume` (existing) — but note `CycleVolume` already has a handler (`probe("volume ${volume.cycle()}%")`); keep it.
- `startAudio(cmd)` (existing): pass `startIndex = 1` to `audio.start(...)` (segment 1 of the session) — keep `fireSessionOpen(kind="audio")` + `sounds.startRecording()`.
- New `pauseAudio()`: `val ok = audio.stop(); if (!ok) probe(...); audioResumeIndex = audio.lastSegmentIndex + 1; sounds.stopRecording()`. **No** `fireSessionClose`. (If a photo left the camera open, keep the existing `if (!pipeline.isRecording) pipeline.release()` cleanup.)
- New `resumeAudio(cmd: ResumeAudio)`: build a new first file + `audio.start(file, segBytes, overlap, nextFile, onSegment, startIndex = audioResumeIndex)` with the SAME `onSegment -> onAudioSegmentFinalized(seg, cmd.sessionId)`; `sounds.startRecording()`; **no** `fireSessionOpen`.
- New `endAudio(cmd: EndAudio)`: `audio.stop()` + `if (!pipeline.isRecording) pipeline.release()` + `sounds.stopRecording()` + `fireSessionClose(cmd.sessionId, now, "end")`.
- New `endPausedAudio(cmd: EndPausedAudio)`: no recorder to stop (already stopped on pause) + `if (!pipeline.isRecording) pipeline.release()` + `fireSessionClose(cmd.sessionId, now, "end")`.
- The command handlers call `core.state` transitions that CaptureCore already performed in `onAction` (state is set synchronously there), so these helpers just do the side effects. Delete the now-unused `stopAudio()` if fully replaced, or keep for the audio-during-shutdown path — check `shutdown()` (`if (audio.isRecording) audio.stop()`) still works (it calls `audio.stop()` directly, fine).

### Task 4: KeyMapping + HomeScreen

**Files:** `keymap/KeyMapping.kt`, `ui/HomeScreen.kt`, `ui/Labels.kt`.
- `KeyMapping.DEFAULTS`: `(HardKey.AUDIO to PressType.SHORT) to START_STOP_AUDIO` (unchanged), `(HardKey.AUDIO to PressType.LONG) to KeyAction.END_AUDIO` (was `START_STOP_AUDIO`).
- `HomeScreen.kt` `when (val s = capture)`: add `is CaptureState.PausedAudio -> MaterialTheme.colorScheme.error to "Paused"`.
- `Labels.kt` `actionLabel`: add `KeyAction.END_AUDIO -> "End audio / volume"`; change `KeyAction.START_STOP_AUDIO -> "Start/stop audio"` to `-> "Start / pause audio"`.

## Device acceptance (controller-run)

AUDIO short (start) → `session OPEN kind=audio`; wait; AUDIO short (pause) → last audio segment saved, NO close, HomeScreen "Paused"; AUDIO short (resume) → new audio segment with the NEXT index (chunk `_c000{N+1}`), no re-open; AUDIO long (end) → `session CLOSE intent=end`. Also AUDIO long while Idle → volume cycles. Confirm the resumed segments carry the same `_sid` and a monotonic `_c` (no collision).
