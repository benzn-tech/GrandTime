# Video Pause / Resume + End — Design (2026-07-29)

**Status:** Design, for implementation. Adds a Pause/Resume capability to video recording plus a
deliberate End, wired to both the recording screen and the physical VIDEO key. Builds on the shipped
session lifecycle: P0-a chunk `_sid/_c` filenames, P0-b `session_open`/`session_close`, P0-c
`intent="end"`.

## Goal

Let an operator **pause** a video recording (mis-touch-safe, keeps the meeting/session alive) and
**resume** it later within the same session, and **end** the meeting deliberately (finalize + email
now). The physical VIDEO key drives it: **short-press = start / pause / resume**, **long-press =
End**. The recording screen shows **Pause/Resume** and **End meeting** buttons.

## Why this composes cleanly with what shipped

- **`session_open`** fires only on **segment 1** start → Resume (which starts segment N≥2) never
  re-opens; the session stays open across a pause.
- **`session_close`** fires only on the transition to **Idle** → Pause goes to a new `PausedVideo`
  state (not Idle), so no close fires while paused; End/stop go to Idle and close.
- **`intent="end"`** already exists (P0-c `endIntentPending`); the End action sets it so the close
  carries `end`.
- Chunk `_sid/_c` naming already keys off `sessionId` + `segmentIndex`; Resume just continues the
  same `sessionId` with `segmentIndex + 1`, so the backend groups pre- and post-pause chunks into one
  session automatically. **No backend change.**

## State machine (CaptureCore)

New state `PausedVideo(sessionId, segmentIndex, startedAtMillis)` — retains session identity + the
original session start (so the REC timer stays session-continuous, same rule as rollover).

| From | Action | To | Commands / effect |
|---|---|---|---|
| Idle | `START_STOP_VIDEO` (short) | RecordingVideo(newSid,1,now) | StartVideoSegment(sid,1) |
| RecordingVideo | `START_STOP_VIDEO` (short) | **PausedVideo(sid,seg,startedAt)** | StopVideo(reason=PAUSE) → finalize current segment, **no close** |
| PausedVideo | `START_STOP_VIDEO` (short) | RecordingVideo(sid,seg+1,startedAt) | StartVideoSegment(sid,seg+1) — resume, same session |
| RecordingVideo | `END_VIDEO` (long) | Idle | StopVideo(reason=END) → finalize + close(intent=end) |
| PausedVideo | `END_VIDEO` (long) | Idle | CloseSession(intent=end) — no active segment to finalize (already finalized on pause) |
| RecordingAudio | `START_STOP_VIDEO` | (unchanged) | notify "stop audio first" |
| Idle / RecordingAudio | `END_VIDEO` | (unchanged) | ignore |

Segment rollover (the ~1-min timer) is unchanged: `StopVideo(reason=ROLLOVER)` → RecordingVideo(seg+1).

`StopVideo` gains a `reason: StopReason` (ROLLOVER | PAUSE | END) replacing the current
`rollToNext: Boolean`. `onVideoFinalized(reason)` maps: ROLLOVER→RecordingVideo(seg+1),
PAUSE→PausedVideo, END→Idle. The existing session_close-on-Idle logic (CaptureManager) then fires
close only for END/failure — with `intent="end"` for END via the P0-c flag.

## Key mapping

`KeyMapping.DEFAULTS`: change `(VIDEO, LONG)` from `START_STOP_VIDEO` to a new **`END_VIDEO`** action.
`(VIDEO, SHORT)` stays `START_STOP_VIDEO` (now start/pause/resume by state). Physical long-press fires
at 1 s (PressTypeDetector, fires LONG immediately without waiting for release) — good for a decisive
End. Overrides map (KeyBindings screen) picks up the new action automatically.

## UI (RecordingScreen)

Replace the current `[Stop] [End meeting]` row. RecordingScreen is shown while state is
RecordingVideo **or** PausedVideo (MainActivity nav must include PausedVideo).

- **RecordingVideo**: `[Pause]` (secondary) + `[End meeting]` (error). Header: `REC mm:ss`.
- **PausedVideo**: `[Resume]` (primary) + `[End meeting]`. Header: `PAUSED` (static, no running timer).

Buttons drive the SAME action pipeline as the keys: Pause/Resume emit the VIDEO short-press
(`screenKeyEvents` VIDEO down/up); End emits VIDEO long-press (or sets `endIntentPending` + emits the
End action). The existing `onStop`→VIDEO-key path is the model.

## What does NOT change / out of scope

- **Audio** pause is out of scope (physical AUDIO key + RecordingAudio unchanged).
- **Plain idle-stop is removed** from the UI/keys: every deliberate finish is either Pause (stay
  open) or End (intent=end). The `intent="idle"` close now fires only from failure/shutdown paths.
- **No pause/resume backend call.** The session stays open because no close fires; chunks resume with
  the same `sessionId`. Known edge: a pause longer than the backend's inactivity window may let the
  backend finalize the session early; resume-chunks would then land on a closed session. Acceptable
  for v1 (pauses are short); a future keepalive (idempotent `session_open` on resume) can harden it.
- **Timer during pause**: `startedAtMillis` is preserved, but the paused screen shows a static
  `PAUSED` label rather than a running counter; resume continues the session wall-clock.

## Testing

- CaptureCore is pure + already has `CaptureCoreTest` — TDD the new state + transitions there
  (start→pause→resume→pause→end; long-press End from both Recording and Paused; audio-guard;
  rollover still works; segmentIndex continuity across pause).
- CaptureManager wiring, key map, and UI are device-verified (project convention): on device confirm
  pause finalizes+uploads a chunk with `_c000N` and NO `session_close`; resume continues same `_sid`
  with `_c000(N+1)`; End fires `close intent=end`; timer/labels correct.
