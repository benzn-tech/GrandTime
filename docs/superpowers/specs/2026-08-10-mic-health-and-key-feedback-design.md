# Microphone health, and a key that answers when you press it

**Date:** 2026-08-10
**Repos:** GrandTime (device) and fieldsight-pipeline (server)
**Origin:** the 2026-08-10 P0 investigation, plus what the reproduction session turned up.

---

## What we are fixing, and what we already know

### 1. The microphone dies and nothing notices

On 2026-08-10 two sessions on `Ben_UCPK2` captured **112 seconds of pure digital
silence** — 480,000 zero samples per chunk, full-size files, contiguous indices.

The evidence chain, all measured:

- The files **on the device** are **byte-identical** to the ones in S3. Upload, S3, VAD
  and transcription are ruled out — the zeros were written at capture time.
- VAD sidecars are 1:1 with chunks (49/49), so nothing was lost in transit either.
- `AudioRecord.read()` returns a **positive length with an all-zero buffer** when the
  input is muted. `AudioRecorder`'s read loop treats any `n > 0` as good data:

  ```kotlin
  n > 0  -> out.write(b, 0, n)      // never looks at the content
  n == 0 -> Unit
  else   -> { captureFailed = true; running = false }
  ```

  So `captureFailed` never fires, the session runs to its normal end, and every
  artifact downstream looks healthy.
- Server-side it is indistinguishable from a quiet room: the chunk is dropped as
  `no_speech_dropped`, exactly like a chunk nobody spoke in.

**The root cause of why the microphone stops is still unknown.** Six trials on 0.6.0 —
baseline ×2, SP-Ask, photo, screen off, double-press record key, and a combined video
trial — produced **32 clean chunks and no reproduction**. The two real failures were both
on 0.5.9, before the 11:18 upgrade; every session after it has been clean. That is a
correlation on two events, not a diagnosis.

This spec therefore **does not fix the microphone**. It makes the fault *visible*, so the
next occurrence is caught with its context instead of being reconstructed from S3 three
hours later. That is the prerequisite for a real fix, not a substitute for one.

### 2. A key that gives no answer

SP-Ask and the Site-voice PTT have **no haptic feedback at all**. `CaptureCommand.Vibrate`
exists and is used throughout `CaptureCore` for record start, record stop and photo — the
two hold-to-talk paths simply never call it.

On a body-cam worn on the chest, vibration is the only feedback that reliably arrives.
Without it, a long-press that did nothing and a long-press that worked feel identical, so
the key gets pressed again, and again, and then written off as broken.

**Related, and NOT a bug:** during the trial the operator long-pressed Ask while recording
video and got no answer. That refusal is deliberate — `AskCore.onPttDown` returns
`PlayBusyCue` when `videoRecording` is true, yielding the microphone to video. It does play
`AskSounds.error()`, but that is the *generic* error tone, so it says "something failed",
not "not while recording video". The operator could not tell the difference, which is the
same defect in a different costume.

**Measured, and worth keeping straight:** during *audio* recording, Ask and the recording
coexist happily. Trial 2 fired three `POST /api/ask/voice` calls at 16:54:24, 16:54:56 and
16:55:13 while the main recording's chunks stayed clean (peaks −5.7 to −8.8 dBFS, no zero
runs). The exclusivity problem is specific to video, whose Camera2/MediaRecorder path holds
the microphone differently.

---

## A — Dead-microphone detection

### Device side

In both read loops of `AudioRecorder` (`runSingleFileWorker` and `runSegmentedWorker`),
check each `n > 0` buffer for being entirely zero.

**Counting only. The recording must not stop.** Setting `captureFailed` here would flip
`running = false` and end the capture — turning "we lost a minute" into "we lost the
meeting". The loop keeps writing; we only keep score.

Per session, accumulate:

- total seconds of all-zero audio
- longest continuous all-zero run

and write a local log line when a run first crosses one buffer, carrying the chunk index
and the offset within it.

**Suppress while Ask holds the microphone.** `AppState.askActive` is already maintained by
`AskManager`. Zero-runs that overlap an active Ask window are expected under section B and
must not be counted, or B poisons the signal A exists to produce.

### Uplink

The device already posts backlog vitals to `POST /api/org/device/status`. Add two fields to
the same body — no new endpoint, no new auth, no new scheduling:

```json
{ "oldestPendingAgeS": …, "pending": …,
  "silentSecondsS": …, "longestSilentRunS": … }
```

`device_status.record()` reads them with the existing `_int_or_none` discipline (a value we
cannot read is absent, not zero) and writes two new `devices` columns.

### Server side, independently

`lambda_vad` performs the same all-zero check and records `vad_result: "dead_mic"` instead
of `no_speech_dropped`.

This half is deliberately **not** dependent on the device half. It ships without an app
release and can be re-run over history immediately, which is the only way to answer "how
long has this been happening" — a question the device-side counter cannot answer
retroactively.

The sidecar's own `loudness_dbfs_before` **cannot** be used for this: chunk
`09-21-23_…_c0003` reports −41.8 dBFS and is digitally silent. Only the samples decide.

Carry the media type on the finding. Under section B a *video* chunk may legitimately be
silent because Ask took the microphone, so a `dead_mic` on video reads as "possibly Ask",
while one on audio is unambiguous. The two populations do not otherwise overlap — every
real failure so far has been on audio.

---

## B — Ask takes the microphone during video

Today `AskCore.onPttDown` refuses when `videoRecording` is true. **Remove that condition**
and let Ask proceed; keep the `siteVoiceActive` refusal, which is a genuine conflict between
two voice features.

The video keeps recording. Its audio track holds silence for the duration of the exchange,
and the microphone returns when the agent has finished replying. **A gap in the recorded
speech is accepted** — the operator's explicit decision, taken with the trade-off below on
the table.

### The known cost, stated plainly

Those zeros are byte-identical to the P0 fault. The device-side counter sidesteps this via
`askActive`; the server-side detector cannot, so a video chunk flagged `dead_mic` will
sometimes be an Ask window. That ambiguity is confined to video, and is why the finding
carries its media type.

### The unverified premise — settle this before estimating

Camera2/MediaRecorder may hold the microphone **exclusively**, in which case deleting the
condition does not hand the microphone over: `recorder.start()` simply returns false, Ask
calls `fail()`, and the operator hears the error cue — no better than today.

**Run a device experiment first:** remove the condition, build, record video, long-press
Ask, and observe whether `AskRecorder.start()` succeeds.

- **Succeeds** → B is a one-line change plus tests.
- **Fails** → handing the microphone over means stopping the video's audio source, which
  is `GlRecordPipeline`/`SegmentRecorder` — the capture path P0 is about. That is a
  materially larger change and gets re-scoped, not absorbed.

Nothing else in this spec depends on B, so it can be settled last without blocking A or C.

---

## C — Two vibrations

Reuse the existing `CaptureCommand.Vibrate(n)` mechanism.

| When | Vibration |
|---|---|
| Ask activated — the hold registered, your question is being recorded | 1 short |
| Ask finished — the answer has played, the microphone is back | 2 short |
| Site-voice PTT pressed | 1 short |
| Site-voice PTT finished | 2 short |

A refusal gets **no** vibration and keeps the existing error tone. Two patterns, not three:
"did it start" and "is it over" are the questions a chest-worn device has to answer, and a
third pattern is one more thing to learn than it earns.

`HoldToTalkGate` already debounces at 1000 ms, so the start vibration fires on genuine
activation, never on a brushed key.

---

## Testing

**Pure logic, JVM-testable — the bulk of it:**

- all-zero buffer detection: fully zero, partly zero, single non-zero sample, empty
- the counters: total and longest-run across several chunks, and the reset at session start
- Ask-window suppression: a zero run inside, partly overlapping, and outside an Ask window
- `AskCore.onPttDown` during video now starts a recording rather than emitting `PlayBusyCue`,
  while `siteVoiceActive` still refuses
- the command list for each hold-to-talk transition contains the right `Vibrate`

Follow `AskCoreTest` and `AudioSegmentationTest`: pure functions, injected state, no Android.

**Server, against the repo's `FakeConn`/`FakeCursor` double:**

- `device_status.record()` stores both new vitals, and treats an absent or unreadable value
  as absent rather than zero
- `lambda_vad` classifies an all-zero chunk as `dead_mic` and a merely quiet one as
  `no_speech_dropped`

**Device-verified, no JVM test** (matching how `HoldToTalkKeySource` is already handled):
that the vibrations are actually felt through a closed pocket, and the section B experiment.

**Regression fixture:** `09-20-27` and `09-21-23` are already pulled off the device and are
known-dead. `09-19-57` and `08-25-08` are known-good. Use them as the detector's fixtures —
real failures beat synthesised ones, and these are the exact bytes that started this.

---

## Order

**C, then A, then B.**

C is the smallest, is verifiable the moment it is on the device, and settles the complaint
that started this. A is the P0 work. B is gated on its experiment and touches the capture
path, so it goes last and alone.

---

## What this spec does not claim

The microphone fault is **not fixed** and its cause is **not known**. Six trials failed to
reproduce it. If the next occurrence is caught by A with its counters and its log line, that
is the point at which a fix can be designed. Until then the honest status is: instrumented,
not repaired.
