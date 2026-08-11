# Silence during a mic handover is sparse, so evidence video drifts out of sync

**Found:** 2026-08-11, while verifying Phase B on the device. Not caused by Phase B — Site-voice
has used the same fill since the handover shipped.
**Repo:** GrandTime only. No backend involvement.
**Revised** after a Fable review of the first draft, which found the proposed fix could produce
non-monotonic timestamps and kill the audio track outright. See "The fix" — the invariant is
now the point of this document.

## The measurement

One video recording, one Ask that borrowed the mic across a segment roll (0.6.5):

| segment | video | audio **declared** | audio **decoded** | hole (declared − decoded) | handover |
|---|---|---|---|---|---|
| 1 | 28.9s | 30.0s | 25.1s | **4.9s** | ≈22.7s |
| 2 | 13.6s | 13.7s | 11.4s | **2.3s** | ≈5.2s |

Sparseness is *declared minus decoded*, not video minus decoded — the first draft mixed the
two and reported 3.8s. Against the handover window the deficits are **≈22%** (4.9/22.7) and
**≈44%** (2.3/5.2). Not a constant: it depends on how loaded the loop is.

**Unexplained residual: segment 1's audio is declared 1.1s LONGER than its video** (30.0 vs
28.9). The fix below does not address that and must not be claimed to. Candidates: the muxer
start gate (`maybeStartMuxer`) admitting the two tracks at different points, or the two EOS
paths in `stop()`. **Measure it before and after; if it survives the fix, it is a separate
finding, not a regression.**

## Why the track is sparse

`SegmentRecorder`'s audio loop stamps every input buffer with wall clock, **before** it decides
whether the payload will be live audio or silence:

```kotlin
val ptsUs = System.nanoTime() / 1000     // taken once, above the branch
...
val n = if (audioHandover || ar == null) fillSilence(inBuf) else ar.read(...)
ac.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
```

and the fill is a fixed one-AAC-frame chunk:

```kotlin
private const val SILENCE_CHUNK_BYTES = 2048                                   // 1024 samples
private const val SILENCE_SLEEP_MS = SILENCE_CHUNK_BYTES / 2 * 1000L / AUDIO_SAMPLE_RATE  // 23
```

1024 samples at 44.1 kHz is **23.22 ms** of audio and the loop sleeps **23 ms** — already ~1%
short from integer division. The real gap is bigger: each iteration also pays
`dequeueInputBuffer(10_000)` and `dequeueOutputBuffer(10_000)`. **Whenever an output buffer is
not ready, the loop waits the full 10 ms**, so a realistic iteration is ~33 ms carrying 23.22 ms
of payload — ~30% short, which is the measured range.

**The pts advances at wall-clock rate; the payload advances at a fixed rate. The difference is
the hole.**

Those two 10 ms timeouts are **normal load, not a defect** — the fix must absorb them, and an
implementer must not "optimise" them away to make the numbers look better.

The live path is fine: `AudioRecord.read()` blocks until it has data, so samples track elapsed
time by construction. **Only the fill is broken**, which is why this stayed invisible — it needs
a handover, and until Phase B the only handover was a ≤15s Site-voice key hold.

## Why it matters

1. **Evidence footage is A/V-desynced after any handover**, worse the longer the borrow — and
   Phase B's borrow spans record + API + playback.
2. **It made a verification unreadable.** While checking that Phase B keeps the agent's answer
   off the tape, the compressed silence shifted the timeline enough that a post-return sound
   *looked* like it fell inside the playback window. Any "what was recorded between t=a and
   t=b" reasoning about handover audio is currently unsound.

## The fix

Pace the fill against a **monotonic sample cursor**, not a fixed sleep — and hold one invariant
above everything else.

### The invariant: timestamps never go backwards

This is the whole reason the first draft was rejected, and it is not a detail of the
implementation. Written naively as *stamp → advance cursor → sleep*, a silence frame gets a pts
up to one frame (~23.2 ms) **ahead of the wall clock**. If `resumeAudio()` then succeeds, the
next live frame is stamped with the current wall clock — **earlier than the silence frame that
preceded it**.

`MediaMuxer` treats a backwards audio timestamp as a malformed track and **stops writing that
track**, and `writeSampleData` here is wrapped in `runCatching` — so the failure is **silent**.
The result would be a video with no audio at all: strictly worse than the sparse track being
fixed, and invisible until someone plays the file.

Three rules, all enforced at the queue point:

1. **Sleep first, stamp second.** A silence frame may only be stamped once the wall clock has
   reached its cursor, so no frame is ever stamped in the future.
2. **Clamp every frame.** `pts = max(chosenPts, lastQueuedPtsUs + 1)`, applied to live frames,
   silence frames, **and the EOS buffer** — which is also stamped with a bare wall clock today.
3. **The pts decision moves inside the branch.** It is currently taken above it, so live and
   silence cannot be stamped differently without moving it.

The reverse transition (live → silence) is safe on its own: the live pts is taken before
`read()` returns, so a cursor seeded from the clock afterwards is necessarily later. It still
goes through the clamp — one rule, no exceptions to reason about.

### The cursor

- Seed from `System.nanoTime()` when a fill begins, so silence starts where real audio stopped.
- Advance by **whole samples with integer arithmetic**: `pts = seedUs + frames * 1024 *
  1_000_000 / 44100`. Do **not** accumulate a per-frame microsecond constant — 23219 µs is
  itself a truncation of 23219.95, so adding it repeatedly drifts ~41 ppm, which is the same
  class of mistake this document exists to fix.
- Reset when the fill stops, so the next fill re-seeds from the clock.
- Use `System.nanoTime()` throughout, never `currentTimeMillis` — a clock adjustment mid-record
  must not move audio timestamps.

### The interleaved case

`read() <= 0` falls back to `fillSilence` on the **live** path, so a single silence frame can sit
between two live reads. There the cursor is seeded and reset every iteration. That is expected,
not a bug — and it is exactly why the clamp, not the cursor, is what guarantees monotonicity.

### Rejected alternatives

- **Drop the sleep entirely.** Unpaced, the loop floods the encoder with silence as fast as it
  can dequeue, burning CPU through every handover. (It would not run away unboundedly — the
  10 ms input timeout and the small AAC input pool throttle it — but that is a side effect to
  rely on, not a design.)
- **Switch the pts to a pure sample counter.** Dense track, but it drifts free of the video's
  wall-clock base: a sparse track traded for a slowly desyncing one.

### Comments that become lies

The companion-object note ("Real-time paced: one frame of silence per frame-duration") and
`fillSilence`'s kdoc both describe the fixed-sleep design being removed. Update them in the same
change, or the next reader implements the bug again from the comment.

## Acceptance

**JVM:** extract the cursor arithmetic into a pure class — injected clock in, `(pts, sleepMs)`
out — and assert: payload sum equals pts span; pts is strictly increasing across a
silence→live→silence sequence; no sleep is requested while behind the clock; no drift over
10⁵ frames. `SegmentRecorder` itself cannot be unit-tested (`MediaCodec`, `MediaMuxer` and
`AudioRecord` are constructed in-class with no seams), and pretending otherwise is worse than
saying so.

**Device — this is the assertion that would have caught the bug, so it is the one that must
pass.** Record ~30s, trigger a handover that spans a segment roll, pull the files, then on each
segment's audio packets (`ffprobe -select_streams a -show_packets`):

- no gap between consecutive `pts_time` exceeds ~24 ms;
- `packets × 1024 / 44100` equals the declared audio duration within 1%;
- the audio track is present and non-empty in **every** segment (the monotonicity failure mode
  is a missing track, so a check that only measures density would miss it entirely).

Record the 1.1s residual before and after.

## Scope

`SegmentRecorder` only. The transient `read() <= 0` path shares the fill and is fixed with it.

## What this does not address

`SILENCE_CHUNK_BYTES` assumes the AAC frame size and `AUDIO_SAMPLE_RATE` assumes 44.1 kHz. Both
are already assumed throughout this file; this change neither strengthens nor weakens that.
