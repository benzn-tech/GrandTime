# P0-b Best-Effort Session Open/Close Calls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On record-start and record-stop, fire a best-effort `session_open` / `session_close` call to the org API so the backend can promptly start the rolling summary and arm its stop-grace timer — without ever blocking recording.

**Architecture:** A new `SessionsApiClient` (mirrors the existing `RecordingsApiClient`: injectable `HttpFns`, camelCase JSON body, pure body-building) posts to `POST {ORG_API_BASE_URL}/org/sessions/{sid}/open` and `.../close`. `CaptureManager` fires these fire-and-forget on `scope.launch(Dispatchers.IO)` at session boundaries — open when video segment 1 or audio recording starts, close when recording returns to Idle — sourcing the idToken via `(context.applicationContext as GrandTimeApp).authManager.freshIdToken()`. Failures are swallowed: correctness never depends on these calls (the backend reconstructs sessions from the uploaded `_sid/_c` chunks + close-by-inactivity), so there is no retry queue.

**Tech Stack:** Kotlin, Android, OkHttp (via the existing `HttpFns`/`RealHttp`), JUnit4 (existing `app/src/test/...`). No new dependencies.

## Global Constraints

- **Endpoints (verified on backend `origin/main` `src/lambda_org_api.py`):**
  - `POST {ORG_API_BASE_URL}/org/sessions/{sid}/open` — body `{"startedAt": <ISO8601>, "kind": "audio"|"video", "siteId": <id?>}`. Idempotent server-side.
  - `POST {ORG_API_BASE_URL}/org/sessions/{sid}/close` — body `{"intent": "idle"|"end", "endedAt": <ISO8601>}`. Returns 404 if the session was never opened (by open OR first chunk) — that is fine for a best-effort call.
- **Field names are camelCase** (`startedAt`, `endedAt`, `kind`, `siteId`, `intent`) — the design doc's `started_at`/`ended_at` was wrong; the backend reads `body.get("startedAt")` / `body.get("endedAt")`.
- **`sid` = 32 lowercase hex, no hyphens** (same value already minted by `ChunkNaming.sessionId`, stored on `CaptureRecord.sessionId`).
- **Auth:** raw idToken in the `Authorization` header (NO `Bearer` prefix) — identical to `RecordingsApiClient` (uses `HttpFns.postJson(url, authToken, body)`).
- **`intent` is always `"idle"` in this slice.** The deliberate-End (`"end"`) intent + Pause/End UX is P0-c; do not add End handling here.
- **Fire on session boundaries only:** open on video **segment 1** start / audio start; close on the transition back to **Idle**. NEVER on a ~1-min segment rollover (same session continues).
- **Best-effort / non-blocking:** run on `scope.launch(Dispatchers.IO)`, wrap in `runCatching`, never `await` on the capture path, never surface errors to the UI. A failed/absent network is a silent no-op.
- **ISO timestamp = `Pacific/Auckland`** zone (match `UploadWorker.iso8601` so the server derives the same NZ date the chunks use).
- **No new Gradle/native dependencies.**
- Build/test env: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` then `./gradlew ...` from repo root. A first-run Dropbox `Could not delete ...build...` lock is transient — re-run once.

---

### Task 1: `SessionsApiClient` (open/close request building)

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/net/SessionsApiClient.kt`
- Test: `app/src/test/java/com/benzn/grandtime/net/SessionsApiClientTest.kt`

**Interfaces:**
- Consumes: `HttpFns` (existing, in `RecordingsApiClient.kt`, package `com.benzn.grandtime.net`) — `fun postJson(url: String, authToken: String, jsonBody: String): HttpResult`.
- Produces (Task 2 relies on these exact signatures):
  - `class SessionsApiClient(baseUrl: String, http: HttpFns = RealHttp())`
  - `fun open(idToken: String, sessionId: String, startedAtMillis: Long, kind: String, siteId: String?): Boolean`
  - `fun close(idToken: String, sessionId: String, endedAtMillis: Long, intent: String): Boolean`
  - Both return `true` iff the POST returned a 2xx code; any exception → `false` (best-effort).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/benzn/grandtime/net/SessionsApiClientTest.kt`:

```kotlin
package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import java.io.File
import java.time.OffsetDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsApiClientTest {

    private val sid = "9f8c1e2a4b6d47f0a1b2c3d4e5f60718"
    // 2026-07-28T14:03:00 NZST is epoch 1785200580000 (deterministic; asserted via round-trip, not literal).
    private val startMs = 1785200580000L

    private class FakeHttp(val code: Int = 200) : HttpFns {
        var url: String? = null
        var authToken: String? = null
        var body: String? = null
        override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
            this.url = url; this.authToken = authToken; this.body = jsonBody
            return HttpResult(code, "{}")
        }
        override fun putFile(url: String, contentType: String, file: File): Int = 200
    }

    @Test fun open_posts_to_open_url_with_camelCase_body_and_raw_token() {
        val fake = FakeHttp(200)
        val ok = SessionsApiClient("https://api.example/prod/api", fake).open(
            idToken = "TOK", sessionId = sid, startedAtMillis = startMs, kind = "video", siteId = "site-1",
        )
        assertTrue(ok)
        assertEquals("https://api.example/prod/api/org/sessions/$sid/open", fake.url)
        assertEquals("TOK", fake.authToken) // raw idToken, no "Bearer "
        val b = JSONObject(fake.body!!)
        assertEquals("video", b.getString("kind"))
        assertEquals("site-1", b.getString("siteId"))
        // startedAt is ISO8601 that round-trips to the input instant (DST-agnostic assertion)
        assertEquals(startMs, OffsetDateTime.parse(b.getString("startedAt")).toInstant().toEpochMilli())
    }

    @Test fun open_omits_siteId_when_null() {
        val fake = FakeHttp(200)
        SessionsApiClient("https://api.example/prod/api", fake).open("TOK", sid, startMs, "audio", null)
        val b = JSONObject(fake.body!!)
        assertFalse(b.has("siteId"))
        assertEquals("audio", b.getString("kind"))
    }

    @Test fun close_posts_to_close_url_with_intent_and_endedAt() {
        val fake = FakeHttp(200)
        val ok = SessionsApiClient("https://api.example/prod/api", fake).close("TOK", sid, startMs, "idle")
        assertTrue(ok)
        assertEquals("https://api.example/prod/api/org/sessions/$sid/close", fake.url)
        val b = JSONObject(fake.body!!)
        assertEquals("idle", b.getString("intent"))
        assertEquals(startMs, OffsetDateTime.parse(b.getString("endedAt")).toInstant().toEpochMilli())
    }

    @Test fun non_2xx_returns_false() {
        assertFalse(SessionsApiClient("https://api.example/prod/api", FakeHttp(500)).close("TOK", sid, startMs, "idle"))
    }

    @Test fun http_exception_returns_false() {
        val throwing = object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult = throw RuntimeException("network")
            override fun putFile(url: String, contentType: String, file: File): Int = 200
        }
        assertFalse(SessionsApiClient("https://api.example/prod/api", throwing).open("TOK", sid, startMs, "video", null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.net.SessionsApiClientTest"`
Expected: FAIL — `SessionsApiClient` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/benzn/grandtime/net/SessionsApiClient.kt`:

```kotlin
package com.benzn.grandtime.net

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONObject

/**
 * Best-effort voice-timeliness session lifecycle calls (spec §6). Fire-and-forget from the caller;
 * correctness never depends on these — the backend reconstructs sessions from the uploaded `_sid/_c`
 * chunks + close-by-inactivity. Mirrors [RecordingsApiClient]'s testability (inject [HttpFns]).
 *
 * Endpoints (backend `lambda_org_api.py`, camelCase body):
 *   POST {baseUrl}/org/sessions/{sid}/open   {startedAt, kind, siteId?}
 *   POST {baseUrl}/org/sessions/{sid}/close  {intent, endedAt}
 * Auth: raw idToken in the Authorization header (no "Bearer"), same as RecordingsApiClient.
 */
class SessionsApiClient(
    private val baseUrl: String,
    private val http: HttpFns = RealHttp(),
) {
    /** @return true iff the server returned 2xx; any failure/exception → false (best-effort). */
    fun open(idToken: String, sessionId: String, startedAtMillis: Long, kind: String, siteId: String?): Boolean {
        val body = JSONObject()
            .put("startedAt", iso(startedAtMillis))
            .put("kind", kind)
        siteId?.let { body.put("siteId", it) }
        return post("$baseUrl/org/sessions/$sessionId/open", idToken, body)
    }

    /** @return true iff the server returned 2xx; any failure/exception → false (best-effort). */
    fun close(idToken: String, sessionId: String, endedAtMillis: Long, intent: String): Boolean {
        val body = JSONObject()
            .put("intent", intent)
            .put("endedAt", iso(endedAtMillis))
        return post("$baseUrl/org/sessions/$sessionId/close", idToken, body)
    }

    private fun post(url: String, idToken: String, body: JSONObject): Boolean =
        runCatching { http.postJson(url, idToken, body.toString()).code in 200..299 }.getOrElse { false }

    // NZ-local ISO so the server derives the session date in NZ, matching the chunk keys (UploadWorker.iso8601).
    private fun iso(epochMillis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.of("Pacific/Auckland")).toString()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.net.SessionsApiClientTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/net/SessionsApiClient.kt \
        app/src/test/java/com/benzn/grandtime/net/SessionsApiClientTest.kt
git commit -m "feat(net): SessionsApiClient for best-effort session open/close"
```

---

### Task 2: Fire open/close from CaptureManager at session boundaries

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`

**Interfaces:**
- Consumes: `SessionsApiClient.open(...)` / `close(...)` from Task 1; `GrandTimeApp.authManager.freshIdToken(): String?` (suspend); `BuildConfig.ORG_API_BASE_URL`.
- Produces: no new public symbols; adds two private fire-and-forget helpers.

- [ ] **Step 1: Add the client field + two fire-and-forget helpers**

In `CaptureManager.kt`, add a private field near the other collaborators (e.g. right after `private val gps = GpsTracker(context)`):

```kotlin
    private val sessionsApi = com.benzn.grandtime.net.SessionsApiClient(com.benzn.grandtime.BuildConfig.ORG_API_BASE_URL)
```

Add these two private methods (place them near `startVideoSegment`):

```kotlin
    /** Best-effort session_open — fire-and-forget, never blocks capture. No-op if not logged in. */
    private fun fireSessionOpen(sessionId: String, kind: String, startedAtMillis: Long) {
        val app = context.applicationContext as com.benzn.grandtime.GrandTimeApp
        val siteId = AppState.selectedSite.value?.id
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val token = app.authManager.freshIdToken() ?: return@launch
                sessionsApi.open(token, sessionId, startedAtMillis, kind, siteId)
            }
        }
    }

    /** Best-effort session_close (intent "idle" — deliberate-End is P0-c) — fire-and-forget. */
    private fun fireSessionClose(sessionId: String, endedAtMillis: Long) {
        val app = context.applicationContext as com.benzn.grandtime.GrandTimeApp
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val token = app.authManager.freshIdToken() ?: return@launch
                sessionsApi.close(token, sessionId, endedAtMillis, "idle")
            }
        }
    }
```

- [ ] **Step 2: Fire open on session start (video segment 1 + audio start)**

In `startVideoSegment`, inside the existing `if (cmd.segmentIndex == 1) { ... }` block (the one that starts GPS + watermark timer, around line 206), add the open call. It becomes:

```kotlin
        if (cmd.segmentIndex == 1) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) gps.start()
            startWatermarkTimer(settings)
            fireSessionOpen(cmd.sessionId, kind = "video", startedAtMillis = startedAt)
        }
```

In `startAudio`, right after the recorder successfully starts (after the `sounds.startRecording()` / `probe("audio started: ...")` lines near the end of the method, where `sessionId` and the first segment's start are in scope), add:

```kotlin
        fireSessionOpen(sessionId, kind = "audio", startedAtMillis = System.currentTimeMillis())
```

(Place it after the `if (!started) { ... return false }` guard so it only fires once the recorder actually started.)

- [ ] **Step 3: Fire close on the transition back to Idle**

The single place both video and audio settle to Idle is worth handling at each finalize. In `startVideoSegment`'s `onFinalized` callback, the non-roll branch calls `execute(core.onVideoFinalized(roll))` and then checks `if (core.state is CaptureState.Idle)`. Add the close there so it fires exactly when a recording (not a rollover) ends. That block becomes:

```kotlin
                    val roll = pendingRoll
                    pendingRoll = false
                    val endingSessionId = (core.state as? CaptureState.RecordingVideo)?.sessionId
                    execute(core.onVideoFinalized(roll))
                    if (core.state is CaptureState.Idle) {
                        sounds.stopRecording()
                        stopWatermarkTimer()
                        gps.stop()
                        pipeline.release()
                        if (endingSessionId != null) fireSessionClose(endingSessionId, System.currentTimeMillis())
                    }
```

For audio, in `stopAudio` (which calls `execute(core.onAudioFinalized())` → Idle), capture the session id before finalizing and fire close after. `stopAudio` becomes:

```kotlin
    private suspend fun stopAudio() {
        val endingSessionId = (core.state as? CaptureState.RecordingAudio)?.sessionId
        val stoppedCleanly = audio.stop()
        if (!stoppedCleanly) probe("audio stop reported error")
        // 录音期间若拍过照,相机会话可能残留——收尾释放;录像中不会走到这。
        if (!pipeline.isRecording) pipeline.release()
        sounds.stopRecording()
        execute(core.onAudioFinalized())
        if (endingSessionId != null) fireSessionClose(endingSessionId, System.currentTimeMillis())
    }
```

(Read the current `stopAudio` first to match its exact body; only add the `endingSessionId` capture at the top and the `fireSessionClose` at the end — keep everything else identical.)

- [ ] **Step 4: Run the full unit test suite (nothing regressed)**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (existing suite + Task 1's 5 new cases). Re-run once if the Dropbox lock trips it.

- [ ] **Step 5: Build the dev APK**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt
git commit -m "feat(capture): fire best-effort session open/close at recording boundaries"
```

- [ ] **Step 7: On-device / backend verification (controller-run)**

The controller verifies end-to-end: install the dev APK, record a short video (`lolaage.video1.down`/`.up`), stop, then check the fieldsight-test backend logs for the session lifecycle:
- org-api log group shows `POST /sessions/{sid}/open` then `/close`, OR
- `aws logs tail` on `/aws/lambda/fieldsight-test-org-api` shows the session id (32-hex) with an open then close.
Confirm the `sid` matches the uploaded chunk's `_sid` token. This step needs no code changes; it is acceptance evidence recorded in the progress ledger.

---

## Notes / out of scope (P0 follow-ups)

- **Pause/End UX + `intent:"end"` + quick-stop debounce** = P0-c. This slice always sends `intent:"idle"`.
- **manifest.json** (durable offline record) is a separate slice; the backend does not consume it, so open/close + chunks already give the server prompt session boundaries when online.
- No retry/queue for open/close by design — they are the "online optimization"; the chunks (WorkManager, durable) are the source of truth.
