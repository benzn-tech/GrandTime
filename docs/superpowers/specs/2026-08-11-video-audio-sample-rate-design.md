# Video audio is captured at 44.1 kHz and loses 5.7% of it; audio-only at 16 kHz loses none

**Follows:** `2026-08-11-live-audio-packet-loss-design.md`, which established that the loss
happens **before the samples reach the app** — the hardware delivered 28.46 s in a 30.08 s
recording, and everything it delivered reached the file intact.
**Repo:** GrandTime only.
**This is an experiment, not a fix.** The change is one constant; the point of this document is
the measurement that decides whether to keep it.

## The differential that makes this worth testing

Same device, same microphone, same evening:

| path | rate | wall clock | actually captured | deficit |
|---|---|---|---|---|
| audio-only session (`AudioRecorder`) | **16 kHz** | 18.22 s | 18 s | ~0 |
| audio-only session | **16 kHz** | 37.28 s | 37 s | ~0 |
| video's audio track (`SegmentRecorder`) | **44.1 kHz** | 30.08 s | **28.24 s** | **5.7%** |

(The 16 kHz figures are `MicSilenceMonitor`'s `recordedSecondsS`, which floors, so "18" against
18.22 s is lossless within one second of resolution — 5.7% loss would have shown as 17.)

**The only difference between the two paths is the requested sample rate.** Both are
`AudioSource.MIC`, mono, PCM 16-bit, both on the same hardware.

## Hypothesis

44.1 kHz is not this platform's native capture rate. MediaTek audio HALs are typically native
at **48 kHz**, and a non-native request forces resampling in the HAL. Periodic block drops are
the classic signature, and the numbers fit the shape: 28.24/30.08 = **93.9%**, far too large for
clock drift, delivered as **22 discrete stalls of 60–380 ms** rather than a uniform slow rate.

Why 16 kHz escapes it is not explained by this hypothesis and does not need to be for the
experiment to be decisive — 16 kHz is a common voice-path rate that HALs often handle through a
different, better-tested path. **If the 48 kHz run is also clean, the hypothesis is confirmed
regardless of why 16 kHz works.**

## The experiment

Change `SegmentRecorder.AUDIO_SAMPLE_RATE` from 44100 to 48000 and record. Nothing else: the
constant is the single source for the `AudioRecord`, the AAC format, and the slow-read
threshold, and `AudioPtsPacer` already takes the rate as a parameter, so the timestamp
arithmetic follows automatically.

**Run both rates on the same device, back to back, same scene, ~30 s of video, no handover**, and
compare the `audio diag` line shipped in #25.

### Decision rule, fixed in advance

Primary number: **`hwFrames / (wall clock × rate)`** — what fraction of real time the hardware
actually delivered. It is upstream of every app-side variable, which is exactly where the loss
was localised.

- **≥ 99% at 48 kHz** (vs 94.6% at 44.1 kHz): confirmed. Ship 48 kHz.
- **Still ~94–95%**: hypothesis dead. The rate is not the variable; revert and stop guessing —
  the next lever is the audio source or the platform, not another constant.
- **Anything between**: inconclusive, run it twice more before deciding. Do not round up.

Secondary checks on the 48 kHz run, all of which must also hold:

- `lostFrames` (hardware → read) stays small, i.e. the loop still keeps up at the higher rate —
  more samples per second is more work.
- Audio packets × 1024 still equals `readFrames`, i.e. nothing new is lost after the read.
- Timestamps still strictly increasing and every segment still has a non-empty audio track
  (the #24 invariant is not negotiable and a rate change touches the frame duration).
- A handover run still shows zero gaps inside the borrow window.

## Risk and blast radius

- **Video audio format changes to 48 kHz AAC.** Nothing downstream pins 44.1 kHz — the pipeline
  decodes with ffmpeg — but this must be confirmed, not assumed, before shipping. **Existing
  recordings are unaffected**; this only changes new video.
- **The audio-only path is untouched.** It stays at 16 kHz, and it is the path that carries
  meetings. This experiment cannot hurt the primary capture.
- **Reversible in one constant**, and the deciding measurement is one recording.
- **Do not leave a test build on the device.** If the experiment fails, reinstall the build from
  `main` before finishing, or the F2SP is left running something that exists on no branch — a
  mistake already made once tonight.

## Not in scope

Why the hardware stalls at all, and whether the same stalls affect other apps. Answering that
means platform-level work well beyond a constant.
