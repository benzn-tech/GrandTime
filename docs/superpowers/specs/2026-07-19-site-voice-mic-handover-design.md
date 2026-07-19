# Site Voice — Mic Handover During Video Recording (Design)

**Date:** 2026-07-19
**Feature branch:** `feature/site-voice` (extends the Site-voice walkie-talkie; HEAD 86398d3)
**Status:** Approved design → implementation plan next.

## Goal

While a video recording is active, pressing the SOS key must let the worker talk on
Site-voice by **temporarily borrowing the microphone from the capture pipeline**, then
returning it when the talk ends. The video keeps recording frames throughout; its audio
track stays **continuous but silent** for the handover window (option A, chosen by the
product owner over "pause the whole recording").

This inverts the current behaviour, where Site-voice **refuses** (busy cue) whenever a
video recording is active (`sitevoice/SiteVoiceCore.kt:45-58`, the `videoRecording` →
`PlayBusyCue` branch).

## Background: the microphone contention

Video recording and Site-voice recording open **two independent `AudioRecord` objects on
the same physical mic**, via two different code paths — Android allows only one to capture
reliably at a time:

- **Video:** `capture/camera2/SegmentRecorder` builds a raw inline `AudioRecord`
  (44.1 kHz mono, `SegmentRecorder.kt:86`) feeding an AAC `MediaCodec` → `MediaMuxer`.
  The read→encode→mux loop is a single thread, `audioLoop()` (`SegmentRecorder.kt:184-223`).
- **Site-voice:** `sitevoice/SiteVoiceManager` uses `AskRecorder` → `capture/AudioRecorder`
  (16 kHz mono, `AudioRecorder.kt:47`) feeding a WAV file.

The whole audio subsystem inside `SegmentRecorder` is **orthogonal to the camera session**
(`Camera2Pipeline.ensureSession()` never touches audio) — so the mic can be released and
reopened mid-segment without reconfiguring the camera / interrupting video.

## Mechanism

### The seam (silence injection)

`SegmentRecorder.kt:197` is the single line where mic PCM enters the pipeline:

```kotlin
val read = if (audioStopRequested) -1 else runCatching { ar.read(inBuf, inBuf.capacity()) }.getOrDefault(-1)
```

Add a **new, non-terminal** `@Volatile private var audioHandover = false` (distinct from
`audioStopRequested`, which permanently ends the track). When `audioHandover` is true, the
loop **does not read the real mic**; instead it zero-fills `inBuf` (same size) and queues it
with the existing `ptsUs` (`SegmentRecorder.kt:196`, `System.nanoTime()/1000`) via the
unchanged `queueInputBuffer` call. Net effect: the AAC track's sample stream stays literally
continuous, with silent content for the window — no muxer PTS gap, no `addTrack` churn.

**Hard invariant:** the AAC `audioCodec` and its `audioTrack` index MUST stay alive across
the handover (MediaMuxer forbids a second `addTrack` after `start()`). Only the
`AudioRecord` (the mic hardware handle) is stopped/released/reopened.

**Pacing:** in handover, there is no blocking `ar.read()` to pace the loop, so the zero-fill
branch MUST sleep ~one buffer-duration between fills (buffer frames ÷ 44100 s) so PTS
advances at the natural cadence and the encoder is not flooded with near-identical
timestamps.

### `SegmentRecorder` pause/resume API

Two new methods, both segment-scoped and idempotent (guarded like the existing
`stopped`/`muxerLock` code):

- `fun pauseAudioForHandover()` — set `audioHandover = true`, then `audioRecord?.stop()`
  + `audioRecord?.release()` + null the field. The loop is already zero-filling by the time
  the mic is released. Safe if audio was never enabled (no-op).
- `fun resumeAudio()` — build a fresh `AudioRecord` (same params as `setupAudio()`),
  `startRecording()`, assign the field, then set `audioHandover = false`. If the rebuild
  throws, leave `audioHandover = true` (track continues silent) and return failure — never
  crash the recording.

`audioLoop()` must reference the **field** `audioRecord` freshly each iteration (not a
captured local) and null-guard it, so a released-then-reopened mic is picked up and the
zero-fill path never touches a released handle.

### `start(..., startAudioPaused: Boolean = false)`

`SegmentRecorder.start()` gains a `startAudioPaused` parameter. When true, the segment starts
with `audioHandover = true` and does NOT open the real mic until `resumeAudio()` — this is
how a **segment rollover during an active handover** is handled (the new segment records
silent audio until the talk ends). Default false preserves current behaviour.

## Control surface & wiring

```
SiteVoiceManager  ──MicHandover──▶  CaptureManager.beginMicHandover()/endMicHandover()
     (SOS record)                        │
                                         ▼
                                   Camera2Pipeline (current SegmentRecorder)
                                         │
                                         ▼
                            SegmentRecorder.pauseAudioForHandover()/resumeAudio()
```

- **`MicHandover` interface** (new, in `sitevoice/`): `suspend fun begin(): Boolean` /
  `suspend fun end()`. Keeps `SiteVoiceManager` decoupled from `capture/`.
- **`CaptureManager`** implements the handover by delegating to the active `Camera2Pipeline`
  → `SegmentRecorder`. It tracks a `handoverActive` flag so a segment rollover while active
  starts the next segment with `startAudioPaused = true`. `begin()`/`end()` are no-ops (return
  success) when no video recording is active — Site-voice then just records normally, no
  handover needed.
- **`CoreService.startPipeline()`** wires the `CaptureManager`'s handover into
  `SiteVoiceManager` (both managers are already constructed here; they otherwise share only
  `AppState`). If `SITE_VOICE_ENABLED` is false, nothing changes.

## Arbitration (with I-1)

`SiteVoiceCore` (pure FSM, unit-tested) changes:

- **`onSosDown(videoRecording, askActive)`**: remove the `videoRecording` → `PlayBusyCue`
  branch. When idle and NOT `askActive`, proceed to Recording even if `videoRecording` is
  true; if `videoRecording` was true, set an internal `borrowedMic = true` and emit a new
  `AcquireMicFromCapture` command (before `StartRecording`).
- **`onSosUp()` / `onCapReached()`**: if `borrowedMic`, emit `ReleaseMicToCapture` (after
  `StopRecording`) and clear the flag.
- **Ask mutual exclusion (unchanged direction + I-1):** `onSosDown` still yields
  (`PlayBusyCue`) when `askActive` — Site-voice and Ask remain mutually exclusive (first-come
  wins). **I-1:** `AskManager.onPttDown` gains a `siteVoiceActive` check → busy cue when
  Site-voice is active (Ask yields to active Site-voice). Ask keeps its current "yield to
  video" behaviour (Ask does not borrow the mic — out of scope this cycle).

New commands: `AcquireMicFromCapture`, `ReleaseMicToCapture`. `SiteVoiceManager.execute()`
maps them to `micHandover.begin()` / `micHandover.end()`.

## Timing (SOS talk during video recording)

1. SOS down → `SiteVoiceCore` → `[AcquireMicFromCapture, PlayTalkStartCue, StartRecording, ArmCapTimer]`.
2. `AcquireMicFromCapture` → `micHandover.begin()` → `SegmentRecorder.pauseAudioForHandover()`
   (video audio goes silent, mic freed).
3. `StartRecording` → Site-voice `AudioRecord` opens (mic now free), records the talk.
4. SOS up (or 15 s cap) → `[CancelCapTimer, StopRecording, ReleaseMicToCapture, UploadAndSend]`.
5. `StopRecording` → Site-voice mic released. `ReleaseMicToCapture` → `micHandover.end()` →
   `SegmentRecorder.resumeAudio()` (mic reopened, real audio resumes).
6. `UploadAndSend` runs after the mic is returned (I/O only).

## Edge cases (must be handled)

| Case | Handling |
|---|---|
| Site-voice `recorder.start()` fails after borrowing | Immediately `micHandover.end()` (restore video audio) + error cue; FSM `onError`. |
| `resumeAudio()` rebuild fails | Leave audio silent for the rest of the segment, log, do NOT crash. |
| Segment rollover during handover | `CaptureManager.handoverActive` → new segment `start(startAudioPaused = true)`; `resumeAudio()` on `end()`. |
| Cap timer (15 s) reached mid-talk | Treated as SOS up: stop + `ReleaseMicToCapture`. |
| SOS while `askActive` | Busy cue (unchanged) — no handover. |
| No video recording at SOS down | `borrowedMic = false`; `begin()`/`end()` are no-ops; normal Site-voice recording. |
| `shutdown()` during handover | `CaptureManager.shutdown()` / segment stop tears down audio normally (`audioHandover` irrelevant once the codec ends). |

## Evidence semantics

The evidence video retains **continuous video and a continuous (silent-gapped) audio track**;
each SOS talk leaves a silent span (no current-scene audio) for its duration. Accepted by the
product owner. The Site-voice clip itself is never part of the evidence recording (separate
WAV, uploaded to the `voice/` prefix — data-isolation invariant unchanged).

## Testing strategy

- **Unit (JVM):** `SiteVoiceCore` — the arbitration inversion + `borrowedMic` acquire/release
  command sequences (down-during-video → Acquire; up → Release; cap → Release; askActive →
  busy; no-video → no Acquire). Extend `SiteVoiceCoreTest`.
- **Unit (JVM):** `AskCore`/`AskManager` — the I-1 `siteVoiceActive` yield.
- **Device (P2 pipeline, no JVM test):** `SegmentRecorder` pause/resume + silence pacing +
  segment-rollover-during-handover. Verified on-device in the combined two-device acceptance:
  record video on device A, press SOS mid-recording, confirm (a) device B hears the clip,
  (b) device A's saved MP4 has continuous video + a silent audio span exactly over the talk
  (via ffprobe/waveform), (c) real audio resumes after, (d) no capture crash, (e) a talk that
  straddles a segment boundary still restores.

## Non-goals

- Ask (PTT) borrowing the mic from video — Ask keeps "yield to video". (Future, if wanted.)
- Mixing the Site-voice talk INTO the evidence audio — explicitly not done (silence instead).
- Inbound playback arbitration during recording — unchanged (still auto-plays; separate open
  question, deferred by the product owner).
