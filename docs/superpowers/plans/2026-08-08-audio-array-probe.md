# Audio mic-array probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the two existing capture sites a single configuration point, then add a dev-flavor-only probe that records ten microphone configurations against a controlled scene so the shipping configuration can be chosen from measurement.

**Architecture:** A pure `AudioCaptureConfig` describes how to open a mic; a shared `openMic()` builds the `AudioRecord`, applies mic selection and effects, and reports what the platform actually granted. `AudioRecorder` and `SegmentRecorder` both route through it with defaults that reproduce today's behaviour byte-for-byte. The probe is a second launcher activity compiled only into the dev flavor; it writes WAV + JSON to app-specific storage and never touches Room, WorkManager, or any API.

**Tech Stack:** Kotlin 2.1, Android framework only (`AudioRecord`, `AudioManager`, `AudioEffect`), Compose Material3 (already present), JUnit4. Python 3 + numpy for the offline analysis script (already installed on the dev machine).

## Global Constraints

- **No new Gradle dependencies and no native libraries.** Device ABI is armeabi 32-bit only. Android framework APIs only.
- **minSdk = targetSdk = 33**, compileSdk 35, Kotlin 2.1, AGP 8.7.
- **All development artefacts in English** — code comments, commit messages, technical docs. Existing Chinese comments are not rewritten.
- **No Google Play Services.**
- Build with `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
- **Work in the worktree `C:/gt-audio-probe`** (branch `feat/audio-array-probe`, off `origin/main`), not in the Dropbox checkout. Gradle occasionally fails with `Could not delete '...build...'` — rerun once, it is not a real failure.
- **`local.properties` already exists in the worktree** (copied from the Dropbox checkout, since it is gitignored and worktrees do not inherit ignored files) and contains
  `sdk.dir=C\:\\Users\\camil\\AppData\\Local\\Android\\Sdk`. `ANDROID_HOME` is not set on this
  machine, so without that file every gradle command fails with `SDK location not found`. If it
  goes missing, copy it again rather than retyping it — naive single backslashes produce a
  cryptic `java.io.IOException` about invalid path syntax.
- **The repo is CRLF throughout** (`core.autocrlf=true`). Use the Edit tool for the before/after
  replacements in Tasks 4–5; any scripted replacement must normalise line endings first.
- Unit tests: `./gradlew testProdDebugUnitTest` (481 existing cases) and `./gradlew testDevDebugUnitTest`.
- **The probe must never upload.** No Room writes, no WorkManager enqueue, no recordings API call.
- **Prod behaviour must not change in this plan.** Every default reproduces today's values; changing them happens after measurement.

Design reference: `docs/superpowers/specs/2026-08-08-audio-array-probe-design.md`.

## File Structure

**Created in `main`** (production code, used by both call sites and the probe):
- `app/src/main/java/com/benzn/grandtime/capture/AudioCaptureConfig.kt` — the config data class, `MicChoice`, and the two defaults. Pure Kotlin.
- `app/src/main/java/com/benzn/grandtime/capture/MicResolution.kt` — pure device-list → chosen-device logic plus the small JSON writer both snapshots share. No Android imports.
- `app/src/main/java/com/benzn/grandtime/capture/MicCapabilities.kt` — reads `AudioManager` and builds a serialisable snapshot. Thin Android wrapper over `MicResolution`.
- `app/src/main/java/com/benzn/grandtime/capture/MicOpener.kt` — `openMic()`, `OpenedMic`, `MicReport`.

**Modified in `main`:**
- `app/src/main/java/com/benzn/grandtime/capture/AudioRecorder.kt` — take a config, open through `openMic`.
- `app/src/main/java/com/benzn/grandtime/capture/camera2/SegmentRecorder.kt` — same, including the Site Voice handover rebuild.

**Created in `dev` only** (absent from the prod APK):
- `app/src/dev/java/com/benzn/grandtime/devprobe/ProbeTakes.kt` — the ten configurations as a pure list.
- `app/src/dev/java/com/benzn/grandtime/devprobe/PcmStats.kt` — pure peak/RMS/clipping from PCM16 bytes.
- `app/src/dev/java/com/benzn/grandtime/devprobe/ProbeRunner.kt` — records one take, writes `.wav` + `.json`.
- `app/src/dev/java/com/benzn/grandtime/devprobe/AudioProbeActivity.kt` — the UI.
- `app/src/dev/AndroidManifest.xml` — declares the second launcher activity.

**Created for tests:**
- `app/src/test/java/com/benzn/grandtime/capture/AudioCaptureConfigTest.kt`
- `app/src/test/java/com/benzn/grandtime/capture/MicResolutionTest.kt`
- `app/src/testDev/java/com/benzn/grandtime/devprobe/PcmStatsTest.kt`
- `app/src/testDev/java/com/benzn/grandtime/devprobe/ProbeTakesTest.kt`

**Created for analysis:**
- `tools/audio_probe_analysis.py` — turns a pulled probe folder into the decision table.

---

### Task 1: `AudioCaptureConfig` and the two defaults

The single point both capture sites will read from. Nothing consumes it yet; this task exists so the "defaults are unchanged" assertion is locked in before any call site moves.

**Why `bufferFloorBytes` and not a buffer size:** today both sites compute
`max(AudioRecord.getMinBufferSize(...), <a floor>)`. `getMinBufferSize` is device-dependent
and unavailable in JVM tests, so the config stores only the floor and `openMic` applies the
`max`. That keeps the "unchanged defaults" claim testable instead of aspirational.
`AudioRecorder.kt:47` uses `sampleRate * 2` = 32000 as its floor; `SegmentRecorder.kt:114`
uses `4096 * 2` = 8192.

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/AudioCaptureConfig.kt`
- Test: `app/src/test/java/com/benzn/grandtime/capture/AudioCaptureConfigTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class MicChoice(val address: String)` with `FRONT("bottom")`, `BACK("back")`; `data class AudioCaptureConfig(source: Int, sampleRate: Int, bufferFloorBytes: Int, preferredMic: MicChoice?, enableNs: Boolean, enableAgc: Boolean)`; `AudioCaptureConfig.DEFAULT_STANDALONE`; `AudioCaptureConfig.DEFAULT_VIDEO`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/benzn/grandtime/capture/AudioCaptureConfigTest.kt`:

```kotlin
package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** These assertions are the regression guard for "prod behaviour is unchanged": they pin the
 *  defaults to the literal values the two capture sites computed before they were routed through
 *  a shared opener. Literals rather than framework constants on purpose — the test must fail if
 *  the value changes, not silently follow it. */
class AudioCaptureConfigTest {

    @Test fun `standalone default reproduces AudioRecorder's previous hardcoded values`() {
        val c = AudioCaptureConfig.DEFAULT_STANDALONE
        assertEquals(1, c.source)              // MediaRecorder.AudioSource.MIC
        assertEquals(16000, c.sampleRate)
        assertEquals(32000, c.bufferFloorBytes) // was max(minBuf, sampleRate * 2)
        assertNull(c.preferredMic)
        assertFalse(c.enableNs)
        assertFalse(c.enableAgc)
    }

    @Test fun `video default reproduces SegmentRecorder's previous hardcoded values`() {
        val c = AudioCaptureConfig.DEFAULT_VIDEO
        assertEquals(1, c.source)              // MediaRecorder.AudioSource.MIC
        assertEquals(44100, c.sampleRate)
        assertEquals(8192, c.bufferFloorBytes)  // was max(minBuf, 4096 * 2)
        assertNull(c.preferredMic)
        assertFalse(c.enableNs)
        assertFalse(c.enableAgc)
    }

    @Test fun `mic choices carry the addresses this board reports`() {
        assertEquals("bottom", MicChoice.FRONT.address)
        assertEquals("back", MicChoice.BACK.address)
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd /c/gt-audio-probe && ./gradlew testProdDebugUnitTest --tests '*AudioCaptureConfigTest*'
```

Expected: compilation failure — `Unresolved reference: AudioCaptureConfig`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/benzn/grandtime/capture/AudioCaptureConfig.kt`:

```kotlin
package com.benzn.grandtime.capture

import android.media.MediaRecorder

/** Which physical built-in microphone to ask for. Both of this board's mics surface as
 *  AudioDeviceInfo.TYPE_BUILTIN_MIC, so they can only be told apart by address — measured on
 *  unit F2S202503103059: "bottom" points down, "back" points at the wearer. */
enum class MicChoice(val address: String) {
    FRONT("bottom"),
    BACK("back"),
}

/**
 * How to open a microphone. Both capture sites read a default from here instead of hardcoding
 * their own values, so the shipping configuration is one constant rather than two call sites.
 *
 * [bufferFloorBytes] is a floor, not a size: the opener uses
 * `max(AudioRecord.getMinBufferSize(...), bufferFloorBytes)`, which is what both sites already
 * did. Buffer size sets read granularity, which drives audio segment-roll timing and the video
 * loop's read cadence, so it belongs to the config rather than to AudioRecord.Builder's default.
 *
 * [enableNs] / [enableAgc] attach app-created AudioEffects to the session. They are NOT how
 * VOICE_COMMUNICATION gets its processing — this board's audio_effects.xml binds NS/AGC/AEC to
 * that source automatically, and those auto-attached effects are not enumerable from the app.
 */
data class AudioCaptureConfig(
    val source: Int,
    val sampleRate: Int,
    val bufferFloorBytes: Int,
    val preferredMic: MicChoice? = null,
    val enableNs: Boolean = false,
    val enableAgc: Boolean = false,
) {
    companion object {
        /** Standalone WAV recording (and SP-Ask, which reuses AudioRecorder). */
        val DEFAULT_STANDALONE = AudioCaptureConfig(
            source = MediaRecorder.AudioSource.MIC,
            sampleRate = 16000,
            bufferFloorBytes = 32000,
        )

        /** The video segment's AAC audio track. */
        val DEFAULT_VIDEO = AudioCaptureConfig(
            source = MediaRecorder.AudioSource.MIC,
            sampleRate = 44100,
            bufferFloorBytes = 8192,
        )
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
cd /c/gt-audio-probe && ./gradlew testProdDebugUnitTest --tests '*AudioCaptureConfigTest*'
```

Expected: 3 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/AudioCaptureConfig.kt \
        app/src/test/java/com/benzn/grandtime/capture/AudioCaptureConfigTest.kt
git commit -m "feat(audio): name the capture settings the two sites had hardcoded"
```

---

### Task 2: Pure mic resolution and the JSON writer

Selecting a physical mic by `AudioDeviceInfo.TYPE_BUILTIN_MIC` alone would silently pick whichever came first, because both of this board's mics report that type. Selection is by address. This task holds the pure decision so it can be tested without a device, plus the minimal JSON writer used by every artefact the probe emits (`org.json` is unavailable in JVM unit tests, and a new dependency is forbidden).

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/MicResolution.kt`
- Test: `app/src/test/java/com/benzn/grandtime/capture/MicResolutionTest.kt`

**Interfaces:**
- Consumes: `MicChoice` (Task 1).
- Produces: `data class InputDevice(val id: Int, val type: Int, val address: String, val productName: String)`; `fun resolvePreferredMic(devices: List<InputDevice>, choice: MicChoice): InputDevice?`; `fun jsonObject(fields: List<Pair<String, String>>): String`; `fun jsonString(value: String): String`; `fun jsonArray(items: List<String>): String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/benzn/grandtime/capture/MicResolutionTest.kt`:

```kotlin
package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicResolutionTest {

    // TYPE_BUILTIN_MIC is 15; TYPE_WIRED_HEADSET is 3. Literals so the test does not depend on
    // android.jar stubs.
    private val builtinMic = 15
    private val wiredHeadset = 3

    private val board = listOf(
        InputDevice(id = 13, type = builtinMic, address = "bottom", productName = "SDJW-F2S"),
        InputDevice(id = 14, type = builtinMic, address = "back", productName = "SDJW-F2S"),
        InputDevice(id = 21, type = wiredHeadset, address = "", productName = "headset"),
    )

    @Test fun `front resolves to the bottom-addressed built-in mic`() {
        assertEquals(13, resolvePreferredMic(board, MicChoice.FRONT)?.id)
    }

    @Test fun `back resolves to the back-addressed built-in mic`() {
        assertEquals(14, resolvePreferredMic(board, MicChoice.BACK)?.id)
    }

    @Test fun `a non-built-in device is never chosen even if its address matches`() {
        val decoys = listOf(InputDevice(id = 21, type = wiredHeadset, address = "back", productName = "headset"))
        assertNull(resolvePreferredMic(decoys, MicChoice.BACK))
    }

    @Test fun `address matching ignores case`() {
        val upper = listOf(InputDevice(id = 14, type = builtinMic, address = "BACK", productName = "x"))
        assertEquals(14, resolvePreferredMic(upper, MicChoice.BACK)?.id)
    }

    @Test fun `an unresolvable choice returns null rather than falling back to another mic`() {
        val onlyFront = listOf(InputDevice(id = 13, type = builtinMic, address = "bottom", productName = "x"))
        assertNull(resolvePreferredMic(onlyFront, MicChoice.BACK))
    }

    @Test fun `json escapes quotes backslashes and newlines`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", jsonString("a\"b\\c\nd"))
    }

    @Test fun `json object renders fields in the order given`() {
        val s = jsonObject(listOf("a" to "1", "b" to jsonString("x")))
        assertEquals("{\"a\":1,\"b\":\"x\"}", s)
    }

    @Test fun `json array renders items in the order given`() {
        assertEquals("[1,2]", jsonArray(listOf("1", "2")))
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
cd /c/gt-audio-probe && ./gradlew testProdDebugUnitTest --tests '*MicResolutionTest*'
```

Expected: compilation failure — `Unresolved reference: InputDevice`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/benzn/grandtime/capture/MicResolution.kt`:

```kotlin
package com.benzn.grandtime.capture

import android.media.AudioDeviceInfo

/** A flattened AudioDeviceInfo, so the selection decision can be tested without a device. */
data class InputDevice(
    val id: Int,
    val type: Int,
    val address: String,
    val productName: String,
)

/**
 * Picks the built-in microphone whose address matches [choice], or null if this board does not
 * expose it.
 *
 * Returning null rather than falling back to the default mic is the point: a silent fallback
 * would make "recorded from the back mic" indistinguishable from "recorded from whatever the
 * platform felt like", which is exactly the comparison the probe exists to make.
 */
fun resolvePreferredMic(devices: List<InputDevice>, choice: MicChoice): InputDevice? =
    devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.address.equals(choice.address, ignoreCase = true)
    }

/** Minimal JSON emitters. org.json is not available in JVM unit tests and a new dependency is
 *  not allowed, so the probe's artefacts are built from these three functions. Callers pass
 *  already-rendered values, so numbers and booleans go in bare and strings go through
 *  [jsonString]. */
fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

fun jsonObject(fields: List<Pair<String, String>>): String =
    fields.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}:$v" }

fun jsonArray(items: List<String>): String = items.joinToString(",", "[", "]")
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
cd /c/gt-audio-probe && ./gradlew testProdDebugUnitTest --tests '*MicResolutionTest*'
```

Expected: 8 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/MicResolution.kt \
        app/src/test/java/com/benzn/grandtime/capture/MicResolutionTest.kt
git commit -m "feat(audio): tell the two built-in mics apart by address, or refuse"
```

---

### Task 3: `openMic` and the report of what was granted

The shared opener. It applies the config and — critically — records what the platform actually did, because a sample rate that was *requested* is not a sample rate that was *captured*, and `setPreferredDevice` can be ignored without saying so.

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/MicOpener.kt`
- Create: `app/src/main/java/com/benzn/grandtime/capture/MicCapabilities.kt`

No JVM test: every line touches `AudioRecord`, `AudioManager` or `AudioEffect`. This matches the
rest of `capture/` (camera, GL and GPS classes are device-verified, not JVM-tested). The pure
parts it depends on were tested in Tasks 1–2, and the probe exercises this on the device.

**Interfaces:**
- Consumes: `AudioCaptureConfig`, `MicChoice` (Task 1); `InputDevice`, `resolvePreferredMic`, `jsonObject`, `jsonString`, `jsonArray` (Task 2).
- Produces:
  - `class OpenedMic` with `val record: AudioRecord`, `val bufferBytes: Int`, `fun reportJson(): String`, `fun stopAndRelease()`.
  - `fun openMic(context: Context, config: AudioCaptureConfig): OpenedMic` — throws `IllegalStateException` if the record fails to initialize or a requested `preferredMic` cannot be resolved.
  - `object MicCapabilities` with `fun snapshotJson(context: Context): String`.

- [ ] **Step 1: Write `MicOpener.kt`**

Create `app/src/main/java/com/benzn/grandtime/capture/MicOpener.kt`:

```kotlin
package com.benzn.grandtime.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * A live microphone plus the record of what the platform actually granted.
 *
 * Two limits are deliberate and documented rather than papered over:
 *  - AudioRecord never reports a sample rate other than the one requested; it either initializes
 *    or fails, and AudioFlinger resamples invisibly if the HAL captured at a different rate. So
 *    [reportJson]'s rate is a request echo, and the real check is spectral, done offline.
 *  - Effects the audio policy auto-attaches to a source (this board binds NS/AGC/AEC to
 *    VOICE_COMMUNICATION) cannot be enumerated from an app. Only app-created effects report
 *    their enabled state here.
 */
class OpenedMic internal constructor(
    val record: AudioRecord,
    val bufferBytes: Int,
    private val config: AudioCaptureConfig,
    private val effects: List<AudioEffect>,
    private val requestedMicAddress: String?,
    private val preferredDeviceAccepted: Boolean?,
) {
    /** Routing facts only become true after startRecording(), so this is safe to call any time
     *  but only meaningful afterwards. Never throws — a diagnostic must not break a recording. */
    fun reportJson(): String {
        val routed = runCatching { record.routedDevice }.getOrNull()
        val active = runCatching { record.activeMicrophones }.getOrNull().orEmpty()
        return jsonObject(listOf(
            "requestedSource" to "${config.source}",
            "requestedSampleRate" to "${config.sampleRate}",
            "grantedSampleRate" to "${runCatching { record.sampleRate }.getOrDefault(-1)}",
            "grantedChannelCount" to "${runCatching { record.channelCount }.getOrDefault(-1)}",
            "bufferBytes" to "$bufferBytes",
            "requestedMicAddress" to (requestedMicAddress?.let { jsonString(it) } ?: "null"),
            "preferredDeviceAccepted" to (preferredDeviceAccepted?.toString() ?: "null"),
            "routedDeviceType" to "${routed?.type ?: -1}",
            "routedDeviceAddress" to jsonString(routed?.address ?: ""),
            // Synthesized from the routed device on a generic HAL, so this does NOT reveal whether
            // one or two mics feed the stream. Diagnostics only.
            "activeMicrophoneAddresses" to jsonArray(active.map { jsonString(it.address ?: "") }),
            "requestedNs" to "${config.enableNs}",
            "requestedAgc" to "${config.enableAgc}",
            "appEffects" to jsonArray(effects.map {
                jsonObject(listOf(
                    "name" to jsonString(it.javaClass.simpleName),
                    "enabled" to "${runCatching { it.enabled }.getOrDefault(false)}",
                ))
            }),
            "nsAvailable" to "${runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)}",
            "agcAvailable" to "${runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)}",
            "aecAvailable" to "${runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)}",
        ))
    }

    /** Releases the app-created effects before the record, then the record. Idempotent-safe:
     *  every call is guarded, so a second invocation cannot throw. */
    fun stopAndRelease() {
        effects.forEach { runCatching { it.enabled = false }; runCatching { it.release() } }
        runCatching { record.stop() }
        runCatching { record.release() }
    }
}

/**
 * Opens a microphone per [config].
 *
 * Throws IllegalStateException when a requested physical mic cannot be resolved, rather than
 * quietly recording from the default one — an unnoticed fallback would invalidate exactly the
 * comparison this exists to support. Caller (preflight) must already hold RECORD_AUDIO.
 */
@SuppressLint("MissingPermission")
fun openMic(context: Context, config: AudioCaptureConfig): OpenedMic {
    val minBuf = AudioRecord.getMinBufferSize(
        config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    val bufferBytes = maxOf(minBuf, config.bufferFloorBytes)

    var chosen: InputDevice? = null
    if (config.preferredMic != null) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map {
            InputDevice(it.id, it.type, it.address ?: "", it.productName?.toString() ?: "")
        }
        chosen = resolvePreferredMic(devices, config.preferredMic)
            ?: throw IllegalStateException(
                "no built-in mic with address '${config.preferredMic.address}' on this device"
            )
    }

    val record = AudioRecord(
        config.source, config.sampleRate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes
    )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IllegalStateException("AudioRecord not initialized (source=${config.source}, rate=${config.sampleRate})")
    }

    var accepted: Boolean? = null
    if (chosen != null) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val target = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == chosen.id }
        accepted = target != null && record.setPreferredDevice(target)
    }

    val effects = ArrayList<AudioEffect>(2)
    if (config.enableNs && NoiseSuppressor.isAvailable()) {
        runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()
            ?.also { it.enabled = true; effects.add(it) }
    }
    if (config.enableAgc && AutomaticGainControl.isAvailable()) {
        runCatching { AutomaticGainControl.create(record.audioSessionId) }.getOrNull()
            ?.also { it.enabled = true; effects.add(it) }
    }

    return OpenedMic(record, bufferBytes, config, effects, config.preferredMic?.address, accepted)
}
```

- [ ] **Step 2: Write `MicCapabilities.kt`**

Create `app/src/main/java/com/benzn/grandtime/capture/MicCapabilities.kt`:

```kotlin
package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * What this unit's audio hardware reports about itself.
 *
 * Worth collecting per unit, not just once: this board ships in two BOM variants whose
 * microphones differ by 15 dB of sensitivity, so recording level may be a property of the
 * handset rather than of the software. `description` carries the fitted part identifier.
 */
object MicCapabilities {

    fun snapshotJson(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val inputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
            .getOrDefault(emptyList())
            .map { d ->
                jsonObject(listOf(
                    "id" to "${d.id}",
                    "type" to "${d.type}",
                    "address" to jsonString(d.address ?: ""),
                    "productName" to jsonString(d.productName?.toString() ?: ""),
                    "channelCounts" to jsonArray(d.channelCounts.map { "$it" }),
                    "sampleRates" to jsonArray(d.sampleRates.map { "$it" }),
                ))
            }

        val mics = runCatching { am.microphones }.getOrDefault(emptyList()).map { m ->
            val p = runCatching { m.position }.getOrNull()
            jsonObject(listOf(
                "id" to "${m.id}",
                "address" to jsonString(m.address ?: ""),
                "description" to jsonString(m.description ?: ""),
                "location" to "${m.location}",
                "directionality" to "${m.directionality}",
                "sensitivity" to "${m.sensitivity}",
                "maxSpl" to "${m.maxSpl}",
                "position" to jsonArray(
                    if (p == null) emptyList() else listOf("${p.x}", "${p.y}", "${p.z}")
                ),
            ))
        }

        return jsonObject(listOf(
            "model" to jsonString(android.os.Build.MODEL),
            "device" to jsonString(android.os.Build.DEVICE),
            "serial" to jsonString(runCatching { android.os.Build.getSerial() }.getOrDefault("")),
            "nsAvailable" to "${runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)}",
            "agcAvailable" to "${runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)}",
            "aecAvailable" to "${runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)}",
            "inputDevices" to jsonArray(inputs),
            "microphones" to jsonArray(mics),
        ))
    }
}
```

- [ ] **Step 3: Verify it compiles and the existing suite is untouched**

```bash
cd /c/gt-audio-probe && ./gradlew compileProdDebugKotlin testProdDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass — 481 existing cases plus the 11 added by Tasks 1–2.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/MicOpener.kt \
        app/src/main/java/com/benzn/grandtime/capture/MicCapabilities.kt
git commit -m "feat(audio): open the mic from a config, and record what was actually granted"
```

---

### Task 4: Route `AudioRecorder` through `openMic`

Behaviour must not change. `AskRecorder` calls `start(file)` and is not touched.

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/AudioRecorder.kt` (lines 25–66 and the `stop`/`cleanup` release paths)

**Interfaces:**
- Consumes: `AudioCaptureConfig.DEFAULT_STANDALONE` (Task 1), `openMic`, `OpenedMic` (Task 3).
- Produces: `AudioRecorder.start(file, segmentBytes, overlapBytes, startIndex, clockMs, nextFile, onSegment, config)` — `config` is the new trailing parameter defaulting to `AudioCaptureConfig.DEFAULT_STANDALONE`; `AudioRecorder.lastReportJson: String?` for the probe.

- [ ] **Step 1: Add the config parameter and the opened-mic field**

In `AudioRecorder.kt`, replace the field declaration block (currently `private var record: AudioRecord? = null`) with:

```kotlin
    private var opened: OpenedMic? = null
```

Keep `import android.media.AudioRecord` — the segmented worker signatures still take
`rec: AudioRecord`.

Do **not** add a `lastReportJson` field. The probe calls `openMic` directly and would never read
it, so it would be dead code written off the caller thread without synchronisation.

Change the `start` signature by appending one parameter:

```kotlin
        onSegment: ((AudioSegment) -> Unit)? = null,
        config: AudioCaptureConfig = AudioCaptureConfig.DEFAULT_STANDALONE,
    ): Boolean = try {
```

- [ ] **Step 2: Replace the body's mic construction**

Replace these lines in `start`:

```kotlin
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(minBuf, sr * 2) // >= 1s
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
        // Assign before the init check so a failed AudioRecord is still released by cleanup().
        record = rec
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
```

with:

```kotlin
        // openMic applies the same max(minBufferSize, floor) this site used to compute inline, and
        // throws instead of returning a half-built record — so the old init check moves inside it.
        val om = openMic(context, config)
        opened = om
        val rec = om.record
        val buf = om.bufferBytes
```

- [ ] **Step 3: Replace the two release paths**

In `stop()`, replace:

```kotlin
        record?.apply { stop(); release() }
        record = null
```

with:

```kotlin
        opened?.stopAndRelease()
        opened = null
```

In `cleanup()`, replace:

```kotlin
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
```

with:

```kotlin
        runCatching { opened?.stopAndRelease() }
        opened = null
```

- [ ] **Step 4: Run the whole suite**

```bash
cd /c/gt-audio-probe && ./gradlew testProdDebugUnitTest
```

Expected: all PASS. `AudioAssemblyTest` and `AudioRecovererTest` exercise the assembly path and
must be unaffected — they do not construct an `AudioRecorder`.

- [ ] **Step 5: Verify the SP-Ask call site still compiles unchanged**

```bash
cd /c/gt-audio-probe && grep -rn "AudioRecorder(" app/src/main/java --include=*.kt
cd /c/gt-audio-probe && ./gradlew compileProdDebugKotlin compileDevDebugKotlin
```

Expected: `AskRecorder` calls `start(file)` with no config argument and compiles; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/AudioRecorder.kt
git commit -m "refactor(audio): open the standalone recorder's mic through the shared opener"
```

---

### Task 5: Route `SegmentRecorder` through `openMic`, including the handover rebuild

`SegmentRecorder` builds a mic in two places: `prepare()` and `resumeAudio()` (the Site Voice handover). Both must go through `openMic`, or a future non-default config would silently lose its effects and mic selection at the first handover — a defect that would appear only during a Site Voice call and leave no trace.

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/camera2/SegmentRecorder.kt` (the `audioRecord` field, `buildMic()`, `pauseAudioForHandover()`, `resumeAudio()`, and the stop path)

**Interfaces:**
- Consumes: `AudioCaptureConfig.DEFAULT_VIDEO` (Task 1), `openMic`, `OpenedMic` (Task 3).
- Produces: no new public API. `AUDIO_SAMPLE_RATE` remains the muxer/encoder's rate and must stay equal to `AudioCaptureConfig.DEFAULT_VIDEO.sampleRate`.

- [ ] **Step 1: Hold the OpenedMic alongside the AudioRecord**

The audio loop reads `audioRecord` on a worker thread and must keep doing so. Add a parallel field
next to it (both written only from Main, per the existing comment):

```kotlin
    @Volatile private var audioRecord: AudioRecord? = null
    // Held so the app-created effects attached by openMic are released with the record. Written
    // only from Main (prepare / pauseAudioForHandover / resumeAudio), same as audioRecord.
    @Volatile private var audioOpened: OpenedMic? = null
```

Add the imports:

```kotlin
import android.content.Context
import com.benzn.grandtime.capture.AudioCaptureConfig
import com.benzn.grandtime.capture.OpenedMic
import com.benzn.grandtime.capture.openMic
```

- [ ] **Step 2: Rewrite `buildMic()` to use the shared opener**

`openMic` needs a `Context`, which `SegmentRecorder` does not currently hold. Add it as a
constructor parameter — `CaptureManager` constructs each segment's recorder and already has one.

Change the class header:

```kotlin
class SegmentRecorder(private val context: Context, private val probe: (String) -> Unit = {}) {
```

Replace `buildMic()`:

```kotlin
    /** Build the video track's mic through the shared opener. Returns the OpenedMic so the caller
     *  can release the app-created effects along with the record. Throws if not INITIALIZED.
     *  Silence pacing uses a fixed one-AAC-frame chunk (SILENCE_CHUNK_BYTES), so the mic buffer
     *  size is only used to size the AudioRecord itself. */
    private fun buildMic(): OpenedMic = openMic(context, AudioCaptureConfig.DEFAULT_VIDEO)
```

- [ ] **Step 3: Update the construction call site**

In **`setupAudio()`** (not `prepare()` — the text below is unique, but look in the right method),
replace:

```kotlin
        audioRecord = try {
            buildMic()
        } catch (e: Exception) {
            runCatching { audioCodec?.release() }; audioCodec = null // 释放已建 AAC,避免泄漏
            throw e
        }
```

with:

```kotlin
        try {
            val om = buildMic()
            audioOpened = om
            audioRecord = om.record
        } catch (e: Exception) {
            runCatching { audioCodec?.release() }; audioCodec = null // 释放已建 AAC,避免泄漏
            throw e
        }
```

In `pauseAudioForHandover()`, replace the stop/release pair:

```kotlin
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
```

with:

```kotlin
        runCatching { audioOpened?.stopAndRelease() }
        audioOpened = null
        audioRecord = null
```

In `resumeAudio()`, replace the body of the `runCatching` block:

```kotlin
            val ar = buildMic()
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                ar.release(); throw IllegalStateException("AudioRecord 未进入录制")
            }
            audioRecord = ar
            audioHandover = false
            true
```

with:

```kotlin
            val om = buildMic()
            val ar = om.record
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                om.stopAndRelease(); throw IllegalStateException("AudioRecord 未进入录制")
            }
            // Publish both before clearing the flag, so the loop never sees handover=false with a
            // null mic — and so the effects are owned even if the next handover comes immediately.
            audioOpened = om
            audioRecord = ar
            audioHandover = false
            true
```

- [ ] **Step 4: Release through the OpenedMic in `stop()` — as ONE edit, not two**

`stop()` deliberately splits stopping from releasing, and collapsing them would reintroduce the
use-after-release the existing comments were written to prevent. **Leave the `stop()` call where
it is.** Only the release moves.

Do **not** touch this line:

```kotlin
        runCatching { audioRecord?.stop() }              // 先停录音,解开可能阻塞的 read
```

It runs *before* `audioThread?.join(2500)` so a blocked `read()` can unblock. Replace only this
later line, which runs *after* the joins:

```kotlin
        runCatching { audioRecord?.release() }            // release 放到 join 之后,避免 use-after-release
```

with:

```kotlin
        runCatching { audioOpened?.stopAndRelease() }      // release 放到 join 之后,避免 use-after-release
        audioOpened = null
```

`audioRecord = null` is already handled a few lines below (`audioRecord = null; audioCodec = null`)
— do not add another.

- [ ] **Step 5: Release through the OpenedMic in the two `setupAudio()` failure paths**

These two also release the record directly. Left alone, they would strand `audioOpened` holding a
released record with live app-created effects — the exact defect this task exists to prevent, and
the first path runs on every segment rollover during a Site Voice handover.

In the `startAudioPaused` branch, replace:

```kotlin
                runCatching { audioRecord?.release() } // free the mic setupAudio() built; loop is silent
                audioRecord = null
```

with:

```kotlin
                runCatching { audioOpened?.stopAndRelease() } // free the mic setupAudio() built; loop is silent
                audioOpened = null
                audioRecord = null
```

In the audio-start-failure branch, replace:

```kotlin
            runCatching { audioRecord?.stop() }; runCatching { audioRecord?.release() }
```

with:

```kotlin
            runCatching { audioOpened?.stopAndRelease() }; audioOpened = null
```

- [ ] **Step 6: Pass the Context at the one construction site**

There is exactly one, `app/src/main/java/com/benzn/grandtime/capture/camera2/Camera2Pipeline.kt:213`.
`Camera2Pipeline` already holds `private val context: Context` (line 29), so use that field —
do not create a new one and do not hold an Activity reference.

Replace:

```kotlin
            val recorder = SegmentRecorder(probe)
```

with:

```kotlin
            val recorder = SegmentRecorder(context, probe)
```

Confirm nothing else constructs one:

```bash
cd /c/gt-audio-probe && grep -rn "SegmentRecorder(" app/src/main/java --include=*.kt
```

Expected: only the declaration in `SegmentRecorder.kt` and the line just edited.

- [ ] **Step 7: Guard the rate coupling and build**

Add to `SegmentRecorder`'s companion, directly under `AUDIO_SAMPLE_RATE`:

```kotlin
        init {
            // The AAC encoder and muxer track are configured from AUDIO_SAMPLE_RATE while the mic
            // is opened from DEFAULT_VIDEO. If those ever diverge the track plays at the wrong
            // speed with nothing failing, so fail loudly at class-load instead.
            require(AUDIO_SAMPLE_RATE == AudioCaptureConfig.DEFAULT_VIDEO.sampleRate) {
                "AUDIO_SAMPLE_RATE ($AUDIO_SAMPLE_RATE) must match DEFAULT_VIDEO.sampleRate " +
                    "(${AudioCaptureConfig.DEFAULT_VIDEO.sampleRate})"
            }
        }
```

```bash
cd /c/gt-audio-probe && ./gradlew compileProdDebugKotlin compileDevDebugKotlin testProdDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/camera2/SegmentRecorder.kt \
        app/src/main/java/com/benzn/grandtime/capture/camera2/Camera2Pipeline.kt
git commit -m "refactor(video): open the video track's mic through the shared opener, handover included"
```

---

### Task 6: The take list and PCM statistics (dev flavor)

Pure data and pure arithmetic, in the dev source set with its own unit tests. Dev-flavor unit
tests live in `app/src/testDev/`, which AGP compiles into the `testDevDebug` variant.

**Files:**
- Create: `app/src/dev/java/com/benzn/grandtime/devprobe/ProbeTakes.kt`
- Create: `app/src/dev/java/com/benzn/grandtime/devprobe/PcmStats.kt`
- Test: `app/src/testDev/java/com/benzn/grandtime/devprobe/ProbeTakesTest.kt`
- Test: `app/src/testDev/java/com/benzn/grandtime/devprobe/PcmStatsTest.kt`

**Interfaces:**
- Consumes: `AudioCaptureConfig`, `MicChoice` (Task 1).
- Produces: `data class ProbeTake(val index: Int, val name: String, val config: AudioCaptureConfig)`; `val PROBE_TAKES: List<ProbeTake>`; `enum class ProbeBlock { S, F, N }`; `data class PcmStats(val peakDbfs: Double, val rmsDbfs: Double, val clippedFraction: Double)`; `fun pcmStats(pcm: ByteArray, len: Int): PcmStats`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/testDev/java/com/benzn/grandtime/devprobe/PcmStatsTest.kt`:

```kotlin
package com.benzn.grandtime.devprobe

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmStatsTest {

    /** Little-endian PCM16 from signed sample values. */
    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            b[i * 2] = (s and 0xFF).toByte()
            b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test fun `full-scale square wave is 0 dBFS peak and 0 dBFS rms`() {
        val s = pcmStats(pcm(32767, -32767, 32767, -32767), 8)
        assertEquals(0.0, s.peakDbfs, 0.01)
        assertEquals(0.0, s.rmsDbfs, 0.01)
    }

    @Test fun `half-scale square wave is about -6 dBFS`() {
        val s = pcmStats(pcm(16384, -16384), 4)
        assertEquals(-6.0, s.rmsDbfs, 0.1)
    }

    @Test fun `digital silence reports a floor instead of negative infinity`() {
        val s = pcmStats(pcm(0, 0, 0, 0), 8)
        assertEquals(-120.0, s.rmsDbfs, 0.01)
        assertEquals(-120.0, s.peakDbfs, 0.01)
    }

    @Test fun `clipping fraction counts samples at or above 98 percent of full scale`() {
        // 2 of 4 samples are clipped (32767 and -32700 both exceed 0.98 * 32768 = 32112).
        val s = pcmStats(pcm(32767, 100, -32700, 200), 8)
        assertEquals(50, (s.clippedFraction * 100).roundToInt())
    }

    @Test fun `only the first len bytes are read`() {
        val buf = pcm(16384, -16384, 32767, -32767)
        val s = pcmStats(buf, 4) // first two samples only
        assertEquals(-6.0, s.rmsDbfs, 0.1)
    }

    @Test fun `an odd len ignores the trailing half sample`() {
        val s = pcmStats(pcm(16384, -16384), 3)
        assertEquals(-6.0, s.rmsDbfs, 0.1)
    }
}
```

Create `app/src/testDev/java/com/benzn/grandtime/devprobe/ProbeTakesTest.kt`:

```kotlin
package com.benzn.grandtime.devprobe

import com.benzn.grandtime.capture.MicChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTakesTest {

    @Test fun `there are ten takes, indexed one to ten in order`() {
        assertEquals(10, PROBE_TAKES.size)
        assertEquals((1..10).toList(), PROBE_TAKES.map { it.index })
    }

    @Test fun `names are unique and filename-safe`() {
        assertEquals(PROBE_TAKES.size, PROBE_TAKES.map { it.name }.toSet().size)
        assertTrue(PROBE_TAKES.all { it.name.matches(Regex("[a-z0-9_]+")) })
    }

    @Test fun `take 1 is today's standalone baseline and take 10 repeats it exactly`() {
        assertEquals(com.benzn.grandtime.capture.AudioCaptureConfig.DEFAULT_STANDALONE, PROBE_TAKES[0].config)
        assertEquals(PROBE_TAKES[0].config, PROBE_TAKES[9].config)
    }

    @Test fun `take 7 is today's video baseline`() {
        assertEquals(com.benzn.grandtime.capture.AudioCaptureConfig.DEFAULT_VIDEO, PROBE_TAKES[6].config)
    }

    @Test fun `the two mic-selection takes ask for different physical mics`() {
        assertEquals(MicChoice.FRONT, PROBE_TAKES[2].config.preferredMic)
        assertEquals(MicChoice.BACK, PROBE_TAKES[3].config.preferredMic)
    }

    @Test fun `the voice_communication takes cover both sample rates`() {
        val vc = PROBE_TAKES.filter { it.config.source == 7 } // AudioSource.VOICE_COMMUNICATION
        assertEquals(listOf(16000, 44100), vc.map { it.config.sampleRate })
    }

    @Test fun `the app-effect takes request both NS and AGC and cover both sample rates`() {
        val fx = PROBE_TAKES.filter { it.config.enableNs || it.config.enableAgc }
        assertTrue(fx.all { it.config.enableNs && it.config.enableAgc })
        assertEquals(listOf(16000, 44100), fx.map { it.config.sampleRate })
    }

    @Test fun `the camcorder model check is present at the standalone rate`() {
        val cam = PROBE_TAKES.single { it.config.source == 5 } // AudioSource.CAMCORDER
        assertEquals(16000, cam.config.sampleRate)
    }

    @Test fun `every block is recorded for every take`() {
        assertEquals(listOf(ProbeBlock.S, ProbeBlock.F, ProbeBlock.N), ProbeBlock.entries.toList())
    }
}
```

- [ ] **Step 2: Run the tests and watch them fail**

```bash
cd /c/gt-audio-probe && ./gradlew testDevDebugUnitTest --tests '*devprobe*'
```

Expected: compilation failure — `Unresolved reference: pcmStats` / `PROBE_TAKES`.

- [ ] **Step 3: Write `PcmStats.kt`**

Create `app/src/dev/java/com/benzn/grandtime/devprobe/PcmStats.kt`:

```kotlin
package com.benzn.grandtime.devprobe

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Level summary of a PCM16 mono buffer. dBFS relative to full scale (32768). */
data class PcmStats(val peakDbfs: Double, val rmsDbfs: Double, val clippedFraction: Double)

private const val FULL_SCALE = 32768.0
private const val FLOOR_DBFS = -120.0
private const val CLIP_THRESHOLD = 0.98

/** Computes peak, RMS and clipping over the first [len] bytes of little-endian PCM16 mono.
 *  Digital silence returns [FLOOR_DBFS] rather than -Infinity so the JSON stays parseable. */
fun pcmStats(pcm: ByteArray, len: Int): PcmStats {
    val samples = minOf(len, pcm.size) / 2
    if (samples == 0) return PcmStats(FLOOR_DBFS, FLOOR_DBFS, 0.0)
    var peak = 0.0
    var sumSquares = 0.0
    var clipped = 0
    for (i in 0 until samples) {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()          // sign-extends, giving the signed 16-bit value
        val v = ((hi shl 8) or lo).toShort().toDouble() / FULL_SCALE
        val a = abs(v)
        if (a > peak) peak = a
        if (a >= CLIP_THRESHOLD) clipped++
        sumSquares += v * v
    }
    val rms = sqrt(sumSquares / samples)
    return PcmStats(
        peakDbfs = toDbfs(peak),
        rmsDbfs = toDbfs(rms),
        clippedFraction = clipped.toDouble() / samples,
    )
}

private fun toDbfs(linear: Double): Double =
    if (linear <= 0.0) FLOOR_DBFS else maxOf(FLOOR_DBFS, 20.0 * log10(linear))
```

Note: a full-scale sample is 32767, so `32767 / 32768` is −0.00026 dB; the tests allow 0.01 dB.

- [ ] **Step 4: Write `ProbeTakes.kt`**

Create `app/src/dev/java/com/benzn/grandtime/devprobe/ProbeTakes.kt`:

```kotlin
package com.benzn.grandtime.devprobe

import android.media.MediaRecorder
import com.benzn.grandtime.capture.AudioCaptureConfig
import com.benzn.grandtime.capture.MicChoice

/** One acoustic condition, recorded once per take. The speaker's state changes only between
 *  blocks, which is what keeps friction measurable separately from speech. */
enum class ProbeBlock(val label: String, val instruction: String) {
    S("S", "Speaker ON. Stand still."),
    F("F", "Speaker OFF. March in place, rub the case and clothing."),
    N("N", "Speaker OFF. Say: one two three four five six seven eight nine ten."),
}

data class ProbeTake(val index: Int, val name: String, val config: AudioCaptureConfig)

/**
 * The configurations under test, in run order.
 *
 * Takes 6, 9 and 10 are expected to be uninformative if everything is as predicted, and are
 * included for exactly that reason: 6 checks the gain-table reading that says CAMCORDER is
 * quieter than MIC, 9 turns "AOSP preprocessing may reject 44.1 kHz" into a measurement, and 10
 * repeats take 1 so scene drift over six minutes of marching cannot masquerade as a win.
 */
val PROBE_TAKES: List<ProbeTake> = listOf(
    ProbeTake(1, "mic_16k", AudioCaptureConfig.DEFAULT_STANDALONE),
    ProbeTake(2, "voicecomm_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        source = MediaRecorder.AudioSource.VOICE_COMMUNICATION)),
    ProbeTake(3, "mic_front_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        preferredMic = MicChoice.FRONT)),
    ProbeTake(4, "mic_back_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        preferredMic = MicChoice.BACK)),
    ProbeTake(5, "mic_nsagc_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        enableNs = true, enableAgc = true)),
    ProbeTake(6, "camcorder_16k", AudioCaptureConfig.DEFAULT_STANDALONE.copy(
        source = MediaRecorder.AudioSource.CAMCORDER)),
    ProbeTake(7, "mic_44k", AudioCaptureConfig.DEFAULT_VIDEO),
    ProbeTake(8, "voicecomm_44k", AudioCaptureConfig.DEFAULT_VIDEO.copy(
        source = MediaRecorder.AudioSource.VOICE_COMMUNICATION)),
    ProbeTake(9, "mic_nsagc_44k", AudioCaptureConfig.DEFAULT_VIDEO.copy(
        enableNs = true, enableAgc = true)),
    ProbeTake(10, "mic_16k_repeat", AudioCaptureConfig.DEFAULT_STANDALONE),
)
```

- [ ] **Step 5: Run the tests and watch them pass**

```bash
cd /c/gt-audio-probe && ./gradlew testDevDebugUnitTest --tests '*devprobe*'
```

Expected: 15 tests, all PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/dev/java/com/benzn/grandtime/devprobe/ProbeTakes.kt \
        app/src/dev/java/com/benzn/grandtime/devprobe/PcmStats.kt \
        app/src/testDev/java/com/benzn/grandtime/devprobe/
git commit -m "feat(probe): name the ten configurations and measure a buffer's level"
```

---

### Task 7: The take runner — record, write WAV, write JSON

Records one take to a `.wav` with a header built from the take's own config (not through
`AudioAssembly`, whose header rate is welded to 16 kHz), and a sibling `.json` with the
`OpenedMic` report plus the measured level.

**Files:**
- Create: `app/src/dev/java/com/benzn/grandtime/devprobe/ProbeRunner.kt`

No JVM test: the class is a thin sequencer over `openMic` and file IO. Its two pure
dependencies — `pcmStats` and `PROBE_TAKES` — are tested in Task 6, and the WAV header builder is
tested in the existing `WavHeaderTest`.

**Interfaces:**
- Consumes: `openMic`, `OpenedMic` (Task 3); `WavHeader.riffWav` (existing); `pcmStats`, `PROBE_TAKES`, `ProbeBlock`, `ProbeTake` (Task 6); `MicCapabilities.snapshotJson` (Task 3).
- Produces: `class ProbeRunner(context: Context)` with `suspend fun runBlock(block: ProbeBlock, seconds: Int = 10, gapMs: Long = 3000, onProgress: (ProbeTake, Double) -> Unit): File` returning the output folder, and `fun isMicBusy(): Boolean`.

- [ ] **Step 1: Write `ProbeRunner.kt`**

Create `app/src/dev/java/com/benzn/grandtime/devprobe/ProbeRunner.kt`:

```kotlin
package com.benzn.grandtime.devprobe

import android.content.Context
import android.media.AudioManager
import com.benzn.grandtime.capture.MicCapabilities
import com.benzn.grandtime.capture.WavHeader
import com.benzn.grandtime.capture.jsonObject
import com.benzn.grandtime.capture.jsonString
import com.benzn.grandtime.capture.openMic
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs one block of the probe: every configuration in PROBE_TAKES, back to back, with no
 * operator input between takes so posture cannot drift mid-block.
 *
 * Writes only to app-specific external storage. It never inserts a Room row, enqueues a
 * WorkManager job, or calls the recordings API — the dev flavor points at the test stack, and
 * pushing deliberate noise into the test lake would pollute real data.
 */
class ProbeRunner(private val context: Context) {

    /** A second AudioRecord initializes fine and returns silence under concurrent-capture policy,
     *  so AudioRecord state cannot detect contention. Ask the framework who is recording. */
    fun isMicBusy(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return runCatching { am.activeRecordingConfigurations.isNotEmpty() }.getOrDefault(false)
    }

    suspend fun runBlock(
        block: ProbeBlock,
        seconds: Int = 10,
        gapMs: Long = 3000,
        onProgress: (ProbeTake, Double) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "audioprobe/${stamp}_block${block.label}")
        dir.mkdirs()
        File(dir, "capabilities.json").writeText(MicCapabilities.snapshotJson(context))

        for (take in PROBE_TAKES) {
            runCatching { recordTake(block, take, seconds, dir, onProgress) }
                .onFailure { e ->
                    // A configuration this board refuses is a result, not a crash. Record why and
                    // carry on, so one unsupported take cannot cost the whole block. Same base name
                    // as a successful take (rate included) so the analysis finds it — a refusal
                    // that never reaches the table would read as "not tested".
                    val base = "probe_${block.label}_%02d_%s_%d".format(
                        take.index, take.name, take.config.sampleRate
                    )
                    File(dir, "$base.json").writeText(jsonObject(listOf(
                        "block" to jsonString(block.label),
                        "index" to "${take.index}",
                        "name" to jsonString(take.name),
                        "rate" to "${take.config.sampleRate}",
                        "error" to jsonString(e.message ?: e.javaClass.simpleName),
                    )))
                }
            delay(gapMs)
        }
        dir
    }

    private fun recordTake(
        block: ProbeBlock,
        take: ProbeTake,
        seconds: Int,
        dir: File,
        onProgress: (ProbeTake, Double) -> Unit,
    ) {
        val base = "probe_${block.label}_%02d_%s_%d".format(
            take.index, take.name, take.config.sampleRate
        )
        val pcmFile = File(dir, "$base.pcm")
        val om = openMic(context, take.config)
        var reportJson = "{}"
        var stats: PcmStats
        try {
            om.record.startRecording()
            reportJson = om.reportJson() // routing is only true once recording has started
            val buf = ByteArray(om.bufferBytes)
            val target = take.config.sampleRate.toLong() * 2 * seconds
            var written = 0L
            var peakAll = -120.0
            var sumSquares = 0.0
            var samples = 0L
            var clipped = 0L
            pcmFile.outputStream().buffered().use { out ->
                while (written < target) {
                    val n = om.record.read(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    val s = pcmStats(buf, n)
                    if (s.peakDbfs > peakAll) peakAll = s.peakDbfs
                    // Accumulate energy so the whole-take RMS is exact rather than an average of
                    // per-buffer dB values, which would be wrong.
                    val chunkSamples = n / 2
                    sumSquares += Math.pow(10.0, s.rmsDbfs / 10.0) * chunkSamples
                    samples += chunkSamples
                    clipped += (s.clippedFraction * chunkSamples).toLong()
                    onProgress(take, s.rmsDbfs)
                }
            }
            stats = PcmStats(
                peakDbfs = peakAll,
                rmsDbfs = if (samples == 0L) -120.0
                          else maxOf(-120.0, 10.0 * Math.log10(sumSquares / samples)),
                clippedFraction = if (samples == 0L) 0.0 else clipped.toDouble() / samples,
            )
        } finally {
            om.stopAndRelease()
        }

        // Header from the take's own config: AudioAssembly's header rate is welded to 16 kHz and
        // would mislabel every 44.1 kHz take.
        val wav = File(dir, "$base.wav")
        wav.outputStream().buffered().use { out ->
            out.write(WavHeader.riffWav(pcmFile.length().toInt(), take.config.sampleRate, 1, 16))
            pcmFile.inputStream().buffered().use { it.copyTo(out) }
        }
        pcmFile.delete()

        File(dir, "$base.json").writeText(jsonObject(listOf(
            "block" to jsonString(block.label),
            "index" to "${take.index}",
            "name" to jsonString(take.name),
            "wav" to jsonString(wav.name),
            "seconds" to "$seconds",
            "peakDbfs" to "${stats.peakDbfs}",
            "rmsDbfs" to "${stats.rmsDbfs}",
            "clippedFraction" to "${stats.clippedFraction}",
            "mic" to reportJson,
        )))
    }
}
```

- [ ] **Step 2: Build the dev variant**

```bash
cd /c/gt-audio-probe && ./gradlew compileDevDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/dev/java/com/benzn/grandtime/devprobe/ProbeRunner.kt
git commit -m "feat(probe): record a block of takes to wav and say what each one got"
```

---

### Task 8: The probe UI, the dev manifest, and proof of prod absence

**Files:**
- Create: `app/src/dev/java/com/benzn/grandtime/devprobe/AudioProbeActivity.kt`
- Create: `app/src/dev/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ProbeRunner`, `ProbeBlock`, `ProbeTake` (Tasks 6–7).
- Produces: a second launcher icon, `AudioProbe`, present only in the dev flavor.

- [ ] **Step 1: Write the activity**

Create `app/src/dev/java/com/benzn/grandtime/devprobe/AudioProbeActivity.kt`:

```kotlin
package com.benzn.grandtime.devprobe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Dev-only. Runs one block of the probe per press and writes the takes to app-specific storage.
 *
 * The layout is deliberately plain and vertically scrollable: this screen is only ever read on a
 * 320x427dp F2SP display, where Material3's default spacing silently pushes controls off-screen
 * (see memory grandtime-f2sp-tiny-screen-layout).
 */
class AudioProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A block runs ~2.2 minutes with the operator forbidden to touch the device. If the screen
        // timed out this activity would leave the foreground, and on API 33 a backgrounded app's
        // AudioRecord is silenced — later takes would record digital zeros with no error at all,
        // poisoning the comparison invisibly.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ProbeScreen() {
        val scope = rememberCoroutineScope()
        val runner = remember { ProbeRunner(this) }
        var status by remember { mutableStateOf("Idle") }
        var running by remember { mutableStateOf(false) }
        var granted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
        val ask = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted = it }

        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AudioProbe", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)

            if (!granted) {
                Button(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant microphone")
                }
                return@Column
            }

            for (block in ProbeBlock.entries) {
                Text("${block.label} — ${block.instruction}", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = !running,
                    onClick = {
                        if (runner.isMicBusy()) {
                            status = "Mic is busy — stop recording in the main app first."
                            return@Button
                        }
                        running = true
                        status = "Block ${block.label}: starting"
                        scope.launch {
                            val dir = runCatching {
                                runner.runBlock(block) { take, dbfs ->
                                    status = "Block ${block.label} — take ${take.index} " +
                                        "${take.name}: %.1f dBFS".format(dbfs)
                                }
                            }
                            running = false
                            status = dir.fold(
                                onSuccess = { "Block ${block.label} done -> ${it.absolutePath}" },
                                onFailure = { "Block ${block.label} failed: ${it.message}" },
                            )
                        }
                    },
                ) { Text("Run block ${block.label}") }
            }
        }
    }
}
```

- [ ] **Step 2: Write the dev manifest**

Create `app/src/dev/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <!-- Dev flavor only: a second launcher entry so the probe needs no changes to
             MainActivity or the app's navigation. Absent from the prod APK. -->
        <activity
            android:name="com.benzn.grandtime.devprobe.AudioProbeActivity"
            android:label="AudioProbe"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: Build both flavors and run every test**

```bash
cd /c/gt-audio-probe && ./gradlew assembleDevDebug assembleProdDebug testProdDebugUnitTest testDevDebugUnitTest
```

Expected: BUILD SUCCESSFUL; all tests PASS.

- [ ] **Step 4: Prove the probe is absent from the prod APK**

```bash
cd /c/gt-audio-probe
"$LOCALAPPDATA/Android/Sdk/build-tools/34.0.0/aapt2.exe" dump xmltree \
  app/build/outputs/apk/prod/debug/app-prod-debug.apk --file AndroidManifest.xml | grep -i devprobe
```

Expected: **no output** (grep exits 1). If any `devprobe` reference appears in the prod manifest,
the flavor split is wrong — stop and fix before continuing.

Then confirm it *is* in dev:

```bash
"$LOCALAPPDATA/Android/Sdk/build-tools/34.0.0/aapt2.exe" dump xmltree \
  app/build/outputs/apk/dev/debug/app-dev-debug.apk --file AndroidManifest.xml | grep -ci devprobe
```

Expected: a non-zero count. (If build-tools 34.0.0 is not installed, use whichever version is
present under `$LOCALAPPDATA/Android/Sdk/build-tools/`.)

- [ ] **Step 5: Commit**

```bash
git add app/src/dev/java/com/benzn/grandtime/devprobe/AudioProbeActivity.kt \
        app/src/dev/AndroidManifest.xml
git commit -m "feat(probe): a dev-only launcher that runs one block per press"
```

---

### Task 9: The analysis script

The decision is made from these numbers, so the script that produces them is part of the
deliverable rather than something retyped later. Runs on the dev machine over a folder pulled off
the device; numpy is already installed and no new Python dependency is introduced.

**Files:**
- Create: `tools/audio_probe_analysis.py`

**Interfaces:**
- Consumes: the `probe_{block}_{NN}_{name}_{rate}.wav` + `.json` pairs written by Task 7.
- Produces: the decision table on stdout.

- [ ] **Step 1: Write the script**

Create `tools/audio_probe_analysis.py`:

```python
"""Turn a pulled AudioProbe folder into the decision table.

Usage:  python tools/audio_probe_analysis.py <folder-with-block-S> <folder-with-block-F>

Criteria (docs/superpowers/specs/2026-08-08-audio-array-probe-design.md):
  1. SNR improves by >= 6 dB over take 1
  2. speech-band/full-band ratio drops by < 3 dB vs take 1
  3. clipping fraction < 0.1 %
  4. (block N, judged separately) the counting phrase transcribes no worse than baseline
The run is void unless take 10 reproduces take 1 within 2 dB.

Output is deliberately ASCII only: this runs on a GBK console where non-ASCII prints as mojibake.
"""
import glob, json, os, re, sys, wave
import numpy as np

ROLL_IN_S = 2.0          # discard while AGC converges
SPEECH_BAND = (300, 3400)
NAME_RE = re.compile(r"probe_([SFN])_(\d\d)_([a-z0-9_]+)_(\d+)\.(wav|json)$")

def load(path):
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        d = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    return sr, d[int(ROLL_IN_S * sr):]

def band_rms(d, sr, lo, hi):
    """Band-limited RMS. The Hann window costs a constant ~4.3 dB of coherent gain, so these
    absolute values sit below the per-take dBFS in the JSON. Every criterion is a delta between
    two numbers computed this same way, so the bias cancels -- do not 'reconcile' the two."""
    if len(d) < 1024:
        return 1e-12
    f = np.fft.rfft(d * np.hanning(len(d)))
    fr = np.fft.rfftfreq(len(d), 1 / sr)
    m = (fr >= lo) & (fr < hi)
    return float(np.sqrt((np.abs(f[m]) ** 2).sum() / (len(d) * len(d) / 2)))

def db(x):
    return 20 * np.log10(max(float(x), 1e-12))

def takes(folder):
    """Every take in a block, keyed by index -- including takes that only produced an error JSON,
    which is how a configuration the board refused stays visible instead of reading as untested."""
    out = {}
    for p in sorted(glob.glob(os.path.join(folder, "probe_*.json"))):
        m = NAME_RE.search(os.path.basename(p))
        if not m:
            continue
        meta = json.load(open(p))
        rec = dict(name=m.group(3), rate=int(m.group(4)), meta=meta,
                   error=meta.get("error"), speech=None, full=None, hf=None, mid=None,
                   clip=float(meta.get("clippedFraction", 0.0)))
        wav = p[:-5] + ".wav"
        if rec["error"] is None and os.path.exists(wav):
            sr, d = load(wav)
            rec["sr"] = sr
            rec["speech"] = db(band_rms(d, sr, *SPEECH_BAND))
            rec["full"] = db(band_rms(d, sr, 20, sr // 2))
            if sr > 16000:
                rec["mid"] = db(band_rms(d, sr, 4000, 8000))
                rec["hf"] = db(band_rms(d, sr, 8000, min(sr // 2, 16000)))
        out[int(m.group(2))] = rec
    return out

def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    S, F = takes(sys.argv[1]), takes(sys.argv[2])
    if not S or not F:
        print("no takes found - check the folder paths")
        return 1

    base = 1
    if base not in S or base not in F or S[base]["speech"] is None or F[base]["speech"] is None:
        print("take 1 (the baseline) is missing or failed in block S or F -- every criterion is")
        print("relative to it, so there is nothing to compare against. Re-record the run.")
        for label, src in (("S", S), ("F", F)):
            e = src.get(base, {}).get("error")
            if e:
                print("  block %s take 1 error: %s" % (label, e))
        return 1

    b_snr = S[base]["speech"] - F[base]["speech"]
    b_shape = S[base]["speech"] - S[base]["full"]

    print("%2s %-16s%6s%9s%9s%8s%7s%8s%7s  %s" % (
        "#", "config", "rate", "speech", "noise", "SNR", "dSNR", "dShape", "clip%", "verdict"))
    for idx in sorted(S):
        t = S[idx]
        if t["error"] or idx not in F or F[idx]["error"] or F[idx]["speech"] is None:
            why = t["error"] or (F.get(idx, {}).get("error")) or "missing in the other block"
            print("%2d %-16s%6d  %s" % (idx, t["name"], t["rate"], "NOT MEASURED: " + str(why)))
            continue
        snr = t["speech"] - F[idx]["speech"]
        shape = t["speech"] - t["full"]
        d_snr, d_shape = snr - b_snr, shape - b_shape
        clip = max(t["clip"], F[idx]["clip"]) * 100
        verdict = "PASS" if (d_snr >= 6.0 and d_shape > -3.0 and clip < 0.1) else ""
        if idx == base:
            verdict = "baseline"
        if idx == 10:
            drift = abs(d_snr)
            verdict = "control drift %.1f dB" % drift + ("" if drift <= 2.0 else "  *** RUN VOID ***")
        print("%2d %-16s%6d%9.1f%9.1f%8.1f%+7.1f%+8.1f%7.3f  %s" % (
            idx, t["name"], t["rate"], t["speech"], F[idx]["speech"], snr, d_snr, d_shape, clip, verdict))

    print("")
    print("44.1 kHz takes -- really wideband, or 16 kHz upsampled?")
    print("Judged against take 7 (MIC @44.1k), a genuine wideband capture from the same board in")
    print("the same scene. An absolute threshold does not work (resampler imaging measures around")
    print("-77 dBFS), and neither does an in-take band ratio: these recordings have almost no")
    print("4-8 kHz content to use as a reference, so that would be noise compared against noise.")
    ref = S.get(7)
    if ref is None or ref.get("hf") is None:
        print("  take 7 is missing or failed -- no reference, so this cannot be judged.")
    elif ref["hf"] <= -100:
        print("  take 7 itself has no energy above 8 kHz (%.1f dBFS): the scene carries nothing up"
              % ref["hf"])
        print("  there, so upsampling is INCONCLUSIVE from this run rather than ruled out.")
    else:
        print("  reference: take 7 %s, 8-16 kHz = %.1f dBFS" % (ref["name"], ref["hf"]))
        for idx in sorted(S):
            t = S[idx]
            if idx == 7 or t["rate"] <= 16000 or t.get("hf") is None:
                continue
            rel = t["hf"] - ref["hf"]
            tag = "LIKELY UPSAMPLED FROM 16k" if rel < -20 else "wideband"
            print("  %2d %-16s 8-16k %7.1f dBFS  %+6.1f dB vs take 7  %s" % (
                idx, t["name"], t["hf"], rel, tag))

    errs = [(lbl, i, src[i]) for lbl, src in (("S", S), ("F", F)) for i in sorted(src) if src[i]["error"]]
    if errs:
        print("")
        print("configurations this board refused:")
        for lbl, i, t in errs:
            print("  block %s take %2d %-16s %s" % (lbl, i, t["name"], t["error"]))

    return 0

if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Verify it runs and reports cleanly on an empty input**

```bash
cd /c/gt-audio-probe && python tools/audio_probe_analysis.py /tmp/nope /tmp/nope
```

Expected: prints `no takes found — check the folder paths` and exits 1 (no traceback).

- [ ] **Step 3: Commit**

```bash
git add tools/audio_probe_analysis.py
git commit -m "feat(probe): score the takes against the criteria the spec commits to"
```

---

### Task 10: Install, record, and report

The only task with a human in the loop. Nothing is decided until these numbers exist.

**Files:** none — this task produces measurements, not code.

- [ ] **Step 1: Install the dev APK alongside prod**

```bash
cd /c/gt-audio-probe && ./gradlew assembleDevDebug
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

The dev flavor has `applicationIdSuffix ".dev"`, so it installs beside the prod app rather than
replacing it. Two new icons appear: `devfieldsight` and `AudioProbe`.

- [ ] **Step 2: Set up the scene**

Tape two floor marks ~3 m apart: one for the speaker, one for the operator. Wear the device in its
normal chest position. Copy `Dropbox/temp/fieldsight-audio/probe-source/A_clean_speech_60s.wav`
to the playback device and set a volume that is comfortably audible at the operator's mark. **Do
not move or re-level anything for the rest of the run.**

- [ ] **Step 3: Confirm the mic-busy guard actually fires**

Thirty seconds, because the guard is a claim this plan has not measured. Start a normal recording
in the prod app, then press *Run block S* in `AudioProbe`. Expected: it refuses with "Mic is
busy". If it runs anyway, `getActiveRecordingConfigurations()` does not see across packages here —
note that, stop the prod recording, and treat "nothing else is recording" as an operator
responsibility for the rest of the session. Delete whatever that trial wrote before continuing.

- [ ] **Step 4: Record the three blocks**

Open `AudioProbe`, grant the microphone, then, without changing clothing, posture or speaker
placement between blocks:

1. **Block S** — start the loop playing, stand still on the mark, press *Run block S*. ~2 minutes.
2. **Block F** — **stop the playback**, press *Run block F*, march in place and rub the case and
   clothing continuously for the whole block.
3. **Block N** — playback still stopped, press *Run block N*, and say "one two three four five six
   seven eight nine ten" once per take, at a steady pace, following the on-screen take counter.

- [ ] **Step 5: Pull the results**

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
MSYS_NO_PATHCONV=1 "$ADB" shell ls //sdcard/Android/data/com.benzn.grandtime.dev/files/audioprobe
mkdir -p /c/Users/camil/Dropbox/temp/fieldsight-audio/probe-results
MSYS_NO_PATHCONV=1 "$ADB" pull //sdcard/Android/data/com.benzn.grandtime.dev/files/audioprobe \
  /c/Users/camil/Dropbox/temp/fieldsight-audio/probe-results
```

- [ ] **Step 6: Score it**

```bash
cd /c/gt-audio-probe
python tools/audio_probe_analysis.py \
  /c/Users/camil/Dropbox/temp/fieldsight-audio/probe-results/<stamp>_blockS \
  /c/Users/camil/Dropbox/temp/fieldsight-audio/probe-results/<stamp>_blockF
```

Check the control line first: if take 10 drifted more than 2 dB from take 1, **the run is void** —
re-record rather than reading the table. Then read `capabilities.json` for the fitted microphone
part (`description`), which determines whether this unit is the −41 or the −26 dBFS/Pa variant.

- [ ] **Step 7: Report, then decide**

Post the table. Adopting a winner is a separate change — `DEFAULT_STANDALONE` and/or
`DEFAULT_VIDEO` in `AudioCaptureConfig.kt` — made only after all four criteria hold, and followed
by a confirmation run against `B_site_conversation_60s.wav`.

---

## Self-Review

**Spec coverage.** Single config point → Task 1. Buffer-size and sample-rate coupling hazards →
Task 1 (`bufferFloorBytes`), Task 5 Step 6 (rate guard), Task 7 (probe writes its own header
rather than going through `AudioAssembly`, whose 16 kHz default is why the standalone rate stays
fixed). Address-based mic selection with no silent fallback → Task 2. `OpenedMic` report and its
two documented blind spots → Task 3. Both call sites, handover included → Tasks 4–5. Ten
configurations with the model check, the 44.1 k baseline and the stationarity control → Task 6.
Local-only output, no upload, `getExternalFilesDir` → Task 7. Mic-contention check via
`getActiveRecordingConfigurations` → Task 7. Dev-only launcher and proof of prod absence →
Task 8. Three-block protocol, 2 s roll-in, level-independent shape criterion, spectral test for
the 44.1 k takes → Tasks 9–10. Per-unit `capabilities.json` including the fitted mic part →
Task 3 and Task 10 Step 5.

**Placeholders.** None: every code step carries the full file or the exact before/after text, and
every command is runnable as written.

**Type consistency.** `AudioCaptureConfig(source, sampleRate, bufferFloorBytes, preferredMic,
enableNs, enableAgc)` is constructed in Task 1 and only ever `.copy()`-ed in Task 6.
`openMic(context, config): OpenedMic` (Task 3) is called in Tasks 4, 5 and 7 with that signature.
`OpenedMic.record` / `.bufferBytes` / `.reportJson()` / `.stopAndRelease()` are used consistently
in Tasks 4, 5 and 7. `pcmStats(pcm, len): PcmStats` with fields `peakDbfs` / `rmsDbfs` /
`clippedFraction` (Task 6) is consumed in Task 7 and read back by name from the JSON in Task 9.
`jsonObject` / `jsonString` / `jsonArray` (Task 2) are used in Tasks 3 and 7. `ProbeBlock.label`
and `.instruction` (Task 6) are used in Tasks 7 and 8. The filename format written in Task 7
(`probe_{block}_{NN}_{name}_{rate}.wav`) matches the regex parsed in Task 9.

**One deviation from the spec, deliberate:** the spec called the config field `bufferBytes`; this
plan uses `bufferFloorBytes` because the existing sites compute `max(getMinBufferSize(...),
floor)` and `getMinBufferSize` is device-dependent, so only the floor can be pinned by a JVM test.
Storing an absolute size would have made the "defaults unchanged" test either untestable or
wrong.

**Amended after review.** Every code block was applied to a scratch copy, compiled on both flavors
and run against both suites, so the corrections below are measured rather than argued:

- The worktree had no `local.properties`, so the very first gradle command failed with `SDK
  location not found`. Copied in from the Dropbox checkout and documented in Global Constraints,
  escaping trap included.
- Task 5 Step 4 said to replace "the `stop()`/`release()` pair" in `SegmentRecorder.stop()`. There
  is no pair: the two calls sit nine lines apart, one before the thread joins and one after, each
  carrying a comment explaining why. Collapsing them would have reintroduced a use-after-release.
  Now two precise edits that leave the `stop()` call untouched.
- Task 5 missed two further release sites in `setupAudio()` — the `startAudioPaused` branch, which
  runs on every segment rollover during a Site Voice handover, and the audio-start-failure branch.
  Both would have stranded `audioOpened` holding a released record with live effects, which is the
  very defect the task exists to prevent. Now Step 5.
- Task 8 had nothing keeping the screen on. A block runs ~2.2 minutes untouched; a screen timeout
  backgrounds the activity and API 33 silences a backgrounded `AudioRecord`, so later takes would
  record digital zeros with no error at all. Added `FLAG_KEEP_SCREEN_ON`.
- The 44.1 kHz upsampling test was wrong twice. The original absolute threshold (`hf > -90`)
  labelled a measured upsampled file "genuine" — resampler imaging sits near −77 dBFS. Its first
  replacement, an in-take 4–8 kHz vs 8–16 kHz ratio, failed too, because these recordings carry
  almost no 4–8 kHz energy and it ended up comparing noise against noise. It now judges each
  44.1 kHz take against **take 7**, a genuine wideband capture from the same board in the same
  scene, and reports "inconclusive" when take 7 itself has nothing above 8 kHz. Verified against
  synthetic sinc-upsampled audio: genuine reads −0.0 dB, upsampled −29.7 dB, threshold −20 dB.
- A refused configuration wrote an error JSON the analysis could never find (no rate in the name,
  and the script keyed everything off `.wav` files), so a board that rejected a configuration
  would have read as "not tested". Both ends fixed and verified end-to-end on synthetic data.
- Dropped `AudioRecorder.lastReportJson`: nothing read it, and it was written off the caller
  thread without synchronisation.
- Corrected the existing-test count from 163 (stale, out of CLAUDE.md) to the measured 481; fixed
  Task 5 Step 3's method label (`setupAudio()`, not `prepare()`); guarded the analysis against a
  missing baseline take; made all script output ASCII for this machine's GBK console; documented
  the Hann-window bias so nobody tries to reconcile the table against the per-take JSON; and added
  a Task 10 step that tests the mic-busy guard instead of assuming it.
