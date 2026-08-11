# Plan — pace handover silence against a monotonic cursor

**Spec:** `docs/superpowers/specs/2026-08-11-handover-silence-pacing-design.md` (revised after
Fable review). **Branch:** `fix/handover-silence-pacing` off `origin/main`.
Device-only; no backend contract. Cannot conflict with tonight's pipeline/ui deploys.

## Task 1 — `AudioPtsPacer`, a pure class

**Files:** `capture/camera2/AudioPtsPacer.kt`, `capture/camera2/AudioPtsPacerTest.kt`

All the arithmetic and the monotonicity rule, with no Android in it. This is the only part of
the change that can be tested at all, so it is where the logic goes.

```kotlin
class AudioPtsPacer(sampleRate: Int, samplesPerFrame: Int) {
    fun live(nowUs: Long): Long                 // clamp only
    fun silence(nowUs: Long): Pacing            // (ptsUs, sleepMs) — sleep BEFORE stamping
    fun onLiveAudio()                           // fill ended: drop the cursor
    fun eos(nowUs: Long): Long                  // clamp; EOS is stamped too
}
data class Pacing(val ptsUs: Long, val sleepMs: Long)
```

- Cursor kept as a **seed + frame count**, pts computed as
  `seedUs + frames * samplesPerFrame * 1_000_000L / sampleRate`. Never accumulate a truncated
  per-frame constant.
- Every returned pts goes through `max(candidate, lastPts + 1)`.
- `sleepMs` is `ceil` of the shortfall when the cursor is ahead of `nowUs`, else 0.

Tests:
```
payload sum equals the pts span                     (no holes — the bug itself)
pts strictly increases across silence -> live -> silence
a live frame after a future-stamped silence frame is still greater
no sleep is requested while behind the clock
catching up does not overshoot the clock
no drift over 100k frames                           (integer math, not 23219us)
eos is clamped like every other frame
seeding twice does not move time backwards
```

## Task 2 — Wire it into the loop

**File:** `capture/camera2/SegmentRecorder.kt`

- Move the pts decision **inside** the branch (it is currently taken above it).
- Live: `pts = pacer.live(nowUs)` and `pacer.onLiveAudio()`.
- Silence: `val p = pacer.silence(nowUs)`; **sleep `p.sleepMs` first**, then queue with
  `p.ptsUs`. `fillSilence` loses its `Thread.sleep` — it only fills the buffer now.
- EOS: `pacer.eos(nowUs)`.
- Update the companion-object comment and `fillSilence`'s kdoc; both currently describe the
  fixed-sleep design being removed.
- Leave `dequeueInputBuffer(10_000)` / `dequeueOutputBuffer(10_000)` alone — they are the load
  the pacer exists to absorb.

## Task 3 — Device verification

Record ~30s of video, trigger an Ask handover spanning a segment roll, pull both segments, and
per segment assert on `ffprobe -select_streams a -show_packets`:

- audio track present and non-empty in **every** segment — the monotonicity failure mode is a
  *missing* track, and a density-only check would miss it;
- no consecutive `pts_time` gap over ~24 ms;
- `packets * 1024 / 44100` within 1% of the declared audio duration.

Record segment 1's declared-audio-minus-video residual (1.1s before the fix) both times. If it
survives, it is a separate finding — do not fold it into this one.

## Task 4 — Release

Bump both version fields, build prod release, verify versionCode with aapt2 and the prod gateway
across every `classesN.dex`, publish, install with `-r`, confirm `firstInstallTime` unchanged.
