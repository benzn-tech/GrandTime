# Audio capture: mic-array probe and routing decision

**Date:** 2026-08-08
**Status:** Design — revised after review
**Repo:** GrandTime (mobile). Device: SDJW-F2SP, unit `F2S202503103059`.

## Problem

Field recordings are unusable for transcription. Two 30 s prod chunks from
2026-08-07 (`sid39ad6c92…`, `c0002`/`c0003`) measure:

| | c0002 (speech at 18–23 s) | c0003 (noise only) |
|---|---|---|
| Overall RMS | −35.7 dBFS | −38.3 dBFS |
| Energy below 300 Hz | 79 % | **90.5 %** |
| Energy 1–8 kHz | ~2 % | ~1 % |
| Peak | −9.4 dBFS | −13.6 dBFS |

The decisive number: in `c0002` the **speech window is −38.0 dBFS while the
noise-only windows are −34.6 dBFS**. Signal-to-noise ratio is **−3.4 dB** — the
rubbing noise is louder than the person talking.

Post-processing cannot fix this. A high-pass sweep on the real file:

| Cutoff | Speech | Noise | SNR | Speech lost |
|---|---|---|---|---|
| none | −38.0 | −34.6 | −3.4 dB | — |
| 200 Hz | −39.0 | −37.8 | −1.2 dB | −1.0 dB |
| 300 Hz | −39.5 | −40.0 | **+0.6 dB** | −1.5 dB |

Four dB, at the cost of 1.5 dB of speech, still landing at ~0 dB SNR. **The fix
must happen at capture time.**

The noise is a broad 104–130 Hz cluster with no harmonic structure — low-frequency
rumble. Speech energy above 1 kHz is ~1.5 %, where real close speech carries
substantial 1–4 kHz consonant energy, so the signal is heavily low-passed. Peak
−9.4 dBFS against RMS −35.7 dBFS means the 16-bit range is spent almost entirely
on friction transients.

This matches `fieldsight-recording-loudness-baseline` (device median −36.0 / −33.0
dBFS across two sessions, versus a normal −20…−12): it is the device, not the session.

## What the board actually does (measured 2026-08-08, no code)

Interrogated directly rather than trusting the datasheet. Everything below was read
off unit `F2S202503103059`.

### Two microphones, and the vendor documents their geometry

`/vendor/etc/audio_policy_configuration.xml` declares two input ports, confirmed at
runtime by `dumpsys media.audio_policy` with **populated addresses**:

```
Port ID 13  "Built-In Mic"       {AUDIO_DEVICE_IN_BUILTIN_MIC, @:bottom}
Port ID 14  "Built-In Back Mic"  {AUDIO_DEVICE_IN_BACK_MIC,    @:back}
    both: PCM_16_BIT, 8000 16000 32000 44100 48000, MONO + STEREO
```

`ro.hx_existence_two_mic = 1`. `/vendor/etc/audio_param/MicInfo_AudioParam.xml`
gives the physical layout:

| | `@:bottom` | `@:back` |
|---|---|---|
| geometric location | 0.5, 0.0, 0.5 | 0.5, 0.7, 0.0 |
| orientation | 0, −1, 0 → **points down** | 0, 0, −1 → **points at the wearer** |

**The rear microphone faces the body.** On a chest-worn camera that is aimed
directly at the fabric that produces the friction noise, which is independent
support for the original instinct behind this work. The front microphone is not
forward-facing either — it points down, off the bottom edge.

### The fitted microphone part may differ between units

`MicInfo` carries tuning for two different parts, and they are not close:

| Part | Sensitivity | Low-frequency response |
|---|---|---|
| `SPH1642HT5H_REV_B` | **−41 dBFS/Pa** | flat from 500 Hz, −1.25 dB @ 100 Hz |
| `SPK0641HT4H_1_Rev_A` | **−26 dBFS/Pa** | −1 dB @ 100 Hz, −7.5 dB @ 20 Hz |

Fifteen decibels of sensitivity between two BOM variants of the same board, with
the quieter part also having *less* low-frequency rejection. If the fleet mixes
parts, recording level and rumble susceptibility vary by unit, and the twenty-unit
monthly rotation in `fieldsight-device-ledger` would spread that variation across
sites invisibly. Which part is fitted is a runtime question, and the probe answers
it — for this unit, and for any other unit the probe is later run on.

### Noise suppression, AGC and echo cancellation are real, and bound to one source

`/vendor/etc/audio_effects.xml` loads `libaudiopreprocessing_mtk.so`;
`dumpsys media.audio_flinger` confirms all three are loaded and registered under the
standard type UUIDs, so `NoiseSuppressor.isAvailable()`,
`AutomaticGainControl.isAvailable()` and `AcousticEchoCanceler.isAvailable()` will
report true. The runtime descriptors identify them as **AOSP (WebRTC) preprocessing
shipped in a MediaTek-named library**, not MediaTek implementations — which matters,
because AOSP preprocessing historically supports 8/16/32 kHz only and may refuse
44 100 Hz.

They are bound to exactly one source:

```xml
<preprocess>
    <stream type="voice_communication">
        <apply effect="aec"/><apply effect="ns"/><apply effect="agc"/>
    </stream>
</preprocess>
```

`mic`, `camcorder` and `voice_recognition` have **no** effects attached.

**Tempering the expectation:** the MediaTek dual-mic noise-reduction tuning tables
(`RecordDMNR`, `VoIPDMNR`, `VoIPv2DMNR`) are present but **all zeros**. Whatever
`voice_communication` delivers on this board is most likely single-mic WebRTC
suppression, not a tuned beamformer. `voip_tx` listing both built-in mics as route
sources proves nothing to the contrary — `primary input` lists the identical set,
and route sources are alternative connectable devices, not simultaneous capture.

### We are already on the loudest gain table

`/vendor/etc/audio_param/RecordVol_AudioParam.xml` maps each record scene to an
uplink gain:

| Scene | `AudioSource` | param | **ul_gain** |
|---|---|---|---|
| `Sound recording` | `MIC` ← **what we use** | 0 | **34** |
| `Camera recording` | `CAMCORDER` | 2 | 20 |
| `VR` | `VOICE_RECOGNITION` | 2 | 20 |
| `VoicePerformance` | — | 0 | 34 |

This corrects two things. First, an earlier draft of this spec claimed
`VOICE_RECOGNITION` is equivalent to `MIC` on this board; that is false — they use
different gain tables. Second, and more usefully, the difference runs the *wrong*
way for us: `MIC` already carries the highest uplink gain the ROM offers, and
`CAMCORDER`/`VOICE_RECOGNITION` are 14 units below it.

**So the −36 dBFS deficit is not a gain-table setting we failed to select.** We are
already at the top of the table. The deficit is acoustic — obstruction, distance,
or a low-sensitivity mic part — which leaves AGC as the only remaining software
lever for level, and makes the fabric-over-the-mic hypothesis considerably stronger.

### What the current code does

Both capture sites hardcode the same thing, with no configuration, no fallback,
and no record of what they actually got:

- `capture/AudioRecorder.kt:48` — `AudioRecord(AudioSource.MIC, 16000, MONO, PCM_16, …)`
  (standalone recording; also reused by SP-Ask's `AskRecorder`)
- `capture/camera2/SegmentRecorder.kt:115` — same, at 44 100 Hz (the video audio track)

Grepping `app/src` for `NoiseSuppressor`, `AutomaticGainControl`,
`AcousticEchoCanceler`, `getMicrophones`, `getDevices`, `setPreferredDevice`: **zero
hits**. The only `AudioManager` use is `VolumeCycler` adjusting playback volume.

## Goal

Decide, from measurement rather than inference, which capture configuration this
board should ship — then change one constant.

**Non-goals.** No per-site or backend-driven audio configuration: there is one wear
position and one scenario today. No changes to upload, session, or chunking
behaviour. No backend changes. No rebuild of the video audio path beyond
establishing which configurations are reachable at 44.1 kHz.

## Design

### Single configuration point

`capture/AudioCaptureConfig.kt` (new, `main`) — an immutable description of how to
open a microphone:

```
data class AudioCaptureConfig(
    val source: Int,                       // MediaRecorder.AudioSource.*
    val sampleRate: Int,
    val bufferBytes: Int,                  // preserved per call site, see below
    val preferredMic: MicChoice? = null,   // FRONT (@:bottom) / BACK (@:back)
    val enableNs: Boolean = false,         // explicit AudioEffect attach
    val enableAgc: Boolean = false,
)
```

with `DEFAULT_STANDALONE` and `DEFAULT_VIDEO` reproducing today's behaviour exactly.

**"Byte-identical to today" requires care, because the two call sites are not
identical today.** `AudioRecorder.kt:47` sizes its buffer `max(minBuf, sampleRate*2)`
= 32 000 B; `SegmentRecorder.kt:114` uses `max(minBuf, 8192)`. Buffer size sets read
granularity, which drives segment-roll timing in `runSegmentedWorker` and the video
loop's read cadence — so `bufferBytes` is part of the config rather than left to
`AudioRecord.Builder`'s default, and the unit test asserts both defaults equal the
values the two sites compute today.

**`sampleRate` is not a free knob on the standalone path.** Three consumers assume
16 kHz and would fail silently if it changed: `WavHeader.riffWav` defaults the rate
and is called from `AudioAssembly.finish` without one, so every written header would
lie; `AudioSegmentation`'s `segmentBytesFor`/`overlapBytesFor` compute byte counts
from an assumed rate, so segment durations would shift; and `AudioRecoverer` reuses
`AudioAssembly.finish` for crash recovery. Until those three are threaded, the
standalone rate stays 16 kHz and the probe is the only caller that varies it — the
probe writes its own WAV headers from the config rather than going through
`AudioAssembly`.

### Selecting a physical microphone

Both built-in mics surface as `AudioDeviceInfo.TYPE_BUILTIN_MIC`, so type-based
selection would silently pick whichever came first. Selection is therefore **by
address**: enumerate `getDevices(GET_DEVICES_INPUTS)`, filter to `TYPE_BUILTIN_MIC`,
and match `getAddress()` against `"bottom"` (FRONT) and `"back"` (BACK) — both
verified populated on this unit.

`MicrophoneInfo.getPosition()` is recorded as diagnostics only and never gated on.
An earlier draft failed the take when position was unavailable; on a generic
`android.hardware.audio@7.0` HAL that is a likely outcome, and it would have
discarded exactly the two takes that answer which hole is covered.

### Report what was granted, not what was asked

`OpenedMic` carries the requested config alongside the observed reality: actual
sample rate, channel count and format; whether `setPreferredDevice` returned true;
`getRoutedDevice()` after `startRecording()`; `getActiveMicrophones()`; and whether
each **app-created** effect reported `enabled`.

Two honest limits, both of which shape the analysis rather than the code:

- **Effect engagement is not observable for `VOICE_COMMUNICATION`.** There is no
  public API to enumerate effects the policy auto-attached to a session, so
  `enabled` can only be reported for effects the app created itself. Whether NS/AGC
  actually ran on the array takes is answerable only acoustically.
- **A granted sample rate is not a captured sample rate.** `AudioRecord` either
  initializes at the requested rate or fails; it never reports a different one, and
  AudioFlinger resamples invisibly if the HAL captured at another rate. Since the
  `voip_tx` profile explicitly lists 44100, a `VOICE_COMMUNICATION` @44.1 k take
  will open and *look* healthy even if the HAL captured 16 k and upsampled. The only
  real test is spectral: energy above 8 kHz in the recorded file.

`capture/MicCapabilities.kt` (new, `main`) — a pure query returning a serialisable
snapshot: `getMicrophones()` (id, address, position, directionality, **and the part
identifier**, which is how the fitted-part question gets answered),
`getDevices(GET_DEVICES_INPUTS)`, and the three `isAvailable()` flags.

### The probe, isolated in the dev flavor

A **second launcher activity that exists only in the dev flavor** —
`app/src/dev/java/.../devprobe/AudioProbeActivity.kt` plus a dev-only manifest entry
labelled `AudioProbe`. Nothing in `app/src/main`'s UI, navigation or `MainActivity`
is touched, and the class is absent from the prod APK.

The operator picks a block (below) and presses one button; the probe runs every
configuration in sequence, 10 s each with a 3 s gap, showing the current
configuration and a live dBFS meter so the mic can be seen to be alive without
interaction between takes — posture must not change mid-run.

Output goes to a timestamped folder under `getExternalFilesDir()` (app-specific
storage needs no permission on API 33, unlike shared storage):
`probe_{block}_{NN}_{configName}_{rate}.wav`, a sibling `.json` per take with the
`OpenedMic` report and measured peak/RMS, and one `capabilities.json` per run.

**The probe writes local files only.** No Room rows, no WorkManager uploads, no
recordings API. The dev flavor points at the test stack, and pushing takes of
deliberate noise into the test lake would pollute real data — the same class of
mistake as `grandtime-dev-flavor-uploads-to-test`.

**Microphone contention.** Every input mixPort is `maxOpenCount="1"
maxActiveCount="1"`, so the probe must not run while the capture service holds the
mic. A second `AudioRecord` typically initializes fine and returns silence under
concurrent-capture policy, so `AudioRecord.state` cannot detect this. The probe
checks `AudioManager.getActiveRecordingConfigurations()` and the capture service's
own state, and refuses with a visible message.

### Configurations under test

| # | Config | Rate | Question |
|---|---|---|---|
| 1 | `MIC` | 16 k | Baseline — today's standalone path exactly |
| 2 | `VOICE_COMMUNICATION` | 16 k | NS + AGC + AEC as the HAL binds them |
| 3 | `MIC` + prefer `@:bottom` | 16 k | Is the default already the front mic? |
| 4 | `MIC` + prefer `@:back` | 16 k | What does the body-facing mic hear? |
| 5 | `MIC` + app-created NS + AGC | 16 k | Suppression without AEC and without the voip path |
| 6 | `CAMCORDER` | 16 k | **Model check** — predicted ~14 gain units quieter |
| 7 | `MIC` | 44.1 k | Baseline for the video path (today's `SegmentRecorder`) |
| 8 | `VOICE_COMMUNICATION` | 44.1 k | Is the processed path reachable for video at all? |
| 9 | `MIC` + app-created NS + AGC | 44.1 k | Expected to fail — AOSP preprocessing may reject 44.1 k |
| 10 | `MIC` (repeat of 1) | 16 k | **Stationarity control** |

Take 6 is not a candidate — the gain table predicts it will be quieter. It is
included because a prediction that can be checked cheaply is worth checking: if
`CAMCORDER` does not come out below `MIC`, the gain-table reading is wrong and every
conclusion drawn from it needs revisiting. `VOICE_RECOGNITION` shares `CAMCORDER`'s
table and is omitted as redundant.

Takes 9 and 10 are likewise expected to be uninformative-if-all-goes-well, and are
there for exactly that reason: 9 converts an inference about AOSP preprocessing at
44.1 k into a measurement, and 10 makes the whole run falsifiable.

Takes 7–9 exercise `AudioRecorder`, not `SegmentRecorder`, so they establish which
configurations the HAL grants at 44.1 kHz, not that the video muxer tolerates them.
Rebuilding the video audio track is follow-up work gated on this answer.

## Field protocol

Takes are sequential — two configurations cannot record the same moment — so
comparability depends entirely on reproducing the scene, at a resolution of a few dB.

**The speech must come from a loudspeaker**, because a person repeating a sentence
cannot hold level, distance and pace steady enough. Source:
`A_clean_speech_60s.wav` (the app's own recorded voice lines, concatenated and
loudness-normalised; −18.2 dBFS, no clipping, 60 s so any take is covered without
needing to be synchronised to the loop).

**Speech, friction and near-talk are separated into three blocks, not three windows
within a take.** An earlier draft measured all three inside one 15 s take while the
speaker played throughout — which made the "noise" window actually speech-plus-friction,
and made the counting phrase compete with the loop. Both of those corrupt the
criteria they feed. The speaker's state changes twice per run, between blocks:

| Block | Speaker | Operator | Yields |
|---|---|---|---|
| **S** — speech | Playing, 3 m, taped mark | Stands still | Speech level, bandwidth |
| **F** — friction | **Off** | Marches in place, rubs case and clothing | Noise floor |
| **N** — near talk | **Off** | Says "one two three four five six seven eight nine ten" | Near-talker level, AGC behaviour, ASR ground truth |

Every configuration runs in every block: 10 configurations × 3 blocks × 10 s ≈ 6
minutes of recording. **SNR for a configuration is its block-S speech-band level
minus its block-F noise level** — same configuration, no interference, no window
alignment.

Between takes and between blocks: do not change clothing, move the speaker, adjust
its volume, or shift the device. The app runs a whole block without operator input
for exactly this reason. The block label is written into every filename and metadata
record so a mislabelled block cannot be compared against the wrong baseline.

**The first 2 s of every take is discarded** before scoring — AGC needs time to
converge, and scoring its transient would penalise exactly the configurations being
evaluated.

A second run against `B_site_conversation_60s.wav` (the real 2026-08-07 conversation,
high-passed at 120 Hz and normalised) is held back as a **confirmation run for the
winning configuration only**. Its source is quiet and heavily low-passed to begin
with, so as a primary signal it would add variance rather than information; as a
final check on real material it is worth ten minutes.

## Decision criteria

Per configuration, from the blocks above, discarding each take's first 2 s:

- **SNR** = speech-band (300–3400 Hz) RMS in block S − speech-band RMS in block F.
- **Speech shape** = the ratio of speech-band to full-band energy in block S,
  compared against take 1. Comparing absolute speech-band RMS would conflate two
  different things: `VOICE_COMMUNICATION` runs AGC, which is *supposed* to change
  level, so a level comparison would fail a configuration that preserved speech
  perfectly at a different gain, and pass one where suppression carved out consonants
  and AGC pushed the level back up. Level is scored by SNR; shape is scored here.
- **Level and headroom** = overall RMS, peak, clipping fraction (samples > 0.98),
  from blocks S and N.
- **Intelligibility** = word error on block N's counting phrase, which has a known
  transcript, through the current prod ASR provider.

A configuration replaces the default only if **all** hold against take 1:

1. SNR improves by **≥ 6 dB**;
2. speech-band/full-band ratio drops by **< 3 dB** relative to take 1;
3. clipping fraction **< 0.1 %** in both blocks S and N;
4. the counting phrase transcribes no worse than baseline.

**The run is void** if take 10 does not reproduce take 1 within ~2 dB on both SNR
and level. Six sequential minutes of a human marching and rubbing "identically",
judged against a 6 dB threshold, makes scene drift the most likely source of a false
win; without this control the protocol is not falsifiable.

If no configuration clears the bar, that is a real result: the obstruction is
mechanical and the answer is wear position or hardware, not software. The take 3 vs
take 4 comparison distinguishes the cases and is worth running regardless of how the
processed configurations turn out.

Takes are 10 s deliberately. Measuring variance with short clips is the lesson from
`fieldsight-audio-too-quiet`, where long clips burned ten thousand ElevenLabs credits
to establish something a few seconds would have shown.

## Risks

- **The noise suppressor removes the distant speaker.** At −3.4 dB SNR a suppressor
  can classify the quiet talker as noise. Criterion 2 exists to catch this; it is
  the most likely way take 2 looks good and is actually worse.
- **The array may not exist in any useful sense.** DMNR tuning is all zeros, so
  `voice_communication` is likely single-mic WebRTC suppression. Expectations for
  take 2 should be set accordingly, and a modest result there is not a measurement
  failure.
- **AEC is bundled.** Choosing `voice_communication` takes all three effects; they
  cannot be unbundled through the source. Take 5 is the unbundled comparison.
- **AGC pumping.** Gain that chases a −38 dBFS speaker will also amplify the friction
  floor between utterances. Visible as level swings within a block-S take.
- **`setPreferredDevice` may be silently ignored**, especially on the voip path where
  the HAL owns mic selection. Mitigated by recording `routedDevice` — though
  `getActiveMicrophones()` is synthesized from the routed device on a generic HAL and
  will not reveal whether one or two mics feed the stream, so it is diagnostics only.
- **Front/back labels could be inverted** if the vendor's orientation convention
  differs from the reading above. The pair is still decisive: if the two differ
  markedly, one hole is obstructed, and the quieter, more low-frequency-dominated
  take is the covered one whatever it is labelled.
- **Site Voice handover rebuilds the mic mid-segment.** `SegmentRecorder`'s
  `pauseAudioForHandover()`/`resumeAudio()` calls `buildMic()` again. If
  `DEFAULT_VIDEO` ever carries effects or a preferred device, every handover would
  silently drop them unless resume also goes through `openMic` and reattaches. The
  implementation routes both paths through `openMic`.

## Rollout

Verification is measurement, not a green build: the analysis over all takes, with the
table published before anything changes.

Once a configuration clears all four criteria and the stationarity control holds,
prod adoption is a change to `AudioCaptureConfig.DEFAULT_STANDALONE` /
`DEFAULT_VIDEO` — one constant each, no new configuration surface. Ship a release
APK to one unit and re-measure loudness on the next real session against the
−36 dBFS baseline before rolling to the fleet.

If the winner applies to standalone recording but not to the video track — the likely
shape if takes 8 and 9 both fail — the two defaults diverge, and that divergence is
recorded here rather than discovered later as an inconsistency.

Separately, `capabilities.json` should be collected from more than one unit. If the
fleet mixes the two microphone parts, a 15 dB sensitivity spread is a fleet-management
fact that belongs in `fieldsight-device-ledger`, and it would mean per-unit recording
level is not a property of the software at all.

## Testing

- `AudioCaptureConfig` and the `OpenedMic` report shape are plain data. JVM unit
  tests assert the two defaults reproduce today's hardcoded source, rate **and
  buffer size** — that is the regression that matters most.
- `openMic` touches real hardware and has no JVM test, consistent with the rest of
  `capture/`. It is exercised by the probe on the device.
- `./gradlew testProdDebugUnitTest` stays green, and `assembleProdDebug` must not
  compile the probe activity — verified by building it and confirming the class is
  absent from the APK.
