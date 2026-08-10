# Two microphones, or one copied twice?

**Date:** 2026-08-10
**Status:** Instrument built, not yet run on a device.
**Reads with:** `2026-08-09-audio-probe-findings.md` — the ten-configuration probe that came back
negative, and why its result does **not** settle this question.

## The question this answers

Whether to spend money on a 4–6 microphone array for the F2SP.

Beamforming gain comes entirely from the **phase relationship between channels**. For an
N-element array in a diffuse field — and room reverberation is a diffuse field — the ceiling is
`10·log10(N)`: 6.0 dB for four microphones, 7.8 dB for six. That is a meaningful number here,
because the measured level difference between 2 m and 6 m is only about 5 dB (2026-08-10 distance
ladder). Six decibels would move the usable range substantially.

**But all of it depends on the platform delivering two genuinely independent channels.** If the
HAL satisfies a stereo request by copying one microphone into both, there is no inter-channel
information, and no array on this board can work no matter how many microphones are fitted.

That costs a twenty-line change to find out. It should be found out before anything is ordered.

## Why the earlier probe does not already answer it

The ten-configuration probe measured, on real device audio, that **no capture-side setting on
this board improves transcription** — level, source, NS/AGC, sample rate, and which physical
microphone. That result stands.

It does not transfer to this question. Every one of those ten configurations recorded **a single
channel**; `preferredMic` selects *which* microphone, never *both at once*. `CHANNEL_IN_STEREO`
appears nowhere in the repository, on any branch. Those configurations varied gain and processing
along one signal path; an array varies the *relationship between two* paths. Different mechanism,
different measurement.

So the earlier negative is weak evidence about arrays, not a verdict.

## Why the platform cannot simply be asked

`AudioRecord` reports success for a stereo request whether the HAL fed it two microphones or
duplicated one. And this board is already on record describing itself inconsistently: the earlier
probe found that **`getMicrophones()` lists one microphone while `getDevices()` lists two**.

`OpenedMic.reportJson()` already carries the caveat in a comment — `activeMicrophones` is
synthesized from the routed device on a generic HAL and *"does NOT reveal whether one or two mics
feed the stream"*.

Every verdict therefore comes from comparing the samples, never from an API's own account of
itself.

## Block D

Six takes, run back to back with no operator input, ordered so the cheap decisive answer comes
first:

| # | take | what it settles |
|---|---|---|
| 1 | `mono_16k` | control, same minute, so channel behaviour is not compared against a recording made under other conditions |
| 2 | `stereo_16k` | the question, on the routing production uses |
| 3 | `stereo_16k_front` | requesting one mic while opening two channels — the contradiction most likely to expose what the HAL really did |
| 4 | `stereo_16k_back` | as above, other mic |
| 5 | `stereo_44k` | inter-channel delay for ~10 cm spacing is ~0.29 ms: under 5 samples at 16 kHz, about 13 at 44.1 kHz. Also the fallback if 16 kHz stereo is refused |
| 6 | `mono_16k_repeat` | drift over the block cannot be read as a channel effect |

A configuration this board refuses is written out as a result, not a crash — the existing runner
already does this, and a refusal that never reached the table would read as "not tested".

## The verdicts

`tools/dual_mic_analysis.py` decides in this order, stopping at the first that applies:

- **REFUSED** — the take errored, or the granted channel count is not 2. The question is closed:
  no array can be built on this board.
- **DUPLICATED** — the channels are bit-identical, or correlate at 1.000 with zero lag. The
  request *succeeded* and returned nothing usable. Note that **only this comparison would have
  revealed it** — every API surface reports health.
- **TWO MICS** — the channels differ. Reports the level difference and the best-correlating lag,
  which are what an array would have to work from.

The analyser was validated against a synthesised block with known answers (a bit-identical pair, a
pair offset by 3 samples and 4 dB, and a refusal) before being pointed at any recording, because
the recording it is meant to judge is not cheap to repeat.

## What a result means

**REFUSED or DUPLICATED** closes the hardware question for this board. Spend the effort on the
levers that do not need new hardware:

1. **Multiple devices.** A person 6 m away who is wearing their own device becomes *that* device's
   wearer, and the wearer is captured **19.6 dB** louder than someone at 0.5 m (measured
   2026-08-10). That is more than double what a six-microphone array could give. The group-merge
   path for this is already built and sitting behind `ENABLE_GROUP_MERGE=false`.
2. **Stop discarding the distant chunks.** At 4 m and 6 m the pipeline dropped the audio entirely
   (VAD judged the chunk silent), yet that same audio scored +0.469 and +0.386 against an enrolled
   voiceprint — well above the threshold that produced zero false positives across 58 turns. The
   signal is being thrown away. This collides with the finding that VAD-zero chunks sent to AWS
   Transcribe produced 10.7 % fabricated words, so it needs a low-confidence path rather than
   simply turning the drop off.
3. **Reduce friction noise.** Real recordings measured speech at −38.0 dBFS against clothing
   friction at −34.6 dBFS — a *negative* signal-to-noise ratio, from a noise source sitting on the
   microphone. Six decibels off that is worth the same as adding four microphones, at two orders
   of magnitude less cost.

**TWO MICS** does not by itself justify a purchase. The next questions would be how large and how
stable the inter-channel difference is, and whether the **15 dB part-to-part mismatch measured
between the two fitted microphones** leaves enough matching for a beamformer — array processing
assumes matched elements, and that mismatch is large enough to defeat it on its own.

## Running it

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDevDebug
adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
# open "devfieldsight" -> AudioProbe -> press block D, read the enrolment passage
adb pull //sdcard/Android/data/com.benzn.grandtime.dev/files/audioprobe <dir>
python tools/dual_mic_analysis.py <dir>/<stamp>_blockD
```

The dev flavour writes only to app-specific storage — no Room row, no upload, no API call. It
points at the test stack, so it must not be used for real recordings.
