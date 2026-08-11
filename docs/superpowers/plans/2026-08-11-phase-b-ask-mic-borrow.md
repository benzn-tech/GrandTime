# Phase B — Ask borrows the microphone during video

**Spec:** `docs/superpowers/specs/2026-08-10-mic-health-and-key-feedback-design.md` §B
**Base:** `origin/main` (`d52a286`, 0.6.4)
**Repo:** GrandTime only. **No backend contract changes** — nothing here can collide with the
pipeline/ui work running in other sessions tonight.

> **Revised 2026-08-11 after a Fable review of the first draft.** The review found three real
> holes, two of which are **pre-existing bugs that Phase B would amplify rather than cause**.
> That changes the shape of the work: it is now two PRs, and the first one is worth shipping
> even if B never lands.

---

## Why two PRs

The first draft treated "hold the borrow across the API call and playback" as a
self-contained change. It is not. Holding a resource across an async boundary only works if
that boundary is guaranteed to terminate — and on two counts it currently is not:

- **`AskPlayer` can never call back.** It has exactly three completion paths
  (`AskPlayer.kt:36-53`): normal completion, async error, setup throw. A MediaPlayer that
  stalls without erroring hits none of them, and there is no timeout anywhere in `ask/`.
  **Today** that already sticks `AppState.askActive` true (`AskManager.kt:87`), which makes
  Site-voice refuse forever (`SiteVoiceCore.kt:59`) and Ask ignore every press
  (`AskCore.kt:46`) until the service restarts. **With B** it also leaves `handoverActive`
  true, so every subsequent segment starts with `startAudioPaused = true`
  (`CaptureManager.kt:426`) — the rest of the recording is silent.
- **The handover's end-side has an unclosed rollover race.** The begin side was explicitly
  fixed (`CaptureManager.kt:481-486` re-asserts the pause once the new segment is live).
  There is no symmetric re-assert for end: `resumeSegmentAudio()` returns **true** when
  `segment` is null (`Camera2Pipeline.kt:312`), so a release landing inside `startSegment`'s
  async window is consumed against nothing, and the new segment stays silent for its whole
  length. Site-voice has this bug today; its exposure is a ≤15s key hold. B's exposure is
  record + up-to-60s API + unbounded playback.

Both are Site-voice's bugs first. Fixing them inside a Phase B PR would hide them, and would
make B look riskier than it is. They ship first, on their own.

---

# PR 1 — Close the two holds that cannot terminate

**Branch:** `fix/mic-handover-hardening` off `origin/main`.
Valuable with or without Phase B: it fixes a Site-voice hang and a Site-voice roll race.

## Task 1.1 — A playback watchdog

**Files:** `ask/AskCore.kt`, `ask/AskManager.kt`, `ask/AskCoreTest.kt`

`AskManager` arms a timer when it enters `Playing` and cancels it on the completion callback.
On expiry: `player.release()` (which drops the pending callback — `AskPlayer.kt:55`) then
`execute(core.onError())`, so the operator gets the refusal buzz rather than silence, and the
FSM returns to Idle.

- Bound it generously — this is a stuck-process guard, not a latency budget. `ASK_PLAY_CAP_MILLIS
  = 120_000`. An answer clip is seconds long; two minutes cannot fire on a healthy playback.
- Cancel it on **both** callback outcomes, and in `shutdown()`.
- Reuse `onError()` rather than adding a state — a timed-out playback IS an error, and it
  already does the right things (cancel cap timer, cue, double buzz, Idle).

Test: `AskCoreTest` cannot see a timer. The real assertion lives at manager level and is
enabled by Task 1.4's seams — pin that `a playback that never calls back still returns to Idle`.

## Task 1.2 — Close the end-side rollover race

**Files:** `capture/camera2/SegmentRecorder.kt`, `capture/CaptureManager.kt`

1. **Guard `resumeAudio()` first.** It currently rebuilds the mic whenever `audioEnabled`
   (`SegmentRecorder.kt:305-321`), with no check that a handover is actually in progress. Add
   `if (!audioHandover) return true` at the top. **Without this, step 2 builds a second
   `AudioRecord` and leaks the live one** — a worse bug than the one being fixed.
2. Then make the post-`startSegment` re-assert symmetric (`CaptureManager.kt:486`):
   `if (handoverActive) pipeline.pauseSegmentAudio() else pipeline.resumeSegmentAudio()`.

Tests (`SegmentRecorder` needs no device for this — the guard is a pure early return; if it is
not JVM-reachable, state that and cover it at review level instead of pretending):
`resuming when no handover is active is a no-op`, and that the no-op does not touch the mic.

## Task 1.3 — Remove the suspend tripwire on `MicHandover`

**Files:** `sitevoice/MicHandover.kt`, `capture/CaptureManager.kt`, `sitevoice/SiteVoiceManager.kt`

`SiteVoiceManager.kt:96-99` documents that the exclusion between Ask and Site-voice holds only
while `begin()` does not suspend before the active-flag is published. Neither implementation
suspends today, but the interface declares `suspend`, so the invariant is a comment with
nothing enforcing it. Drop `suspend` from `begin()`/`end()`. The compiler then holds the
invariant instead of a reviewer.

(The alternative — publishing `askActive`/`siteVoiceActive` before `execute()` — is a larger
behavioural change to two features and is not needed once the signature enforces it.)

## Task 1.4 — Make `AskManager` testable

**File:** `ask/AskManager.kt`

It constructs its recorder, player, sounds and haptics internally, so nothing about its
ordering can be asserted at JVM level. Add an `internal constructor` taking the collaborators,
exactly as `AskRecorder` already does (`AskRecorder.kt:16-20`), keeping the public constructor
as the production path. This is what makes Tasks 1.1 and 2.3 verifiable rather than reviewed.

---

# PR 2 — Phase B proper

**Branch:** `feat/ask-mic-borrow` off PR 1.

## What changes

`AskCore` stops refusing while a video recording is running and borrows the microphone the way
`SiteVoiceCore` already does, holding the borrow until the answer has finished playing.

The mechanism is not new and the scope is small — this was mis-estimated once and corrected by
a spec review:

- Video audio is a plain `AudioRecord` + AAC `MediaCodec` (`SegmentRecorder.kt:115`).
- The handover ships today: `SiteVoiceCore.borrowedMic` → `AcquireMicFromCapture` →
  `CaptureManager.begin()` (`:184`) → `pipeline.pauseSegmentAudio()` →
  `SegmentRecorder.pauseAudioForHandover()`.
- Steady-state rollover is inherited: `CaptureManager.kt:426` samples
  `startAudioPaused = handoverActive`, which is owned by CaptureManager and does not care who
  borrowed. **The end-side roll race is NOT inherited safely — that is why it is PR 1.**

## Task 2.1 — `AskCore` borrows instead of refusing

**Files:** `ask/AskCore.kt`, `ask/AskCoreTest.kt`

Add `AcquireMicFromCapture` / `ReleaseMicToCapture` command variants and a `borrowedMic` flag
recomputed on every accepted `onPttDown`.

| Transition | Commands |
|---|---|
| `onPttDown`, site-voice active | `PlayBusyCue`, `Vibrate(DOUBLE_SHORT)` — **unchanged**, a real conflict |
| `onPttDown`, accepted | `AcquireMicFromCapture` (only when `videoRecording`), `PlayListeningCue`, `StartRecording`, `Vibrate(SHORT)`, `ArmCapTimer` |
| `onPttUp` / `onCapReached` | unchanged — **no release**, the borrow is held |
| `onAnswer` | unchanged |
| `onPlaybackDone` | `ReleaseMicToCapture` (if borrowed), `Vibrate(LONG)` |
| `onError` | `CancelCapTimer`, `ReleaseMicToCapture` (if borrowed), `PlayErrorCue`, `Vibrate(DOUBLE_SHORT)` |

Two orderings are load-bearing and must be pinned by tests, not by reading:

- **Acquire precedes StartRecording** — the capture mic must be free before Ask opens its own.
  `SiteVoiceCoreTest:38-39` pins the same thing for its path.
- **Release precedes the LONG buzz** — the buzz means "finished, microphone back".

Tests:
```
down during video acquires the mic instead of refusing
acquire precedes start recording
down during site voice still refuses            (regression — the real conflict stays)
down with no video does not acquire
up does not release — the borrow is held through playback
cap reached does not release either
playback done releases before the long buzz
error releases the mic
error without a borrow emits no release
a borrow is released exactly once
re-entrant down while playing does not acquire again
a discrete tap during video acquires too        (onDiscreteAsk delegates; nothing pins it)
```

## Task 2.2 — Video started *during* an exchange

**Files:** `capture/CaptureManager.kt`

The borrow is decided at key-down. If Ask starts while idle and the operator then presses the
video key mid-exchange, the new segment opens a live mic and **the agent's answer is recorded
into the evidence video** — the exact outcome §B exists to prevent, one key press away.

Decision: **start the segment paused when an ask is in flight.** In `startVideoSegment`, sample
`startAudioPaused = handoverActive || askInFlight`, and have Ask's release path call `end()`
unconditionally (it already will) so the segment recovers when the answer finishes.

`askInFlight` must be the **physical-and-playback** window, i.e. `AppState.askActive` — here
that is the correct flag, unlike in `MicSilenceMonitor` where the physical hold was wanted.
Say so in the code, because the two flags now sit in the same file for opposite reasons.

Alternative considered and rejected: refuse the video key while an ask is in flight. Refusing a
recording is never the safe default on an evidence device.

## Task 2.3 — `AskManager` executes the two commands

**Files:** `ask/AskManager.kt`, `service/CoreService.kt`

- Constructor gains `micHandover: MicHandover` (the existing `sitevoice` interface;
  `CaptureManager` already implements it — `CaptureManager.kt:73`).
- Executor branches mirroring Site-voice's, probe wording included.
- **`fail()` ordering is load-bearing and must be pinned by a test**, not left to reading:
  `recorder.discard()` → cancel timer → `micHandover.end()` → `execute(core.onError())`.
  `end()` reopens the capture mic; on a single-capture ROM it must run *after* Ask's mic is
  freed, or `buildMic()` fails, `audioHandover` stays true while `handoverActive` is already
  false (`SegmentRecorder.kt:316-320`), and the segment is stuck silent with nothing left to
  recover it. `SiteVoiceManager.fail()` (`:249-253`) already encodes this order.
- `shutdown()` calls `micHandover.end()` unconditionally — and note why: it calls
  `player.release()`, which drops the pending `onDone` (`AskPlayer.kt:55`), so this is the
  **only** release on that path.
- `CoreService` passes the same `CaptureManager` instance it already gives `SiteVoiceManager`;
  it is constructed first (`:320` vs `:341`), so no ordering change.

Manager-level tests (enabled by Task 1.4): `fail discards the clip before ending the handover`,
`shutdown ends the handover`.

## Task 2.4 — Device verification

**Any verification that only checks `start()` return values is worthless here.** Android does
not fail the losing `AudioRecord` — it feeds it zeros — and `AudioRecorder.start()` returns
false only if construction throws. Check **content**, on both sides:

1. Video recording running. Press SOS, ask a question aloud, let the answer play.
2. Pull the video and the Ask clip.
3. `ffprobe`/decode the video's audio track: the exchange window is silent AND the track is
   **present and continuous** (a missing track means the handover broke the recorder).
4. The Ask clip contains the spoken question — not silence, not the room.
5. The answer is **not** audible in the video.
6. Roll a segment mid-exchange; the next segment is silent-but-alive and recovers after.
7. **Press End-video while the answer is playing**, then start a new video — confirm the new
   recording has live audio (i.e. no borrow leaked across).
8. Start a video *during* an ask (Task 2.2) and confirm the answer is not on the tape.
9. During a plain **audio** recording, press SOS and read the probe line — free data on the
   open question of concurrent capture.

⚠️ On (9): a run of this on 2026-08-11 12:23 showed the main recording completely clean
(`0s silent of 37s, peak 12644`) while Ask ran. **That is a one-sided measurement** — the Ask
clip was never checked and the "question" was silence, because adb cannot speak. It is not
evidence that concurrent capture is safe.

## Task 2.5 — Release

Bump `versionCode`/`versionName` together, build prod release, verify versionCode with aapt2
and the prod gateway across **every** `classesN.dex`, publish to `fieldsight-dev-apk/` with
README notes, install with `-r`, confirm `firstInstallTime` unchanged.

---

## What this does NOT fix

Ask during an **audio** recording is untouched. There is no refusal and no handover on that
path — `AudioRecorder` has no pause-for-handover, so both `AudioRecord`s run at once and
Android feeds zeros to whichever loses without failing it. Same mechanism as P0. Phase A's
`silentRunsWithMicBorrowed` is what would measure it; **B is not evidence either way.**

## Conflict surface

Device-only. No API shape change, no new endpoint, no migration. The `/device/status` fields
Phase A added are already on the device and inert server-side, and nothing here touches them.
Tonight's pipeline/ui deploys and this can land in either order.
