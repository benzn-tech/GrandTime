# Microphone health, and a key that answers when you press it

**Date:** 2026-08-10 (revised same day after review)
**Repos:** GrandTime (device) and fieldsight-pipeline (server)
**Origin:** the 2026-08-10 P0 investigation, plus what the reproduction session turned up.

---

## What we are fixing, and what we already know

### 1. The microphone delivers silence and nothing notices

On 2026-08-10 two sessions on `Ben_UCPK2` captured **112 seconds of digital silence** —
zero samples, full-size files, contiguous indices.

The evidence chain, all measured:

- The files **on the device** are **byte-identical** to the ones in S3. Upload, S3, VAD
  and transcription are ruled out — the zeros were written at capture time.
- VAD sidecars are 1:1 with chunks (49/49), so nothing was lost in transit either.
- `AudioRecord.read()` returns a **positive length with an all-zero buffer** when the
  input is denied. `AudioRecorder`'s read loop treats any `n > 0` as good data:

  ```kotlin
  n > 0  -> out.write(b, 0, n)      // never looks at the content
  n == 0 -> Unit
  else   -> { captureFailed = true; running = false }
  ```

  So `captureFailed` never fires, the session runs to its normal end, and every
  artifact downstream looks healthy.
- Server-side it is indistinguishable from a quiet room: the chunk is dropped as
  `no_speech_dropped`, exactly like a chunk nobody spoke in.

**Call it what it is: `silence_delivered`, not `dead_mic`.** Zeros are how the framework
reports *any* denied capture — the Android 12+ privacy toggle, another app winning
arbitration, a ROM concurrency policy. "The microphone died" is one cause among several
and naming the finding after it would pre-commit the diagnosis.

**Why it stops is still unknown.** Six trials on 0.6.0 — baseline ×2, SP-Ask, photo,
screen off, double-press record key, and a combined video trial — produced **32 clean
chunks and no reproduction**. Both real failures were on 0.5.9, before the 11:18 upgrade.
That is a correlation on two events from one morning on one device, not a diagnosis, and
not a population property.

This spec **does not fix the microphone**. It makes the fault visible so the next
occurrence is caught with its context. That is the prerequisite for a fix, not a substitute.

### 2. A key that gives no answer

SP-Ask and the Site-voice PTT have **no haptic feedback at all**. `CaptureCommand.Vibrate`
exists and is used throughout `CaptureCore`, but the two hold-to-talk paths never call it.
On a chest-worn body-cam, vibration is the only feedback that reliably arrives.

**Not a bug:** the operator long-pressed Ask while recording video and got no answer.
`AskCore.onPttDown` returns `PlayBusyCue` when `videoRecording` is true. It plays
`AskSounds.error()` — the *generic* error tone, so it says "something failed", not "not
while recording video".

**Measured:** during *audio* recording Ask and the recording appear to coexist. Trial 2
fired three `POST /api/ask/voice` calls at 16:54:24, 16:54:56 and 16:55:13 while the main
recording's chunks stayed clean (peaks −5.7 to −8.8 dBFS, no zero runs).

⚠️ **That inference is one-sided and contradicted in-repo.** Only the *main recording* was
checked. Nobody verified the three Ask clips actually contained the spoken questions — a
silenced Ask stream still produces a 200 response, and an agent replying "I didn't hear
anything" looks identical in an access log. And `SiteVoiceManager.kt:55-57` states the
opposite as a hardware fact: "the two AudioRecords must never hold the physical mic at
once (single-capture ROM)". **One of the two is wrong.** Section A resolves it with data
rather than picking a side now.

---

## A — Silence detection

### Device side

Instrument **only `runSegmentedWorker`** — the main capture loop.

Not `runSingleFileWorker`: `AskRecorder` delegates to the same `AudioRecorder` with no
segment size (`AskRecorder.kt:71-73`), so the single-file loop *is* the Ask/Site-voice clip
recorder. Counting there would mingle clip silence into session totals.

Check each `n > 0` buffer for being entirely zero, and track the **longest contiguous zero
run**, not a per-buffer or per-chunk boolean. Also record the **peak amplitude** per chunk:
exact-zero matches only the one fault class observed, and a microphone dying to a DC offset
or LSB dither would read as a quiet room. Peak costs nothing and catches the near-miss.

**Counting only — the recording must not stop.** Setting `captureFailed` here would flip
`running = false` and end the capture, turning "we lost a minute" into "we lost the
meeting".

**Counters cannot be `AudioRecorder` instance fields.** Pause/resume calls `stop()` then
`start()` again within one session, which would reset them. They live above it, scoped to
the capture session.

**Annotate, do not suppress.** Record whether Ask or Site-voice held the microphone during
each zero run, keyed on a **dedicated mic-held flag** — set when `StartRecording` executes,
cleared at `recorder.stop()`. **Not `AppState.askActive`**: that stays true until playback
completes (`AskManager.kt:84`), while the microphone is released at the top of `sendClip()`
(`AskManager.kt:96`). Suppressing on `askActive` would blind the detector for the whole
thinking-plus-playback window — tens of seconds — which is exactly when a real fault would
be misattributed.

Annotating rather than suppressing is also how the contradiction above gets settled: if
concurrent capture really is safe on this ROM, these annotated runs never appear.

Write a local log line when a run first crosses one buffer, carrying the chunk index and
the offset within it.

### Uplink

Add to the existing `POST /api/org/device/status` body:

```json
{ "oldestPendingAgeS": …, "pending": …,
  "silentSecondsS": …, "longestSilentRunS": …, "silentRunsWithMicBorrowed": … }
```

**This is not free, and the earlier draft's "no new endpoint, no new auth, no new
scheduling" undersold it.** `DeviceStatusWorker` is a periodic WorkManager job on an
hours-scale interval (`GrandTimeApp.kt:58`), possibly running in a cold process after the
counters' owner is gone — so the counters need **persistence**, not just memory. And
`device_status._VITALS` is a last-write-wins `UPDATE` on `devices`: a gauge. The next clean
report would erase the fault from the ledger. Store these **max-preserving** (or append a
row), never as a plain overwrite.

**The local log line is the only timely artifact.** Say so out loud rather than implying
the uplink is prompt.

### Server side, independently

`lambda_vad` classifies by **longest contiguous zero run** (threshold ≥ 5 s), recorded as a
sidecar field on **every** chunk — not only on the no-speech branch, so a chunk whose live
head contains speech is still measured.

A whole-chunk all-zero test would be wrong: chunk `09-21-23_…_c0003` is **97.6% zeros with
a 29.3 s run**, and its final 0.72 s is live audio. A boolean test returns false for it, so
the classifier would miss the onset chunk of every fault and the historical count would run
low. (The earlier draft called that chunk "digitally silent" while also asserting 480,000
zero samples per chunk — the two claims contradicted each other. The measurement, not the
prose, is right.)

The sidecar's `loudness_dbfs_before` **cannot** be used: that same chunk reports −41.8 dBFS.
Only the samples decide.

**Re-running history needs a standalone read-only scan**, not a `lambda_vad` re-run:
the lambda is S3-event-triggered with `SKIP_EXISTING=true`, and reprocessing re-uploads
segments and re-triggers Transcribe — cost plus duplicate downstream artifacts. Model it on
`scripts/missing_chunk_audit.py`, which already walks the bucket without touching the
pipeline.

Carry the media type on the finding. Silence on a video chunk may be a legitimate mic
handover (see B — **and note Site-voice already does this on `main` today**, so the
ambiguity is not new and is not B's alone).

---

## B — Ask borrows the microphone during video

**The earlier draft got this wrong in both directions** — it assumed video audio was
MediaRecorder-exclusive, and it warned that handing the microphone over would mean a
"materially larger change" to the capture pipeline. Neither holds.

- Video audio is a plain `AudioRecord(MIC)` feeding an AAC MediaCodec
  (`SegmentRecorder.kt:112-117`).
- **A microphone handover already exists and ships today**, used by Site-voice for exactly
  this case: `SiteVoiceCore.kt:61-63` sets `borrowedMic` and emits `AcquireMicFromCapture`
  → `CaptureManager.kt:170-171` sets `handoverActive` and calls
  `Camera2Pipeline.pauseSegmentAudio()` → `SegmentRecorder.pauseAudioForHandover()`, which
  releases the AudioRecord while the encoder loop zero-fills paced silence and the track
  stays alive.
- It even covers the segment rollover, which a naive implementation would break: a new
  segment builds a *fresh* AudioRecord and degrades to video-with-no-audio-track if that
  fails (`SegmentRecorder.kt:84`), so `CaptureManager.kt:411` starts a mid-handover segment
  with `startAudioPaused` and `:471` re-pauses on the roll.

**So B is: give `AskCore` the `borrowedMic` treatment `SiteVoiceCore` already has.** No
feasibility experiment is needed and no new mechanism is invented. Keep the
`siteVoiceActive` refusal — that is a real conflict between two voice features.

The video keeps recording and its audio track holds silence for the exchange. **A gap in
the recorded speech is accepted** — the operator's explicit decision.

**Consequence the earlier draft missed, and it needs a decision:** the microphone is
released at `recorder.stop()`, *before* the API call, so under a naive port the video
re-acquires it and **the agent's spoken answer is recorded into the evidence video**. Either
hold the borrow through playback (video silent for longer, answer not in the evidence), or
release early and accept the answer on the tape. **Hold through playback** is the default
here: an evidence recording that contains a synthesised voice answering a question is worse
than a longer gap, and it is the same reasoning that moved the "recording started"
announcement out of the recording in PR #16.

**Do not remove the `videoRecording` branch and stop there.** Android does not fail the
losing `AudioRecord` — it feeds it zeros. `AudioRecorder.start()` returns false only if
construction throws (`AudioRecorder.kt:44-66`), so "did `start()` succeed" would have
reported success while one of the two streams was silent. Any verification of B must check
the **content** of both the Ask clip and the video's audio track.

---

## C — Vibration that matches the vocabulary already on the device

The existing vocabulary, read off `CaptureCore`:

- `Vibrate(1)` — every normal action: record start, stop, pause, resume, end, photo
- `Vibrate(2)` — **refusal and failure only** (`:55, :59, :99, :103`, and `onFailure` `:153`)

An operator trained on this device already reads two buzzes as "refused". The earlier draft
assigned 2-short to "Ask finished successfully", which would have arrived unprompted
seconds after the press and read as a failure.

| When | Vibration |
|---|---|
| Ask activated — the hold registered, your question is being recorded | 1 short |
| Site-voice PTT pressed | 1 short |
| Ask finished — answer played, microphone back | **1 long** |
| Site-voice finished | **1 long** |
| Refused (Site-voice already active) | 2 short — the existing failure signal, now also felt |

A long buzz is distinguishable from one or two short ones through a closed pocket; three
short would not be reliably countable.

Adding the refusal case is free and closes the gap that started this: a refusal currently
signals by tone alone, which is the feedback channel we already know does not arrive.

**Two implementation notes the earlier draft glossed:**

- "Reuse `CaptureCommand.Vibrate`" is conceptual. `Vibrate` is executed inside
  `CaptureManager` (`:240`); `AskCommand` and `SiteVoiceCommand` have no vibrate variant and
  their managers hold no vibrator. Two new command variants plus executor plumbing in both
  managers — small, but real.
- `HoldToTalkGate` debounces at 1000 ms, so the activation buzz never fires on a brushed
  key — **but only on the hold path**. Keymap-routed `ASK_AGENT` taps reach `onDiscreteAsk`
  with no gate (`CoreService.kt:423-424`). The discrete path gets the same activation buzz;
  it has no hold to debounce, and a tap the operator meant is still worth confirming.

**Revision (2026-08-11) — where the activation buzz sits in the command list.**
Both cores now emit `Vibrate(SHORT)` **after** `StartRecording`, not before it. Both
executors short-circuit (`if (!recorder.start()) { fail(); return }`), so an activation buzz
ordered first is delivered for a talk that never began — the operator feels "accepted", then
either nothing or a refusal buzz behind it. That is the exact failure mode this phase exists
to remove, so ordering wins over the marginally snappier press-to-buzz latency. Pinned by
`accept_buzz_comes_after_start_recording` in both core test classes. Accepted cost: the buzz
now lands inside the first moments of the recording and may be faintly audible in the clip.

---

## Testing

**Pure logic, JVM-testable — the bulk of it:**

- longest-zero-run tracking: fully zero, partly zero, run spanning two buffers, single
  non-zero sample breaking a run, empty buffer
- peak amplitude alongside it
- counters surviving a pause/resume cycle within one session, and resetting between sessions
- the mic-held annotation: a zero run inside, partly overlapping, and outside a borrow window
- `AskCore` borrow: during video it emits the acquire command rather than `PlayBusyCue`;
  `siteVoiceActive` still refuses; the borrow is released at the right point for the
  hold-through-playback decision
- the command list for each hold-to-talk transition contains the right vibration, including
  the discrete path and the refusal case

Follow `AskCoreTest` and `AudioSegmentationTest`: pure functions, injected state, no Android.

**Server, against the repo's `FakeConn`/`FakeCursor` double:**

- `device_status.record()` stores the new vitals **max-preserving**, and treats an absent or
  unreadable value as absent rather than zero
- `lambda_vad` classifies by zero-run length: a 29.3 s run inside a 30 s chunk is
  `silence_delivered`; a merely quiet chunk is not

**Device-verified, no JVM test** (matching how `HoldToTalkKeySource` is handled): that the
vibrations are felt through a closed pocket, and that during a B borrow **both** the Ask
clip and the video audio track contain what they should.

**Regression fixtures — real bytes, already pulled off the device:**
`09-20-27` and `09-20-55` (fully zero), `09-21-23` (97.6% zeros, 29.3 s run, live tail),
`09-21-51` (the 2 s overlap sliver — must NOT be flagged), `09-19-57` and `08-25-08`
(clean). Verify each fixture's actual content before writing its expectation.

---

## Order

**C, then A, then B.** C is smallest, verifiable the moment it is on the device, and
settles the complaint that started this. A is the P0 work. B is well-scoped now but touches
the capture path, so it goes last and alone.

---

## What this spec does not claim

The microphone fault is **not fixed** and its cause is **not known**. Six trials failed to
reproduce it. Whether concurrent capture is safe on this ROM is **unsettled** — the
measurement and `SiteVoiceManager`'s own comment disagree, and section A is instrumented to
answer it rather than assume it. If the next occurrence is caught with its counters, its
peak, its zero-run and its borrow annotation, that is when a fix can be designed.
