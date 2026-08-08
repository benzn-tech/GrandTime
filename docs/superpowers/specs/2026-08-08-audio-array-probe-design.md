# Audio capture: mic-array probe and routing decision

**Date:** 2026-08-08
**Status:** Design — awaiting review
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

Three further observations point the same way:

- The noise is a broad 104–130 Hz cluster with no harmonic structure — low-frequency
  rumble (clothing friction, footsteps, wind), not hiss.
- Speech energy above 1 kHz is ~1.5 %. Real close speech carries substantial
  1–4 kHz consonant energy. The recording is heavily low-passed, the signature of
  a mic covered by fabric.
- Peak −9.4 dBFS against RMS −35.7 dBFS: the 16-bit range is spent almost
  entirely on friction transients. The person talking occupies the bottom few bits.

This is the same root condition already recorded in
`fieldsight-recording-loudness-baseline` (device median −36.0 / −33.0 dBFS across
two sessions, versus a normal −20…−12) — it is the device, not the session.

## What the ROM actually supports (measured 2026-08-08, no code)

Interrogating the device directly, rather than trusting the datasheet:

**Two physical microphones are declared.** `/vendor/etc/audio_policy_configuration.xml`:

```xml
<devicePort tagName="Built-In Mic"      type="AUDIO_DEVICE_IN_BUILTIN_MIC" role="source">
<devicePort tagName="Built-In Back Mic" type="AUDIO_DEVICE_IN_BACK_MIC"    role="source">
    both: PCM_16_BIT, 8000 16000 32000 44100 48000, MONO + STEREO
```

Corroborated by the vendor property `ro.hx_existence_two_mic = 1`. The `voip_tx`
mixPort (`AUDIO_INPUT_FLAG_VOIP_TX`) routes from **both** built-in mics, so the
array path physically exists.

**Noise suppression, AGC and echo cancellation are real, not stubs.**
`/vendor/etc/audio_effects.xml` loads MediaTek's own `libaudiopreprocessing_mtk.so`,
and `dumpsys media.audio_flinger` confirms all three are loaded and registered at
runtime under the standard type UUIDs — so `NoiseSuppressor.isAvailable()`,
`AutomaticGainControl.isAvailable()` and `AcousticEchoCanceler.isAvailable()` will
all report true. (Contrast `LedController`'s finding that the vendor's
`police_system` service is a hollow shell — on this board, these are not.)

**They are bound to exactly one audio source:**

```xml
<preprocess>
    <stream type="voice_communication">
        <apply effect="aec"/><apply effect="ns"/><apply effect="agc"/>
    </stream>
</preprocess>
```

`mic`, `camcorder` and `voice_recognition` have **no** effects attached.

### What that means for the current code

Both capture sites hardcode the same thing, with no configuration, no fallback,
and no record of what they actually got:

- `capture/AudioRecorder.kt:48` — `AudioRecord(MediaRecorder.AudioSource.MIC, 16000, MONO, PCM_16, …)`
  (standalone recording; also reused by SP-Ask's `AskRecorder`)
- `capture/camera2/SegmentRecorder.kt:115` — same, at 44 100 Hz (the video audio track)

Grepping `app/src` for `NoiseSuppressor`, `AutomaticGainControl`,
`AcousticEchoCanceler`, `getMicrophones`, `getDevices`, `setPreferredDevice`:
**zero hits**. The only `AudioManager` use is `VolumeCycler` adjusting playback volume.

So: **we record through a single, unprocessed front mic and have never asked the
platform for anything else.** The measured −36 dBFS with no AGC signature, the
untouched 100 Hz rumble, and the missing high frequencies are all consistent with
exactly that. `VOICE_COMMUNICATION` is the only source that engages the array;
`VOICE_RECOGNITION` is equivalent to `MIC` on this board and is not a lever.

## Goal

Decide, from measurement rather than inference, which capture configuration this
board should ship — then change one constant.

**Non-goals.** No per-site or backend-driven audio configuration: there is one
wear position and one scenario today, and configurability without evidence is
unjustified complexity. No changes to upload, session, or chunking behaviour. No
backend changes. No attempt to fix the video path's audio in this spec beyond
establishing whether the array is reachable at 44.1 kHz.

## Design

### Single configuration point

`capture/AudioCaptureConfig.kt` (new, `main`) — an immutable description of how to
open a microphone:

```
data class AudioCaptureConfig(
    val source: Int,                       // MediaRecorder.AudioSource.*
    val sampleRate: Int,
    val preferredMic: MicChoice? = null,   // FRONT / BACK, resolved to a device id at open time
    val enableNs: Boolean = false,         // explicit AudioEffect attach
    val enableAgc: Boolean = false,
)
```

with `AudioCaptureConfig.DEFAULT_STANDALONE` (16 kHz) and `DEFAULT_VIDEO` (44.1 kHz),
both currently `source = MIC` and every other field off — **byte-identical to today's
behaviour**. `AudioRecorder.start()` gains an optional config parameter defaulting
to `DEFAULT_STANDALONE`; `SegmentRecorder.buildMic()` reads `DEFAULT_VIDEO`. Prod
behaviour does not change until the constants change.

**Selecting a physical mic is not a matter of type.** Android exposes both built-in
microphones as `AudioDeviceInfo.TYPE_BUILTIN_MIC`; there is no distinct type constant
for the rear one, so choosing by type would silently select whichever came first —
the exact "looks like it worked, returns the wrong thing" failure this design is
trying to avoid. `MicChoice.FRONT` / `BACK` are therefore resolved at open time by
enumerating `getDevices(GET_DEVICES_INPUTS)`, filtering to `TYPE_BUILTIN_MIC`, and
disambiguating with `getMicrophones()` — matching on `MicrophoneInfo.getAddress()`
against `AudioDeviceInfo.getAddress()` and ordering by `MicrophoneInfo.getPosition()`
along the front-back axis, with the front mic being the one facing away from the
wearer. If the board reports fewer than two addressable built-in mics, or positions
are unavailable (`POSITION_UNKNOWN`), resolution fails loudly and the take is
recorded as unsupported rather than silently falling back to the default mic.
`capabilities.json` is captured before the takes precisely so this resolution can be
checked by hand against what the hardware reported.

A shared `openMic(config): OpenedMic` helper builds the `AudioRecord` via
`AudioRecord.Builder`, applies `setPreferredDevice` when requested, and attaches
`NoiseSuppressor` / `AutomaticGainControl` to the session when requested. It returns
the record **together with what actually happened** (see below), and owns releasing
the effects alongside the record.

### Report what was granted, not what was asked

`OpenedMic` carries the requested config and the observed reality: the
`AudioRecord`'s actual sample rate, channel count and format; whether
`setPreferredDevice` returned true; the `routedDevice` type after `startRecording()`;
`activeMicrophones` (identity and position of the mics actually feeding the stream);
and whether each effect reported `enabled` after creation.

This is deliberate. The recurring failure mode in this project is a path that
reports healthy while producing the wrong thing — the ROM already lies about
`SENSOR_ORIENTATION`, and a silently-ignored `setPreferredDevice` returning a
stream from the other mic would be indistinguishable from success. Every probe
take records both.

`capture/MicCapabilities.kt` (new, `main`) — a pure query returning a serialisable
snapshot: `AudioManager.getMicrophones()` (per-mic id, address, position, directionality),
`getDevices(GET_DEVICES_INPUTS)`, and the three `isAvailable()` flags. Kept in `main`
because it is useful diagnostic output later, not only for this probe.

### The probe, isolated in the dev flavor

A **second launcher activity that exists only in the dev flavor** —
`app/src/dev/java/.../devprobe/AudioProbeActivity.kt` plus a dev-only manifest entry
labelled `AudioProbe`. Nothing in `app/src/main`'s UI, navigation or `MainActivity`
is touched, and the class does not exist in the prod APK at all.

One button. It runs the take list below back-to-back, 15 s each with a 3 s gap,
showing the current take and a live dBFS meter so the operator can see the mic is
alive without having to interact between takes (posture must not change mid-run).

Output goes to a single timestamped folder in external storage:
`probe_{run}_{NN}_{configName}_{sampleRate}.wav`, a sibling `.json` per take with the
`OpenedMic` report and measured peak/RMS, and one `capabilities.json`.

**The probe writes local files only.** It does not insert Room rows, does not
enqueue WorkManager uploads, and never touches the recordings API. The dev flavor
points at the test stack, and pushing five takes of deliberate noise into the test
lake would pollute real data — the same class of mistake as
`grandtime-dev-flavor-uploads-to-test`.

### Take list

| # | Config | Rate | Question it answers |
|---|---|---|---|
| 1 | `MIC` | 16 k | Baseline — reproduces today's prod exactly |
| 2 | `VOICE_COMMUNICATION` | 16 k | The array, with NS + AGC + AEC as the HAL binds them |
| 3 | `MIC` + prefer front mic | 16 k | Is the default already the front mic? |
| 4 | `MIC` + prefer back mic | 16 k | What does the back mic hear — which hole is covered? |
| 5 | `VOICE_COMMUNICATION` | 44.1 k | Can the array be used on the video audio track at all? |
| 6 | `MIC` + explicit NS + AGC | 16 k | NS/AGC **without** AEC and without the voip path |

Take 6 was added during design. The effects are separately creatable through the
`AudioEffect` API, so if take 2 wins on noise but the bundled AEC or the voip
path's resampling causes harm, take 6 is the way to get the same benefit on the
`MIC` path — including at 44.1 kHz for video, which take 5 may show is otherwise
unreachable. It costs 15 seconds.

Take 5 exercises `AudioRecorder`, not `SegmentRecorder`, so it establishes only
whether the HAL grants `voice_communication` at 44.1 kHz. Full validation of the
video audio track is follow-up work gated on this answer.

## Field protocol

The takes are sequential — every input mixPort in the policy is
`maxOpenCount="1" maxActiveCount="1"`, so two configurations cannot record the same
moment. Comparability therefore depends entirely on reproducing the scene, and the
differences being measured are only a few dB. **A human repeating a sentence from
memory cannot hold level, distance and pace steady enough.** The speech must come
from a loudspeaker.

**Setup.** A speaker (or second phone) playing a fixed clip, on a taped floor mark,
~3 m from a second taped mark where the operator stands. Device worn in its normal
chest position.

**The playback material must be homogeneous, and there are two runs.** The speaker
loops freely and is not synchronised to the start of a take, so a clip that changed
character partway through would feed different content to the same analysis window
in different takes — take 1's "0–5 s" window could contain clean speech while take
2's contained site conversation, and the six takes would no longer be comparable at
the few-dB resolution this decision needs. Each source is therefore a single kind of
speech, 60 s long so that any 15 s take is fully covered without alignment:

- **Run A — `A_clean_speech_60s.wav`**: clean produced speech (the app's own recorded
  voice lines, concatenated with even spacing), −18.2 dBFS, no clipping. Answers
  *does the noise suppressor damage speech.*
- **Run B — `B_site_conversation_60s.wav`**: the real 2026-08-07 site conversation
  (`c0002`, 18–25 s), high-passed at 120 Hz and loudness-normalised, −19.6 dBFS.
  Answers *does this help on our actual material.*

All six takes run against A, then all six against B. Twelve takes, ~6 minutes of
recording. Comparisons are only ever made within a run.

**Each 15 s take, identically:**

| Window | Operator | Measures |
|---|---|---|
| 0–5 s | Stand still, speaker playing | Distant-speech SNR, no friction |
| 5–10 s | March in place, rub the case and clothing | Friction rejection |
| 10–15 s | Say the fixed phrase "one two three four five six seven eight nine ten" | Near-talker level, AGC over/undershoot |

Between takes: do not change clothing, move the speaker, adjust its volume, or
shift the device. The app runs all six takes without operator input for exactly
this reason. Between runs A and B only the playback file changes; the speaker,
its volume, and both floor marks stay put.

The probe therefore takes a run label (A or B) before starting, and writes it into
every filename and metadata record, so a mislabelled run cannot be silently
compared against the wrong baseline.

## Decision criteria

Computed per take from the windows above:

- **SNR** = speech-band (300–3400 Hz) RMS in 0–5 s minus RMS in 5–10 s.
- **Speech preservation** = speech-band RMS in 0–5 s, compared to take 1.
- **Level** = overall RMS and peak; clipping fraction (samples > 0.98).
- **Intelligibility ground truth** = word error on the 10–15 s counting phrase,
  which has a known transcript, run through the current prod ASR provider.

A configuration replaces the default only if **all** hold against take 1:

1. SNR improves by **≥ 6 dB**;
2. speech-band RMS drops by **< 3 dB** (the noise suppressor must not have won by
   removing the person);
3. clipping fraction **< 0.1 %**;
4. the counting phrase transcribes no worse than baseline.

If no configuration clears the bar, that is a real result: it means the obstruction
is mechanical (fabric over the mic) and the answer is wear position or hardware, not
software. The take 3 vs take 4 comparison is what distinguishes the two cases, and it
is worth running regardless of how takes 2/5/6 turn out.

Clips are 15 s deliberately. Measuring variance with short clips is the lesson from
`fieldsight-audio-too-quiet`, where long clips burned ten thousand ElevenLabs credits
to establish a fact a few seconds would have shown.

## Risks

- **The noise suppressor removes the distant speaker.** At −3.4 dB SNR a suppressor
  can easily classify the quiet talker as noise. Criterion 2 exists to catch this;
  it is the single most likely way take 2 looks good and is actually worse.
- **AEC is bundled.** Choosing `voice_communication` takes all three effects; they
  cannot be unbundled through the source. With no playback reference AEC is usually
  inert, but it may introduce artefacts. Take 6 is the unbundled comparison.
- **AGC pumping.** Automatic gain that chases a −38 dBFS speaker will also amplify
  the friction floor between utterances, and may breathe audibly. Visible as
  window-to-window level swings within a take.
- **`setPreferredDevice` may be silently ignored**, especially on the voip path where
  the HAL owns mic selection. Mitigated by recording `routedDevice` and
  `activeMicrophones` rather than trusting the return value.
- **Front/back mic resolution may be wrong even when it succeeds.** The position
  convention is inferred, not documented by the vendor, so takes 3 and 4 could be
  labelled backwards. They are still decisive as a pair — if the two differ
  markedly, one hole is obstructed and the quieter, more low-frequency-dominated
  take is the covered one regardless of which label it carries.
- **The array may not be granted at 44.1 kHz**, forcing a resample or a rate change
  on the video path. This is what take 5 exists to find out early rather than after
  the video work is built.
- **Single input stream.** With `maxActiveCount="1"`, the probe must not run while
  the capture service holds the mic. The probe activity checks and refuses rather
  than failing obscurely — the same contention that motivated the Site Voice mic
  handover design.

## Rollout

Verification is measurement, not a green build: the analysis script over the six
takes, with the table published back before anything changes.

Once a winner clears all four criteria, prod adoption is a change to
`AudioCaptureConfig.DEFAULT_STANDALONE` / `DEFAULT_VIDEO` — one constant each,
no new configuration surface. Ship as a release APK to one unit and re-measure
loudness on the next real session against the −36 dBFS baseline before rolling to
the fleet.

If the winner applies to standalone recording but not to the video track (the
likely shape if take 5 fails and take 6 succeeds), the two defaults diverge and
that divergence is recorded here rather than being discovered later as an
inconsistency.

## Testing

- `AudioCaptureConfig` and the `OpenedMic` report shape are plain data — JVM unit
  tests cover defaults being unchanged from today's hardcoded values, which is the
  regression that matters most.
- `openMic` touches real hardware and has no JVM test, consistent with the rest of
  `capture/`. It is exercised by the probe itself on the device.
- Existing suite (`./gradlew testProdDebugUnitTest`) must stay green, and the prod
  variant must not compile the probe activity — verified by building
  `assembleProdDebug` and confirming the class is absent.
