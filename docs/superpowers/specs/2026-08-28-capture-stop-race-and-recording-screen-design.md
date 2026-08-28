# A stop pressed during a start is discarded, and an audio session never takes the screen

**Found:** 2026-08-28, from a field report on a test unit running 0.6.11 (versionCode 25):
"press to start, press again and it takes 5–10 seconds to pause; hold it and it does not stop".
**Repo:** GrandTime only for everything shipped here. One item — the speaker count — needs a
`fieldsight-pipeline` change that has **not** been made; see "Backend contract".
**Branch:** `claude/mobile-filming-delay-bug-61bkyj`. **Ships as** 0.7.1 (versionCode 27).

Two unrelated defects, filed together because the field report contained both: one is a race
that eats key presses, the other is that the only screen answering "is this still recording?"
was never shown for audio.

---

## Part 1 — The dropped stop

### The report, and what was wrong with the first reading of it

The first diagnosis said the recording "never stops". That is too strong and the operator's own
account is more accurate: **the stop is discarded, and the session then stops only if a later
press lands after the recorder exists.** From the outside that looks like a 5–10 second delay,
or like a long-press that did nothing. The distinction matters because "never stops" sends an
investigator looking for a hang, and there is no hang.

### The window

`CaptureCore` commits to `RecordingVideo` **synchronously**, in the same call that reads the key
(`CaptureCore.kt:38`). The recorder behind that decision does not exist for seconds:

| step | cost | source |
|---|---|---|
| `settingsStore.settings.first()` | ~0 | `CaptureManager.startVideoSegment` |
| `sounds.startRecordingAndAwait()` | **1.4s** (`recording_started.wav`), up to 3.0s on `ANNOUNCE_TIMEOUT_MS` | `CaptureSounds.kt` |
| `pipeline.startSegment` → `ensureSession` → `openCamera` + `createCaptureSession` + GL start | **device-dependent, seconds on a slow unit** | `Camera2Pipeline.kt:195` |
| `segment = recorder` — the first moment a stop can land | | `Camera2Pipeline.kt:218` |

The announcement was added in `a1136d6` (0.5.9, "Say recording started before the microphone is
live"). Before it, the window was narrow enough not to be hit. **This is why the defect appears
on one test unit and not on others: the race is unconditional in the code, but the window's
width is a property of how fast that device opens its camera.** Do not close this as a
device-specific fault.

### Why the press is lost rather than early

Three facts have to line up, and they do:

1. `handle()` launched **every key press into its own coroutine** on `lifecycleScope`. The class
   comment claimed "全部在 scope 串行"; that stopped being true the moment the start path grew
   suspension points. A second press runs straight through the middle of the first.
2. `Camera2Pipeline.stopSegment()` opens with `val rec = segment ?: return` — with no live
   segment it is a **silent no-op**, and no `onFinalized` callback is ever scheduled.
3. **Neither `START_STOP_VIDEO` nor `END_VIDEO` changes the state itself while recording.** Both
   return only `StopVideo(reason)` and leave the transition to that callback
   (`CaptureCore.kt:45`, `:64`).

So the press is not deferred, it is gone, and the machine stays in `RecordingVideo` with the key
dead and the camera rolling. The next rollover overwrites `pendingStopReason` with `ROLLOVER`
and recording simply continues.

### The fix

One `Mutex` (`sessionLock`) over the actions that open or close a session, so a press landing
mid-startup **waits and then applies**.

**Invariants, in the order they matter:**

- **The lock covers only the session lifecycle** — `START_STOP_VIDEO`, `END_VIDEO`,
  `START_STOP_AUDIO`, `END_AUDIO`. `TAKE_PHOTO`, `TOGGLE_TORCH` and `ADJUST_VOLUME` stay outside
  it. A photo capture can take 3s (`prepareForPhoto`'s 400ms 3A settle plus a 3s shutter
  timeout); holding the lock across one would make the stop key unresponsive again, by a
  different route.
- **`execute()` must never take the lock.** It is re-entrant: `startVideoSegment`
  (`CaptureManager.kt:569`), `takePhoto` (`:698`) and `startAudio` all call it back on their failure
  paths. A non-reentrant `Mutex` in there deadlocks exactly the path that has to still work —
  the camera being unavailable.
- **The rollover timer, the `onFinalized` callback and `onCameraLost` take the same lock.** A
  rollover opens the next segment, which is the same window once per segment rather than once
  per session — a fix aimed only at the first start would leave that behind. Taking it also
  means each re-reads `core.state` at the moment it runs rather than the moment it was
  scheduled, which is why a rollover that loses the race to a pause now correctly does nothing.
- **No lock holder waits on the `onFinalized` callback.** `stopSegment()` returns immediately and
  hands the drain to a teardown thread. This is what makes taking the lock inside that callback
  safe, and it must stay true.
- `Camera2Pipeline.release()` clears `onFinalizedCb` without invoking it, so it cannot re-enter
  the lock either.

A stop that still finds nothing to stop now writes `StopVideo(<reason>) with no live segment` to
the probe log. That line should be unreachable; it exists so that if it ever happens again it
reads as a defect rather than as a broken key.

### What this does NOT fix

- **The ~2s latency itself.** The press is now honoured, not instant. Making it instant means
  interrupting the announcement when a stop is queued, which touches the contract
  `AnnounceBeforeRecordTest` pins (the announcement must finish before the microphone is live).
  Not attempted without a device.
- **The teardown window.** Between `stopSegment()` and its callback (~the encoder drain,
  100–500ms) a second press is absorbed rather than queued. Pre-existing, much narrower, not the
  reported symptom.
- **A hang inside camera startup.** `ensureSession` awaits `stDeferred` with no timeout. If the
  GL thread dies, that await now blocks the key path too, where before it blocked only itself.
  Pre-existing and never observed; adding a cancellation path into Camera2 startup untested is
  the more dangerous change.

### How to confirm it on the device

Start a recording and read the probe log for the gap between the key press and
`video segment 1 started: …`. **That gap is this device's window width** and is the number worth
carrying into any deeper analysis. Then press pause at 0.5s, 1.0s and 3.0s and long-press at the
same three points: `StopVideo(...) with no live segment` must not appear, and each press must
produce its transition.

---

## Part 2 — The recording screen

### The problem

The device is worn on a chest. The screen gets woken for about two seconds and is not read. In
0.6.11 that glance was served well for video (a full-screen preview) and not at all for audio:
`MainActivity` navigated to `Screen.RECORDING` only for `RecordingVideo`/`PausedVideo`, so an
audio session left the operator on Home, where "recording" was a line of body text on one of
five cards.

### What the screen has to answer, in order

1. **Is it still recording?** Site, a large elapsed timer, a lit status line
   (`RECORDING AUDIO` / `RECORDING VIDEO` / `PAUSED`). The timer **freezes when paused** — a
   paused device must not read as a running one.
2. **How do I stop it?** A 120dp circular button wired to end-and-upload, hittable without
   aiming. Pause is a secondary control: ending is the action people need in a hurry.
3. **Is it hearing the room?** A microphone meter (audio) and the speaker count (both).

### Decisions, and why

- **One screen, both kinds.** Navigation is now "any non-Idle capture state". The cost, accepted:
  Files and Settings are unreachable during an audio recording, exactly as they already were
  during a video one.
- **No mark-highlight button.** Nobody checks a chest-mounted screen often enough for one to
  carry meaning. Explicitly dropped from the mock.
- **No photo button.** The physical key already does this well; a second shutter is a second way
  to fire it by accident. The existing key behaviour is untouched.
- **The meter is never animated on its own.** This is the load-bearing rule of the whole screen.
  On this ROM a refused capture returns a **positive** read length and a buffer of zeros
  (`MicSilenceMonitor`'s reason for existing), so an idling animation would hide the one fault
  that is otherwise invisible until someone reads a transcript. The level comes from
  `MicSilenceMonitor.onLevel`, a **peak** over ~100ms of real samples — a peak and not an average
  so that digital zero stays exactly zero and a quiet room does not. Flat bars mean flat audio,
  and after 5s of digital silence the screen says "No sound reaching the microphone".
  Bars keep a 4dp floor: "nothing is arriving" and "there is no meter" must not look the same.
- **No meter on the video path.** A video session builds no `MicSilenceMonitor`, and its liveness
  proof is the camera preview. A meter there would need a tap into `SegmentRecorder`'s ring and
  would be showing invented data until it had one.
- **Local dark palette.** The rest of the app is the light FieldSight theme; this is the one
  surface that has to be legible at night, on a chest, in two seconds.
- **English copy**, per the repo rule, even though the mock that prompted it is in Chinese.

### The speaker count

It rides back on the chunk-upload `complete` response — the same passenger channel `groupEnded`
uses, because a body-worn device holds no connection open.

**Tagged with its session.** Chunks from a finished recording keep uploading while the next one
records; an untagged count would put the previous session's speakers under the current session's
clock. `AppState.SessionSpeakers(sessionId, count)`, and the screen renders it only when the id
matches.

**Absent and zero are different, all the way to the screen.**

| server says | client parses | screen shows |
|---|---|---|
| no `speakers` field | `null` | nothing |
| `"speakers": 0` | `0` | `· 0 speakers` |
| `"speakers": 3` | `3` | `· 3 speakers` |
| `"speakers": -2` or non-numeric | `null` | nothing |
| unparseable body | `null` | nothing, and the upload verdict is untouched |

A zero is a finding — a microphone that is running and has confirmed nobody — and is worth
showing. An absent field is not a finding, and rendering it as zero would put a claim on screen
that the server never made.

### Backend contract — NOT YET IMPLEMENTED

`fieldsight-pipeline` does not send this field. Until it does, the client parses `null` and the
line does not appear; nothing else changes.

What the backend needs to add to the `POST /org/recordings/{id}/complete` response body:

```json
{ "speakers": 3 }
```

- An integer ≥ 0: distinct speakers confirmed **for the session this chunk belongs to**, from
  the batched diarisation, as of now. It is expected to climb as more chunks are processed.
- Omit the field when there is no answer yet. Do **not** send `0` to mean "not computed".
- It must never change the status code or any existing field. The client treats an unparseable
  body as "no answer" and keeps the upload's own verdict, and that must stay safe to rely on.

---

## Files

| file | part |
|---|---|
| `capture/CaptureManager.kt` | `sessionLock`, `runAction`, `onCameraLost` extracted, mic level + speaker reset |
| `capture/MicSilenceMonitor.kt` | `onLevel` callback, ~10Hz peak window |
| `core/AppState.kt` | `micLevel`, `sessionSpeakers` |
| `net/RecordingsApiClient.kt` | `parseSpeakers`, `CompleteResult.speakers` |
| `upload/UploadWorker.kt` | publishes the session-tagged count |
| `ui/RecordingScreen.kt` | rewritten; both kinds, big stop, meter, status line |
| `ui/MainActivity.kt` | navigation on any non-Idle state |

## Verification status

**Not built and not run in the session that wrote this** — the remote environment has no Android
SDK and `dl.google.com` is blocked by network policy, so AGP could not be resolved. Syntax was
checked by review only.

Before this reaches a device:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew testProdDebugUnitTest
```

Tests added: the swallowed pause and swallowed long-press end reproduced against a model of the
dispatch and shown to land once serialized, plus source-level pins that the lock is on the paths
that need it and off `execute()`; the level callback's rate, full-scale value, exact zero on
digital silence and non-zero on a quiet room, and that none of it disturbs the shipped silence
counters; the `speakers` field parsed, absent, zero, malformed and negative, and never rescuing
a failed `complete`; the session tagging and the singular/plural/absent rendering.

**Compose has no JVM coverage here.** The screen is 320×427dp and the layout was budgeted by
hand, not rendered. First thing to check on the device: the secondary button row is not pushed
off the bottom.
