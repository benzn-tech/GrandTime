# Audio mic-array probe — findings, and why this branch was not merged

**Date:** 2026-08-09
**Status:** Complete. Negative result. Branch deliberately left unmerged.
**Device:** SDJW-F2SP, unit `F2S202503103059` (fitted mic part `SPH1642HT5H_REV_B`, −41.0 dBFS/Pa)
**Reads with:** `2026-08-08-audio-array-probe-design.md` (spec), `2026-08-08-audio-array-probe.md` (plan)

## The question

Field recordings were unusable for transcription: measured on real prod audio, speech sat at
−38.0 dBFS while clothing-friction noise sat at −34.6 dBFS — a **negative** signal-to-noise ratio.
A high-pass sweep bought only ~4 dB, so the fix had to happen at capture time. This branch built a
probe to find which capture configuration to ship.

**Answer: none of them. No capture-side setting on this board improves transcription.**

## What was tested

Ten configurations — both physical microphones, four `AudioSource` values, platform NS/AGC
attached at the app level, at 16 kHz and 44.1 kHz — recorded across three acoustic blocks
(speech-only, friction-only, near-talker), then transcribed through the same provider the pipeline
uses (ElevenLabs `scribe_v2`, identical parameters to `lambda_transcribe`).

### 1. Capture level does not affect transcription

A 30 s real prod chunk was attenuated, **re-quantised to int16 at the quiet level**, then loudness-
normalised back to −16.7 dBFS — reproducing what a quieter capture would suffer:

| Captured at | Effective bits | Words | Similarity to control |
|---|---|---|---|
| −16.7 dBFS | 16.0 | 77 | — |
| −16.7 (round-trip control) | 16.0 | 77 | 0.9677 |
| −28.7 | 14.0 | 77 | **1.0000** |
| −42.3 | 11.7 | 77 | **1.0000** |
| −54.0 | 9.8 | 76 | 0.9355 |

The round-trip control is bit-identical to the source yet still differs by one word, so the ASR
carries its own ±1-word noise. At −54 dBFS — the quietest chunk of a real conversation — the
damage sits inside that noise. **The "quiet capture loses resolution the backend cannot restore"
hypothesis is refuted.**

### 2. Noise suppression and AGC do not improve transcription

Ten takes of the identical 8 s source, each trimmed to exactly one loop period and loudness-
normalised the way prod does, scored by rotation-invariant word-hit F1 against the source
transcript:

| Group | n | Median | Range |
|---|---|---|---|
| Plain `MIC` variants | 6 | **0.836** | 0.800 – 0.871 |
| With NS/AGC (`VOICE_COMMUNICATION` or app-attached) | 4 | **0.813** | 0.742 – 0.820 |

Fully overlapping, and the processed group is if anything slightly worse.

### 3. Friction noise does not make the ASR invent words

Ten friction-only takes, normalised to −16.7 dBFS (which amplifies the noise ~20 dB): **zero
hallucinated words in every configuration.** ElevenLabs labels them `[sound of rubbing]` /
`[sound of sawing]` / `[sound of footsteps]`. The failure mode recorded for AWS Transcribe
(inventing 10.7 % of words from silence) does not reproduce on this provider.

### 4. Noise suppression does not damage near speech

Ten near-talker takes counting one-to-ten: **10/10 numbers recovered in every configuration.**
(Take 1 scored 4/10 because the operator had not started counting — a timing artefact, not a
configuration effect.)

## The mistake that nearly shipped the opposite recommendation

The first pass scored block S with `difflib.SequenceMatcher`, an **order-sensitive** similarity. It
produced a clean story: the four NS/AGC takes separated completely above the six plain-MIC takes
(0.679–0.807 vs 0.545–0.667), a 1-in-210 arrangement under random assignment. A recommendation to
change `DEFAULT_STANDALONE` was already drafted on the strength of it.

The source was an 8 s loop and each take entered it at a different phase, so the transcripts were
**rotations of each other**. An order-sensitive metric charges a rotation as a quality difference.
Re-scoring with a rotation-invariant word-hit F1 reversed the result entirely (section 2 above).

**The general lesson: when comparing different recordings of identical content, ask what else your
metric responds to before you trust its ranking.** Every number in section 2 is rotation-invariant
for this reason.

## What this means

The device-side knobs are exhausted. The remaining constraints are acoustic, and
`MicInfo_AudioParam.xml` on this board names them: the front microphone points **down** off the
bottom edge (`orientation 0,−1,0`) and the rear one points **at the wearer** (`0,0,−1`). Improving
capture on this hardware means changing wear position or adding an external lapel microphone — not
changing software.

Separately, backend loudness normalisation (shipped 2026-08-08 by other work) already brings every
chunk into −16.7…−23.0 dBFS, so level is no longer an argument for anything on the device side.

## Why the branch is kept

The feature is not worth shipping; the instrument is worth keeping. The probe answers a
capture-side question in about six minutes of device time plus one script run, instead of by
argument:

```
# install once
./gradlew assembleDevDebug && adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
# record: open AudioProbe, run blocks S / F / N
adb pull //sdcard/Android/data/com.benzn.grandtime.dev/files/audioprobe <dir>
python tools/audio_probe_analysis.py <blockS> <blockF> [<blockN>]
```

`AudioCaptureConfig` also stands on its own: the two capture sites now read their source, rate and
buffer floor from one place instead of hardcoding them separately, with unit tests pinning the
defaults to the values they used before. Nothing in the branch changes production behaviour.

## Protocol notes for whoever runs this next

- **Human-generated friction is not repeatable** — ten takes spanned 21 dB. Any criterion with it
  in the denominator will not resolve anything. The speaker-driven side reached 0.5 dB.
- **Make the playback loop period equal to the analysis window** (8 s here) so every take contains
  exactly one full period regardless of phase.
- **Set levels before recording, against the real operating point** (−38 dBFS speech / −35 dBFS
  friction). The first two runs were 15–20 dB too quiet and were wasted.
- The probe has no idle level meter; you cannot see dBFS until a block is running. Adding one would
  save an iteration.
- `setPreferredDevice` genuinely works here (`back` requested → `back` routed), but
  `getMicrophones()` lists only one microphone while `getDevices()` lists both — so physical
  selection must go by address.
- App-attached NS/AGC at 44.1 kHz reports `enabled=true` yet performs distinctly worse than at
  16 kHz. Another instance of the platform reporting healthy while not doing the work.
