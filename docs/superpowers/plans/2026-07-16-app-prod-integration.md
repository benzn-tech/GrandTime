# FieldSight app → prod integration (G1–G5a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the FieldSight mobile app's recordings land in the PRODUCTION lake, registered via the org-api with the in-app selected `siteId`, in the pipeline-parseable format (NZ date, dashed filename, WAV audio) — so field recordings actually reach the customer's site.

**Architecture:** All app-side. Add dev/prod build flavors (release → prod org gateway); the presigned upload flow already carries `siteId` (SP4b) so prod host alone fixes attribution. Fix the two metadata mismatches (NZ-local `startedAt`, dashed filename). Replace the M4A `AudioRecorder` with a WAV (16 kHz mono 16-bit) recorder, which the pipeline ingests; because SP-Ask's `AskRecorder` reuses it, the ask clip becomes WAV and `AskApiClient` switches `format` to `wav`.

**Tech Stack:** Kotlin 2.1 / minSdk=targetSdk 33; Android `AudioRecord` (PCM) + hand-written WAV header; Gradle product flavors; `java.time` (`OffsetDateTime`/`ZoneId`). No new Gradle deps.

## Global Constraints

- Prod org gateway `https://ys94qy2tk0.execute-api.ap-southeast-2.amazonaws.com/prod/api`; test gateway `https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api`. Cognito already prod (`ap-southeast-2_q88pd6XXr` / `4ratjdjonqm17tln6bs2761ci3`) — DO NOT change.
- Attribution is by the recording's explicit `siteId` (→ site → company), NEVER the recorder's role/membership.
- Recording S3 key (server-built from `fileName`): `users/{display}/{video|audio|pictures}/{YYYY-MM-DD}/{Prefix}_{YYYY-MM-DD}_{HH-MM-SS}.{ext}`. Pipeline time regex (BUG-01): `\d{4}-\d{2}-\d{2}_(\d{2})-(\d{2})-(\d{2})`.
- Audio must be WAV 16 kHz mono 16-bit. Video (MP4) + photos (JPG) unchanged.
- ALL new dev content ENGLISH (comments/commits). No new Gradle deps / native libs (armeabi). NZ timezone = `Pacific/Auckland`.
- Any prod-facing acceptance runs ONLY after explicit user sign-off.
- Build: Git Bash `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`; Dropbox-lock `Could not delete` = rerun once. Tests `./gradlew testDebugUnitTest`.

## File Structure

- `app/build.gradle.kts` — add `flavorDimensions("env")` + `productFlavors { prod; dev }` with per-flavor `ORG_API_BASE_URL`. Remove the single `ORG_API_BASE_URL` from `defaultConfig`.
- `app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt` — `iso8601()` → NZ-local offset.
- `app/src/main/java/com/benzn/grandtime/capture/MediaStorage.kt` — timestamp format → dashed; AUDIO ext `m4a` → `wav`.
- `app/src/main/java/com/benzn/grandtime/capture/AudioRecorder.kt` — rewrite to WAV via `AudioRecord`; add a pure `WavHeader` helper (new file `capture/WavHeader.kt`) for JVM testing.
- `app/src/main/java/com/benzn/grandtime/net/AskApiClient.kt` — ask clip `format` `"m4a"` → `"wav"`.
- `UploadWorker.contentTypeFor` — audio content type → `audio/wav` (already keys off the `.wav` extension; verify).

## Sequencing

Task 1 (merge SP-Ask) is a prerequisite done on `main`. Tasks 2–5 create branch `feature/app-prod-integration` off the post-merge `main`. Task 6 is device acceptance (dev flavor first; prod flavor ONLY after user sign-off).

---

### Task 1: Prerequisite — finish & merge SP-Ask to main

**Files:** none in this repo's app source (branch/merge operation).

- [ ] **Step 1: Quick-review the SP-Ask read-timeout fix**

The only un-reviewed SP-Ask commit is `285f845` on `feature/sp-ask` (OkHttp read/write timeout bump in `RecordingsApiClient.kt`, device-proven). Read `git show 285f845` — confirm it only adds `.connectTimeout/.readTimeout/.writeTimeout` to the shared `OK_HTTP` builder and nothing else. It is safe (only lengthens timeouts).

- [ ] **Step 2: Merge SP-Ask mobile branch to main**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
git checkout main && git pull
git merge --ff-only feature/sp-ask   # or a merge commit if main advanced
./gradlew testDebugUnitTest          # expect the SP-Ask suite green (160+)
git push origin main
git branch -d feature/sp-ask
```
(Backend SP-Ask is already on `develop`/fieldsight-test — no action here. If prod-voice gating is wanted before a develop→main promotion, that is the separately-deferred decision, not this task.)

- [ ] **Step 3: Branch for this plan**

```bash
git checkout -b feature/app-prod-integration
```

---

### Task 2: G1 — dev/prod build flavors (release → prod)

**Files:**
- Modify: `app/build.gradle.kts` (defaultConfig `buildConfigField ORG_API_BASE_URL` line ~21; add `flavorDimensions`/`productFlavors`).

**Interfaces:**
- Produces: `BuildConfig.ORG_API_BASE_URL` resolved per flavor (`prod` = ys94qy2tk0, `dev` = wdsgobb7b0). Consumed by `CoreService`/`UploadWorker`/`AskApiClient` (unchanged call sites).

- [ ] **Step 1: Remove the hardcoded ORG_API_BASE_URL from defaultConfig and add flavors**

In `app/build.gradle.kts`, delete the `defaultConfig` line:
```kotlin
buildConfigField("String", "ORG_API_BASE_URL", "\"https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api\"")
```
Add, inside `android { }` (after `defaultConfig`):
```kotlin
    flavorDimensions += "env"
    productFlavors {
        create("prod") {
            dimension = "env"
            // Production org gateway — the customer lake. Shipping/release build.
            buildConfigField("String", "ORG_API_BASE_URL", "\"https://ys94qy2tk0.execute-api.ap-southeast-2.amazonaws.com/prod/api\"")
        }
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"   // dev installs side-by-side, never overwrites the prod app
            // Test gateway (fieldsight-test) — safe testing + SP-Ask voice endpoint.
            buildConfigField("String", "ORG_API_BASE_URL", "\"https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api\"")
        }
    }
```
(Cognito buildConfigFields stay in `defaultConfig` — shared pool. `prod` is listed first so it is the default-selected flavor.)

- [ ] **Step 2: Build both flavors**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleProdDebug assembleDevDebug
```
Expected: BUILD SUCCESSFUL; two APKs under `app/build/outputs/apk/{prod,dev}/debug/`. (Dropbox-lock rerun once.)

- [ ] **Step 3: Verify the resolved host per flavor**

```bash
grep -r "ORG_API_BASE_URL" app/build/generated/source/buildConfig/prod/debug/ app/build/generated/source/buildConfig/dev/debug/
```
Expected: prod BuildConfig contains `ys94qy2tk0`; dev contains `wdsgobb7b0`.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(build): dev/prod flavors — release build targets prod org gateway"
```

---

### Task 3: G3 + G4 — NZ-local `startedAt` and dashed filename (pipeline-parseable)

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt` (`iso8601()` ~line 132).
- Modify: `app/src/main/java/com/benzn/grandtime/capture/MediaStorage.kt` (SimpleDateFormat ~line 28).
- Test: `app/src/test/java/com/benzn/grandtime/upload/UploadWorkerDateTest.kt` (new); extend/create a MediaStorage filename test if the class is JVM-constructible, else assert the format string in a small extracted helper.

**Interfaces:**
- Produces: `iso8601(millis): String` now NZ-local offset (e.g. `2026-07-16T09:50:00+12:00`); `MediaStorage` filenames match `..._YYYY-MM-DD_HH-MM-SS.ext`.

- [ ] **Step 1: Write the failing NZ-date test**

`app/src/test/java/com/benzn/grandtime/upload/UploadWorkerDateTest.kt`:
```kotlin
package com.benzn.grandtime.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadWorkerDateTest {
    // 2026-07-16T00:30:00Z is 2026-07-16 12:30 NZST (+12) — same NZ day, but a naive UTC
    // date-folder (startedAt[:10]) would read 2026-07-16 here; the failing case is the evening:
    // 2026-07-15T13:00:00Z = 2026-07-16 01:00 NZST → NZ date must be 2026-07-16, not 2026-07-15.
    @Test fun `iso8601 uses NZ-local date across the UTC-evening boundary`() {
        val utcEvening = java.time.Instant.parse("2026-07-15T13:00:00Z").toEpochMilli()
        val s = iso8601(utcEvening)
        assertEquals("2026-07-16", s.substring(0, 10))
        assert(s.endsWith("+12:00") || s.endsWith("+13:00")) { "expected NZ offset, got $s" }
    }
}
```

- [ ] **Step 2: Run it, verify RED**

```bash
./gradlew testDebugUnitTest --tests "com.benzn.grandtime.upload.UploadWorkerDateTest"
```
Expected: FAIL — current `iso8601` returns UTC `...2026-07-15T13:00:00Z`, substring `2026-07-15`.

- [ ] **Step 3: Implement NZ-local iso8601**

In `UploadWorker.kt`, replace:
```kotlin
internal fun iso8601(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
```
with:
```kotlin
// NZ-local ISO so the server derives the date folder (startedAt[:10]) in NZ, not UTC — a UTC
// evening is already the next NZ day; a UTC-based folder was off by one (G3).
internal fun iso8601(epochMillis: Long): String =
    java.time.OffsetDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(epochMillis),
        java.time.ZoneId.of("Pacific/Auckland"),
    ).toString()
```

- [ ] **Step 4: Run it, verify GREEN**

```bash
./gradlew testDebugUnitTest --tests "com.benzn.grandtime.upload.UploadWorkerDateTest"
```
Expected: PASS.

- [ ] **Step 5: Dashed filename in MediaStorage**

In `MediaStorage.kt`, change the timestamp format (device-local timezone stays — already NZ):
```kotlin
// FROM:
val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startMillis))
// TO (dashed, so the pipeline BUG-01 regex \d{4}-\d{2}-\d{2}_(\d{2})-(\d{2})-(\d{2}) parses the time):
val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startMillis))
```

- [ ] **Step 6: Filename-format test (if MediaStorage is JVM-testable; else assert via a tiny extracted function)**

If `MediaStorage.fileFor(...)`/naming can be constructed in a JVM test (no Android deps in the naming path), add `MediaStorageNameTest` asserting the produced name matches `Regex("""^.+_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.\w+$""")`. If the naming touches Android `Context`/storage, extract the pure stamp+name construction into an internal function and test that. Do NOT fake Android storage. State in the report which path you took.

- [ ] **Step 7: Full suite + commit**

```bash
./gradlew testDebugUnitTest && ./gradlew assembleProdDebug
git add app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt app/src/main/java/com/benzn/grandtime/capture/MediaStorage.kt app/src/test/java/com/benzn/grandtime/upload/UploadWorkerDateTest.kt
git commit -m "fix(upload): NZ-local startedAt + dashed filename for pipeline time parsing (G3/G4)"
```

---

### Task 4: G5a — audio output WAV (16 kHz mono 16-bit) + SP-Ask format follow

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/WavHeader.kt` (pure, JVM-testable).
- Modify: `app/src/main/java/com/benzn/grandtime/capture/AudioRecorder.kt` (rewrite internals to `AudioRecord` + WAV; keep the public interface `start(file): Boolean` / `stop(): Boolean` / `isRecording`).
- Modify: `app/src/main/java/com/benzn/grandtime/capture/MediaStorage.kt` (AUDIO ext `m4a` → `wav`, ~line 20).
- Modify: `app/src/main/java/com/benzn/grandtime/net/AskApiClient.kt` (ask `format` `"m4a"` → `"wav"`).
- Verify: `app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt` `contentTypeFor` (already returns `audio/wav` when the extension is `wav` — confirm).
- Test: `app/src/test/java/com/benzn/grandtime/capture/WavHeaderTest.kt` (new).

**Interfaces:**
- Consumes: `AudioRecord` (Android). Produces: `WavHeader.riffWav(pcmLen, sampleRate=16000, channels=1, bits=16): ByteArray` (44-byte header); `AudioRecorder.start(file)/stop()` unchanged signatures, now writing a valid WAV file.

- [ ] **Step 1: Write the failing WAV-header test**

`app/src/test/java/com/benzn/grandtime/capture/WavHeaderTest.kt`:
```kotlin
package com.benzn.grandtime.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {
    @Test fun `44-byte RIFF WAVE header for 16k mono 16-bit`() {
        val pcmLen = 32000 // 1s of 16k mono 16-bit
        val h = WavHeader.riffWav(pcmLen, sampleRate = 16000, channels = 1, bits = 16)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(h, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(h, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(h, 36, 4, Charsets.US_ASCII))
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcmLen, bb.getInt(4))       // RIFF chunk size
        assertEquals(16, bb.getInt(16))               // fmt subchunk size
        assertEquals(1.toShort(), bb.getShort(20))    // PCM
        assertEquals(1.toShort(), bb.getShort(22))    // mono
        assertEquals(16000, bb.getInt(24))            // sample rate
        assertEquals(16000 * 1 * 16 / 8, bb.getInt(28)) // byte rate
        assertEquals((1 * 16 / 8).toShort(), bb.getShort(32)) // block align
        assertEquals(16.toShort(), bb.getShort(34))   // bits
        assertEquals(pcmLen, bb.getInt(40))           // data size
    }
}
```

- [ ] **Step 2: Run it, verify RED**

```bash
./gradlew testDebugUnitTest --tests "com.benzn.grandtime.capture.WavHeaderTest"
```
Expected: FAIL — `WavHeader` unresolved.

- [ ] **Step 3: Implement WavHeader**

`app/src/main/java/com/benzn/grandtime/capture/WavHeader.kt`:
```kotlin
package com.benzn.grandtime.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure 44-byte RIFF/WAVE (PCM) header builder — the pipeline ingests .wav. */
object WavHeader {
    fun riffWav(pcmLen: Int, sampleRate: Int = 16000, channels: Int = 1, bits: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + pcmLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)                       // PCM fmt chunk size
        bb.putShort(1)                      // audio format = PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bits.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(pcmLen)
        return bb.array()
    }
}
```

- [ ] **Step 4: Run it, verify GREEN**

```bash
./gradlew testDebugUnitTest --tests "com.benzn.grandtime.capture.WavHeaderTest"
```
Expected: PASS.

- [ ] **Step 5: Rewrite AudioRecorder to WAV (keep the public interface)**

Replace `AudioRecorder.kt` body with an `AudioRecord`-based PCM capture writing a WAV file. It records to a temp `.pcm`, then on stop prepends the header into the target `file` (streaming PCM to temp keeps memory bounded for long recordings):
```kotlin
package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.concurrent.thread

/** Standalone recorder: WAV 16 kHz mono 16-bit PCM (the pipeline ingests .wav). Public
 *  interface unchanged (start/stop/isRecording) — also reused by SP-Ask's AskRecorder. */
class AudioRecorder(private val context: Context) {
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var pcmTmp: File? = null
    private var target: File? = null

    val isRecording: Boolean get() = running

    @android.annotation.SuppressLint("MissingPermission") // caller (preflight) ensures RECORD_AUDIO
    fun start(file: File): Boolean = try {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(minBuf, sr * 2) // >= 1s
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        val tmp = File(file.parentFile, file.nameWithoutExtension + ".pcm")
        record = rec; target = file; pcmTmp = tmp; running = true
        rec.startRecording()
        worker = thread(name = "audio-pcm") {
            tmp.outputStream().buffered().use { out ->
                val b = ByteArray(buf)
                while (running) {
                    val n = rec.read(b, 0, b.size)
                    if (n > 0) out.write(b, 0, n)
                }
            }
        }
        true
    } catch (e: Exception) {
        cleanup(); false
    }

    /** Stops capture and writes the WAV (header + PCM) to the target file. */
    fun stop(): Boolean = try {
        running = false
        worker?.join(2000)
        record?.apply { stop(); release() }
        record = null
        val tmp = pcmTmp; val out = target
        var ok = false
        if (tmp != null && out != null && tmp.exists()) {
            val pcmLen = tmp.length().toInt()
            out.outputStream().buffered().use { o ->
                o.write(WavHeader.riffWav(pcmLen))
                tmp.inputStream().buffered().use { it.copyTo(o) }
            }
            ok = out.length() > 44
        }
        tmp?.delete()
        pcmTmp = null; target = null
        ok
    } catch (e: Exception) {
        cleanup(); false
    }

    private fun cleanup() {
        running = false
        runCatching { worker?.join(1000) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { pcmTmp?.delete() }
        pcmTmp = null; target = null
    }
}
```

- [ ] **Step 6: MediaStorage AUDIO ext → wav**

In `MediaStorage.kt`, change the AUDIO enum entry:
```kotlin
// FROM:
AUDIO("audio", "AUD", "m4a"),
// TO:
AUDIO("audio", "AUD", "wav"),
```

- [ ] **Step 7: AskApiClient format → wav (SP-Ask follow)**

In `AskApiClient.kt`, change the ask request `format` from `"m4a"` to `"wav"` (the ask clip is now WAV since `AskRecorder` reuses `AudioRecorder`). Find the `"m4a"` literal in the request body construction and set it to `"wav"`. Update any test asserting `format=="m4a"` to `"wav"`.

- [ ] **Step 8: Verify contentTypeFor**

Confirm `UploadWorker.contentTypeFor` returns `audio/wav` for a `.wav` audio record (it already keys off the extension: `if (...ext == "wav") "audio/wav" else "audio/mp4"`). No change if so; note in report.

- [ ] **Step 9: Full suite + build**

```bash
./gradlew testDebugUnitTest && ./gradlew assembleProdDebug assembleDevDebug
```
Expected: green (WavHeaderTest + updated AskApiClient test + existing suite). Note: `AudioRecorder`'s real mic capture is device-verified in Task 5, not JVM-tested.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/WavHeader.kt app/src/main/java/com/benzn/grandtime/capture/AudioRecorder.kt app/src/main/java/com/benzn/grandtime/capture/MediaStorage.kt app/src/main/java/com/benzn/grandtime/net/AskApiClient.kt app/src/test/java/com/benzn/grandtime/capture/WavHeaderTest.kt
git commit -m "feat(capture): audio records WAV 16k mono (pipeline-ingestible); SP-Ask ask clip -> wav (G5a)"
```

---

### Task 5: Device acceptance (dev flavor; prod flavor ONLY after user sign-off)

**Files:** none (verification).

- [ ] **Step 1: dev-flavor device check (no prod risk)**

Install `assembleDevDebug` on F2S202503103054. Confirm `com.corget` disabled (`adb shell pm list packages -d | grep corget`). Record a short audio (physical AUDIO key, user hands-on). Pull the file: it must be `.wav`, 16 kHz mono 16-bit (`ffprobe`), named `{prefix}_YYYY-MM-DD_HH-MM-SS.wav`. Confirm the upload created a `recordings` row in the TEST stack with the selected `siteId`, filed under the NZ-date folder, and the pipeline transcribes it (proves format+name are consumed). Re-verify SP-Ask voice still works end-to-end (ask clip is now WAV → STT).

- [ ] **Step 2: Regression pass (dev flavor)**

Video (MP4) + photo (JPG) still record/upload; existing capture/keymap/LED/preview unaffected.

- [ ] **Step 3: PROD acceptance — REQUIRES EXPLICIT USER SIGN-OFF FIRST**

Only after the user confirms: install `assembleProdDebug`, select the Ellesmere project, record one real clip, and confirm it lands in the PROD lake `fieldsight-data-509194952652` attributed to Ellesmere (`site_id 2f6b0776-...`). This writes real data to the customer lake — do not run without sign-off.

- [ ] **Step 4: Finish the branch**

Per superpowers:finishing-a-development-branch — merge `feature/app-prod-integration` to main + push after acceptance.

---

## Self-Review

**Spec coverage:** G1 (Task 2 flavors), G2 (no-op, verified in Task 5 acceptance), G3 (Task 3 iso8601), G4 (Task 3 filename), G5a (Task 4 WAV + AskApiClient), prerequisite SP-Ask merge (Task 1), multi-tenant siteId invariant (relies on existing G2 flow, verified in acceptance). Phase 2 rename explicitly out of this plan (separate). G5b out of scope (pipeline). All covered.

**Placeholder scan:** No TBD/TODO. Task 3 Step 6 and Task 4 Step 7 name concrete conditional paths (JVM-testable extraction vs device-only; find the `"m4a"` literal) rather than placeholders. Real code in every code step.

**Type consistency:** `iso8601(Long): String` used consistently; `WavHeader.riffWav(pcmLen, sampleRate, channels, bits)` defined in Task 4 Step 3, consumed in Step 5; `AudioRecorder.start(file)/stop()/isRecording` interface preserved so `AskRecorder`/standalone callers are unchanged; `AskApiClient` `format` string is the only ask-side change.
