# The live audio path loses samples — measure before touching it

**Found:** 2026-08-11, immediately after #24, which stopped masking it.
**Repo:** GrandTime only.
**Revised** after a Fable review demolished the first draft's mechanism using the draft's own
numbers. **This document no longer proposes a fix.** It proposes instrumentation, because three
plausible mechanisms remain and the cheap fix for one of them is a no-op for the other two.

## What is actually established

0.6.6, audio packet timestamps (`ffprobe -select_streams a -show_packets`):

| segment | handover | gaps > 24 ms | lost | max gap |
|---|---|---|---|---|
| 1, first 6s (live) | none yet | 8 | 0.85s | 372 ms |
| 1, during handover | 21.9s | **0** | 0 | — |
| 2, whole (live) | none | 17 | **1.40s of 13.9s** | 330 ms |

**Samples are genuinely lost — this is not a measurement artifact.** Two independent checks:

- 521 of 538 inter-packet gaps are **exactly 23.22 ms**, one AAC frame. Had the encoder simply
  stamped each 8-frame input batch with one timestamp, spacing would be ~186 ms throughout.
  It interpolates per frame, so the timeline is dense and the outliers are real discontinuities.
- Packet count is a hard number: 13.89 s of wall clock should yield ~598 packets; 539 arrived.
  `READ_BLOCKING` does not short-read, so ~1.37 s of microphone samples were **never read**.

## What is NOT established — the first draft was wrong

The draft blamed an 8-frames-in / 1-frame-out imbalance starving a 92.9 ms mic ring. **Its own
data refutes it:**

- Under a ring-overrun model, a stall of duration D loses `D − 93 ms` and the ring hands back the
  rest. Summed over the 17 gaps that predicts **≈0.54 s lost**. Measured: **1.37 s ≈ the entire
  gap sum.** The ring returned essentially nothing.
- If a backlog *had* been recovered, those frames would be stamped back-to-back and show up as a
  burst of near-zero gaps. **There is not one gap below 20 ms in either segment.**
- The blocking read drains the ring *continuously* while it blocks, so the 186 ms read is not a
  window in which nobody reads the mic. The real no-read window is only the per-iteration codec
  work, which is bounded by one 10 ms failed `dequeueInputBuffer` — it cannot produce a 330 ms
  stall.

Also wrong: treating "the handover window is clean" as evidence that one-frame-per-iteration
works. That comparison changes **three** things at once — frame count per iteration, timestamp
source (sample cursor vs wall clock), and whether a microphone is involved at all. A sample
cursor *absorbs* stalls by construction, so a clean handover mostly proves the cursor hides
them.

## RESOLVED 2026-08-11 by the instrumented build (0.6.7) — all three candidates were wrong

One 30s recording, no handover:

```
audio diag: reads=152 readFrames=1245184 hwFrames=1255236 lostFrames=10052
            slowReads=35 readMax=276ms readAvg=110ms
            muxWaitMax=10ms muxWaitTotal=68ms
            minBuf=3528 ring=8192 inCap=16384
```

and the same segment's audio packets: **1216 packets x 1024 = 1,245,184 samples — exactly
readFrames.**

| stage | audio |
|---|---|
| wall clock | 30.08s |
| **hardware delivered** (`hwFrames`) | **28.46s** |
| loop read | 28.24s |
| reached the file | **28.24s — identical to what was read** |

- **`muxerLock` is not the cause.** Max wait 10 ms, 68 ms total across 30 s. The 1.2 gaps/s vs
  1 IDR/s correlation was a coincidence.
- **The loop is not the cause.** It loses 0.23 s of what the hardware hands it, and **nothing at
  all** between read and file. The encoder and muxer are innocent.
- **The ring arithmetic was right but irrelevant** — `minBuf` is 3528, so the ring is the
  assumed 8192 bytes / 92.9 ms.

**The deficit is upstream: the hardware delivered 1.62 s less than wall clock.** The gap shape
agrees — 22 discrete stalls of 60–380 ms totalling ~1.7 s. During each the blocking read waited
and got nothing, because there was nothing.

**This kills every buffering, pacing and loop-balancing fix**, including the ones this document
first proposed: you cannot catch samples that were never produced.

### Next hypothesis (untested, do not implement blind)

`AUDIO_SAMPLE_RATE = 44100`. On MediaTek platforms the native capture rate is usually **48000**,
and asking for a non-native rate forces HAL resampling — a well-known source of exactly this
kind of periodic glitching. 28.24/30.08 = 93.9% is far too large for clock drift but is the
right shape for a resampler dropping blocks.

Testing it is a one-constant change, but it alters the recorded audio format, so it needs its
own measurement run and its own decision — **not a blind flip tonight.** The `audio diag` line
now makes the comparison a single recording.

## Three candidate mechanisms, none yet chosen (superseded — kept for the record)

1. **`muxerLock` contention.** `KEY_I_FRAME_INTERVAL = 1` means one large IDR per second, and
   the audio thread must take the same lock to write its packets. **17 gaps over 13.9 s ≈ 1.2/s
   — the keyframe rate.** Segment 1's live head: 8 gaps in 6 s. The correlation is the strongest
   single signal in the data and the first draft missed it entirely.
2. **Loss upstream of the ring** (AudioFlinger/HAL, routing churn). Fits "nothing was recovered"
   better than any in-app model.
3. **The loop imbalance after all**, if the ring is far smaller than assumed —
   `getMinBufferSize` has never been logged on this device, and the whole arithmetic rests on it.

## What to instrument (one build, one recording, decides all three)

Per segment, accumulated on the audio thread and emitted as **one probe line at `stop()`** —
never per read; `probe()` appends to a file and is not synchronised.

- `getMinBufferSize` actual value, the ring size actually used, and `inBuf.capacity()`
  (`KEY_MAX_INPUT_SIZE` is a floor, not a promise — the codec may allocate more).
- Read count, total bytes, **max and p95 wall time of `read()`**.
- **Max and total wall time blocked on `muxerLock`** from the audio thread — this is what
  separates candidate 1 from the rest.
- **`AudioRecord.getTimestamp()` framePosition versus samples actually read.** This is the
  decisive accounting: it says whether the hardware produced frames that never reached us
  (loss upstream or in the ring) or whether we read everything and lost it later.
- Both tracks' first written pts (see below).

## The 1.1 s residual — explained, and it is not a defect

Measured directly: **video's first packet is at 1.103 s while audio's is at 0.000 s**, and both
end at ~29.98 s (segment 2: 1.005 s). The audio track is not longer; **the video track starts
later**, because the camera and encoder take about a second to produce a first frame while audio
starts immediately.

Consequences, both worth knowing:

- It is **not** A/V desync — both tracks share one clock base. It is a ~1 s audio-only preamble.
- **"declared audio duration" is therefore the wrong denominator** for any loss percentage, and
  the first draft used it. Use wall-clock recording duration instead.

## Acceptance criteria, corrected

The first draft demanded "zero gaps over 24 ms". Against a 23.22 ms frame that is **0.78 ms of
margin**, and it is only met today because most timestamps are interpolated by the encoder
inside a batch. Any change that makes each packet's pts an independent wall-clock sample would
fail it while genuinely improving loss — the reviewer's phrase for this was a ruler bent enough
to kill a correct fix.

So:

- Gap threshold **≥ 2 frames (46.4 ms)**, not 24 ms.
- Primary measure is **payload deficit against wall-clock recording duration**:
  `packets × 1024 / 44100` versus the measured recording length — not against declared duration.
- Better still, the direct one: **`getTimestamp()` framePosition versus samples read**, which
  needs no ffprobe at all and cannot be confounded by encoder or muxer behaviour.
- Timestamps still strictly increasing, and every segment still has a non-empty audio track.

## What must not change

- **The timestamp invariant from #24.** A backwards timestamp makes `MediaMuxer` drop the whole
  audio track, silently.
- **The 10 ms dequeue timeouts.** They are load the pacer absorbs, not a defect to tune away.

## Explicitly deferred

- **Reading one frame per iteration.** Not until the instrumentation says the loop is the
  problem. It also systematically moves each frame's timestamp ~23 ms early (`nowUs` is sampled
  *before* the read) and turns catch-up bursts into a locally compressed timeline.
- **Enlarging the mic ring.** Not "cannot make things worse": with wall-clock stamping, a bigger
  ring means a bigger catch-up burst and more timeline compression after a stall. It is only
  safe alongside a timestamp change.
