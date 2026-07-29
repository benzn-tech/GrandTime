# P0-a Chunk-Session Filename Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stamp the device's existing `session_id` + segment index into the uploaded recording's wire file name so the backend can durably group audio/video chunks into one recording session.

**Architecture:** The device already mints a `session_id` per record-press (`CaptureCore.newId`) and a 1-based segment index for every ~1-min video segment and audio segment, and stores both on each `CaptureRecord` DB row. The backend already parses `_sid{32hex}_c{NNNN}` tokens out of the uploaded key's base name (verified: `transcript_utils.py` / `lambda_vad.py` on `origin/develop`+`origin/main`, both stacks). So this change is small and device-only: (1) make the minted `session_id` 32 lowercase hex with no hyphens, and (2) at upload time build the `UploadUrlReq.fileName` by appending `_sid{32hex}_c{NNNN}` to the local file's stem. Local file names and the audio segmentation path are untouched; the transform reads the authoritative `sessionId` + `segmentIndex` already on the DB row, so it applies uniformly to video and audio and falls back to the plain name for photos.

**Tech Stack:** Kotlin, Android, JUnit4 (existing `app/src/test/...` unit tests), Gradle. No new dependencies (device is armeabi 32-bit — pure-Kotlin only).

## Global Constraints

- **`session_id` = exactly 32 lowercase hex chars, no hyphens** — backend regex is `_sid([0-9a-f]{32})`. A hyphenated UUID will NOT match and silently falls back to "no session".
- **Preserve the `{YYYY-MM-DD}_{HH-MM-SS}` shape exactly** in the file name (backend BUG-01 time parser). This plan only *appends* tokens after the existing stem; it never rewrites the date/time.
- **`c{NNNN}` is 0-based, zero-padded to 4, monotonic within a session, first chunk = `c0000`.** The device segment index is 1-based (video `CaptureCore` starts at 1; audio `AudioRecorder` starts at 1), so the chunk token = `segmentIndex - 1`.
- **Scope: audio + video chunks only.** Photos (`segmentIndex == null`) and any row without a valid session id keep their plain name — the backend treats an absent token as "no session", so the fallback is safe and back-compatible with rows recorded before this change.
- **No new Gradle/native dependencies.** Pure Kotlin, standard library only.
- Build/test env: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` then `./gradlew ...` from the repo root. Dropbox occasionally holds a build lock (`Could not delete ...build...`) — just re-run once.

---

### Task 1: `ChunkNaming` pure helper (session id + wire file name)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/capture/ChunkNaming.kt`
- Test: `app/src/test/java/com/benzn/grandtime/capture/ChunkNamingTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin).
- Produces (later tasks rely on these exact signatures):
  - `object ChunkNaming`
  - `fun ChunkNaming.sessionId(raw: String): String` — hyphen-stripped, lowercased.
  - `fun ChunkNaming.wireFileName(localFileName: String, sessionId: String?, segmentIndex: Int?): String` — tokenized wire name, or `localFileName` unchanged when the session id isn't valid 32-hex or `segmentIndex` is null/< 1.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/benzn/grandtime/capture/ChunkNamingTest.kt`:

```kotlin
package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkNamingTest {

    private val sid = "9f8c1e2a4b6d47f0a1b2c3d4e5f60718" // 32 hex

    @Test fun sessionId_strips_hyphens_and_lowercases() {
        assertEquals(sid, ChunkNaming.sessionId("9F8C1E2A-4B6D-47F0-A1B2-C3D4E5F60718"))
    }

    @Test fun wireFileName_video_segment1_is_c0000() {
        assertEquals(
            "ben_ucpk_2026-07-29_11-01-06_sid${sid}_c0000.mp4",
            ChunkNaming.wireFileName("ben_ucpk_2026-07-29_11-01-06.mp4", sid, 1),
        )
    }

    @Test fun wireFileName_video_segment12_is_c0011() {
        assertEquals(
            "ben_ucpk_2026-07-29_11-01-06_sid${sid}_c0011.mp4",
            ChunkNaming.wireFileName("ben_ucpk_2026-07-29_11-01-06.mp4", sid, 12),
        )
    }

    @Test fun wireFileName_audio_keeps_wav_extension() {
        assertEquals(
            "ben_ucpk_2026-07-29_11-01-06_sid${sid}_c0000.wav",
            ChunkNaming.wireFileName("ben_ucpk_2026-07-29_11-01-06.wav", sid, 1),
        )
    }

    @Test fun wireFileName_strips_hyphens_from_legacy_uuid_session_id() {
        assertEquals(
            "a_sid${sid}_c0000.mp4",
            ChunkNaming.wireFileName("a.mp4", "9f8c1e2a-4b6d-47f0-a1b2-c3d4e5f60718", 1),
        )
    }

    @Test fun wireFileName_falls_back_when_segmentIndex_null() { // e.g. photos
        assertEquals(
            "img_2026-07-29_11-01-06.jpg",
            ChunkNaming.wireFileName("img_2026-07-29_11-01-06.jpg", sid, null),
        )
    }

    @Test fun wireFileName_falls_back_when_sessionId_not_32hex() {
        assertEquals("a.mp4", ChunkNaming.wireFileName("a.mp4", "not-hex", 1))
    }

    @Test fun wireFileName_collision_suffixed_name_still_tokenizes() {
        // MediaStorage appends _N on name collisions; token still lands after the stem.
        assertEquals(
            "ben_ucpk_2026-07-29_11-01-06_1_sid${sid}_c0000.mp4",
            ChunkNaming.wireFileName("ben_ucpk_2026-07-29_11-01-06_1.mp4", sid, 1),
        )
    }

    @Test fun wireFileName_no_extension_appends_token_and_no_dot() {
        assertEquals("stemonly_sid${sid}_c0000", ChunkNaming.wireFileName("stemonly", sid, 1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.capture.ChunkNamingTest"`
Expected: FAIL — compilation error, `ChunkNaming` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/benzn/grandtime/capture/ChunkNaming.kt`:

```kotlin
package com.benzn.grandtime.capture

/**
 * Wire-format naming for the mobile↔backend chunk-session contract
 * (docs/mobile-client-session-contract-design.md). The backend groups a recording session by
 * parsing two tokens out of the uploaded raw-media S3 key's base name:
 *   - `_sid{32 lowercase hex}` — the session id (backend regex `_sid([0-9a-f]{32})`)
 *   - `_c{NNNN}`               — zero-based, zero-padded chunk index within the session
 * Full grammar: `{device}_{YYYY-MM-DD}_{HH-MM-SS}_sid{32hex}_c{NNNN}.{ext}`.
 * Pure + side-effect-free so it is fully unit-testable.
 */
object ChunkNaming {

    private val HEX32 = Regex("^[0-9a-f]{32}$")

    /** Normalizes a UUID (or any id) to the backend's required 32 lowercase hex form, no hyphens. */
    fun sessionId(raw: String): String = raw.replace("-", "").lowercase()

    /**
     * Builds the wire file name sent as `UploadUrlReq.fileName` (the backend uses it as the raw-media
     * S3 key's base name). Appends `_sid{32hex}_c{NNNN}` to the local file's stem, preserving the
     * existing `{prefix}_{YYYY-MM-DD}_{HH-MM-SS}` shape (BUG-01) and the extension.
     *
     * @param localFileName on-disk name, e.g. `ben_ucpk_2026-07-29_11-01-06.mp4`
     * @param sessionId     the recording's session id (hyphens stripped + validated here)
     * @param segmentIndex  1-based capture segment index; chunk token = segmentIndex - 1
     * @return the tokenized wire name, or [localFileName] unchanged when the session id is not valid
     *         32-hex or the segment index is null/< 1 (e.g. photos). The backend treats an absent
     *         token as "no session", so the fallback is safe and back-compatible.
     */
    fun wireFileName(localFileName: String, sessionId: String?, segmentIndex: Int?): String {
        val sid = sessionId?.replace("-", "")?.lowercase()?.takeIf { it.matches(HEX32) } ?: return localFileName
        val idx = segmentIndex?.takeIf { it >= 1 } ?: return localFileName
        val dot = localFileName.lastIndexOf('.')
        val stem = if (dot >= 0) localFileName.substring(0, dot) else localFileName
        val ext = if (dot >= 0) localFileName.substring(dot) else "" // includes the leading '.'
        val chunk = (idx - 1).toString().padStart(4, '0')
        return "${stem}_sid${sid}_c${chunk}${ext}"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.capture.ChunkNamingTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/ChunkNaming.kt \
        app/src/test/java/com/benzn/grandtime/capture/ChunkNamingTest.kt
git commit -m "feat(capture): ChunkNaming helper for _sid/_c wire file names"
```

---

### Task 2: Mint 32-hex session id and emit tokenized wire file name

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt:58` (the `newId` lambda)
- Modify: `app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt:66` (the `fileName` field) + add an import

**Interfaces:**
- Consumes: `ChunkNaming.sessionId(String)` and `ChunkNaming.wireFileName(String, String?, Int?)` from Task 1.
- Produces: the uploaded recording key now carries `_sid{32hex}_c{NNNN}`; no new public symbols.

- [ ] **Step 1: Make the minted session id 32-hex (no hyphens)**

In `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`, line 58, change:

```kotlin
    private val core = CaptureCore(clock = System::currentTimeMillis, newId = { UUID.randomUUID().toString() })
```

to:

```kotlin
    private val core = CaptureCore(clock = System::currentTimeMillis, newId = { ChunkNaming.sessionId(UUID.randomUUID().toString()) })
```

(`ChunkNaming` is in the same package `com.benzn.grandtime.capture`, so no import is needed.)

- [ ] **Step 2: Build the wire file name from the DB row at upload time**

In `app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt`, add the import near the other `com.benzn.grandtime` imports (top of file, keep alphabetical with the existing `import com.benzn.grandtime.net.UploadUrlReq`):

```kotlin
import com.benzn.grandtime.capture.ChunkNaming
```

Then change line 66 inside the `UploadUrlReq(...)` builder from:

```kotlin
                fileName = record.fileName,
```

to:

```kotlin
                // Wire name carries the session/chunk tokens the backend groups on; local file name
                // and audio segmentation are untouched. Falls back to record.fileName for photos /
                // legacy rows without a valid session id (ChunkNaming.wireFileName).
                fileName = ChunkNaming.wireFileName(record.fileName, record.sessionId, record.segmentIndex),
```

- [ ] **Step 3: Run the full unit test suite (nothing regressed)**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (existing suite + the 9 new `ChunkNamingTest` cases). If it fails once with a Dropbox `Could not delete` lock, re-run.

- [ ] **Step 4: Build the dev APK**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug`
Expected: BUILD SUCCESSFUL. (Re-run once if the Dropbox build lock trips it.)

- [ ] **Step 5: On-device verification of the wire key (temporary log)**

The wire file name is only visible at upload time. Add a one-line temporary log to confirm it, then remove it.

In `UploadWorker.kt`, immediately after the `val req = UploadUrlReq(...)` block (right after its closing `)`), add:

```kotlin
            android.util.Log.i("GrandTime", "upload wire fileName=${req.fileName}") // TEMP verify P0-a
```

Rebuild + install, record a short video (physical VIDEO key short-press, or `adb shell am broadcast -a lolaage.video1.down` then `... .up`), stop after a few seconds, wait for the upload attempt, then:

Run: `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" logcat -d -s GrandTime | grep "upload wire fileName"`
Expected: a line whose fileName matches `..._YYYY-MM-DD_HH-MM-SS_sid[0-9a-f]{32}_c0000.mp4` (and `c0001`, ... for later segments). If the device has no network, the upload-url call still runs and logs the wire name before failing — that is sufficient to verify naming.

Then REMOVE the temporary log line and rebuild.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt \
        app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt
git commit -m "feat(upload): send _sid/_c chunk-session tokens in the recording wire name"
```

---

## Notes / out of scope (P0 follow-ups, not this plan)

- `*_manifest.json` per-session file, `session_open`/`session_close` best-effort calls, Pause/End UX, and store-and-forward hardening are separate P0 steps. The backend does **not** consume the manifest by design, so shipping just this filename contract already lets the backend group sessions.
- Photos are intentionally excluded (`segmentIndex == null` → fallback to plain name). They are stills, not session chunks.
- The file-name time token stays the local file's creation stamp (video = exact segment start; audio ≈ segment start, well within the backend's BUG-37 skew tolerance since it drives grouping on relative deltas). The exact per-chunk `startedAt` is also sent separately as `UploadUrlReq.startedAt`.
- Existing un-uploaded DB rows carry hyphenated UUID session ids; `wireFileName` strips hyphens defensively, so they still upload with a valid 32-hex token — no DB migration needed.
