# Upload Freeze & Thaw — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the device tell the truth about why an upload failed, stop retrying failures only we can fix, stop charging frozen time against the give-up budget, and report backlog vitals to the device ledger — with the backend accepting the report and never yet issuing a thaw.

**Architecture:** Failure classification becomes a pure Kotlin lookup (`UploadFailure`) that `UploadWorker` consults instead of writing one word for nine different outcomes. Records whose failure only we can fix enter a `frozen` state that stops retrying and accrues time credit against the seven-day give-up rule. A low-frequency `DeviceStatusWorker` posts backlog vitals to a new `POST /org/device/status` and learns the server build; the server records the vitals and always answers with an empty `thaw` list in this phase.

**Tech Stack:** Kotlin 2.1 / Compose / Room / WorkManager (GrandTime, `assembleProdDebug`, `testProdDebugUnitTest`); Python 3.12 / psycopg3 / SAM / Aurora PG16 (fieldsight-pipeline, pytest).

**Spec:** `docs/superpowers/specs/2026-08-06-upload-freeze-thaw-design.md` (revision 2). Read §3, §4, §5.1 before starting.

## Global Constraints

- **All development artefacts in English** — code comments, commit messages, technical docs. User-visible copy in English. (GrandTime CLAUDE.md, in force since 2026-07-15.)
- **No new Gradle dependencies and no native libraries.** Device ABI is armeabi 32-bit only.
- **No Google Play Services.** MediaTek device, not guaranteed present.
- **Build:** `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` before any `./gradlew`. A `java.io.IOException: Could not delete '...build...'` is the Dropbox file lock — re-run once, it is not a real failure.
- **Tests:** `./gradlew testProdDebugUnitTest` (163 green on `origin/main`; every task must leave it green).
- **There is no `app/src/androidTest`** and `CaptureDb` sets `exportSchema = false`, so Room migrations have **no automated test harness**. Migration correctness is verified on device (Task 3, Step 6). Do not fabricate a migration unit test.
- **Kotlin work happens in** `C:\Users\camil\Dropbox\worktrees\gt-freeze-thaw` (branch `feat/upload-freeze-thaw`, off `origin/main` at `3c54595`). Do **not** work in `C:\Users\camil\Dropbox\GrandTime` — that tree sits on the superseded `feat/device-identity-phase2` branch.
- **Python work (Task 9) happens in a separate clean worktree** off `fieldsight-pipeline`'s `origin/develop`; Task 9 Step 0 creates it. Never work in the `fieldsight-pipeline` main tree — it is stale and has uncommitted work belonging to another session.
- **Commit after every task.** Use single-line `Edit` anchors; the repo is `autocrlf=true` with mixed line endings.

## File Structure

**GrandTime (Kotlin)**

| File | Responsibility |
|---|---|
| `net/RecordingsApiClient.kt` (modify) | `UploadUrlResult.Error` gains the HTTP status code |
| `upload/UploadFailure.kt` (create) | Pure classification: observation → class + fingerprint |
| `upload/UploadAging.kt` (create) | Pure age arithmetic: frozen-time credit, give-up decision |
| `upload/ThawDecision.kt` (create) | Pure thaw predicate: author scope, build mismatch, explicit list |
| `db/CaptureRecord.kt` (modify) | Six new columns |
| `db/CaptureDb.kt` (modify) | Schema v5 + `MIGRATION_4_5` |
| `db/CaptureRecordDao.kt` (modify) | Status lists widened; freeze/thaw mutators; vitals queries |
| `ui/UploadSummary.kt` (modify) | New `stuck` bucket; no silent fallthrough |
| `upload/UploadWorker.kt` (modify) | Wire the three pure units in |
| `net/DeviceStatusClient.kt` (create) | `POST /org/device/status` request/response |
| `upload/DeviceStatusWorker.kt` (create) | Periodic probe; applies thaw; persists server build |

**fieldsight-pipeline (Python)**

| File | Responsibility |
|---|---|
| `src/migrations/0033_device_upload_freezes.sql` (create) | Freeze table + three `devices` vitals columns |
| `src/device_status.py` (create) | Vitals upsert + thaw decision (returns empty in Phase 1) |
| `src/lambda_org_api.py` (modify) | Route `POST /org/device/status` |
| `src/template.yaml` (modify) | `SERVER_BUILD` env var on the org-api function |

---

### Task 1: `UploadUrlResult.Error` carries the status code

The device cannot currently tell a 403 from a 404 — the code survives only inside `Error("HTTP ${r.code}: ...")`. Every later task's classification depends on fixing this first.

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/net/RecordingsApiClient.kt:126` (the `Error` declaration) and `:218-231` (`parseUploadUrl`)
- Modify: `app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt:120` (the `Error` branch — pattern only, behaviour unchanged in this task)
- Test: `app/src/test/java/com/benzn/grandtime/net/RecordingsApiClientTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `RecordingsApiClient.UploadUrlResult.Error(val code: Int, val message: String)`. `code == 0` means "a 2xx response we could not use" (malformed body, missing `recordingId`); any other value is the real HTTP status.

- [ ] **Step 1: Write the failing tests**

Append to `RecordingsApiClientTest.kt`:

```kotlin
@Test
fun `error carries the real http status`() {
    val r = RecordingsApiClient.parseUploadUrl(HttpResult(403, """{"message":"denied"}"""))
    assertTrue(r is RecordingsApiClient.UploadUrlResult.Error)
    assertEquals(403, (r as RecordingsApiClient.UploadUrlResult.Error).code)
}

@Test
fun `malformed 2xx has no status code of its own`() {
    val r = RecordingsApiClient.parseUploadUrl(HttpResult(200, "not json"))
    assertEquals(0, (r as RecordingsApiClient.UploadUrlResult.Error).code)
}

@Test
fun `missing recordingId has no status code of its own`() {
    val r = RecordingsApiClient.parseUploadUrl(HttpResult(200, """{"uploadUrl":"https://x"}"""))
    assertEquals(0, (r as RecordingsApiClient.UploadUrlResult.Error).code)
}

@Test
fun `no response is still Busy, never Error`() {
    val r = RecordingsApiClient.parseUploadUrl(HttpResult(RecordingsApiClient.NO_RESPONSE, ""))
    assertTrue(r is RecordingsApiClient.UploadUrlResult.Busy)
}
```

Match the existing file's import style and its `HttpResult` construction — read the top of the file first and copy it rather than guessing.

- [ ] **Step 2: Run to verify they fail**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew testProdDebugUnitTest --tests '*RecordingsApiClientTest*'
```

Expected: compilation failure — `Error` has no `code` parameter.

- [ ] **Step 3: Change the declaration**

`RecordingsApiClient.kt:126`:

```kotlin
        /**
         * A real response we cannot use. [code] is the HTTP status, or 0 when the
         * response was 2xx but unusable (malformed body, no recordingId) — those have
         * no status of their own to report.
         *
         * A total absence of response is [Busy], never this: see [parseUploadUrl].
         * So an Error always means the server answered and the answer was wrong,
         * which is why the upload path treats it as ours to fix, not the world's.
         */
        data class Error(val code: Int, val message: String) : UploadUrlResult
```

- [ ] **Step 4: Update the three construction sites**

`parseUploadUrl`:

```kotlin
                if (r.code !in 200..299) return@runCatching UploadUrlResult.Error(r.code, "HTTP ${r.code}: ${r.body}")
                val json = JSONObject(r.body)
                val recordingId = json.optString("recordingId")
                if (recordingId.isBlank()) return@runCatching UploadUrlResult.Error(0, "missing recordingId")
```

and the tail: `}.getOrElse { UploadUrlResult.Error(0, "malformed response") }`

- [ ] **Step 5: Run the full suite**

```bash
./gradlew testProdDebugUnitTest
```

Expected: PASS, 167 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/net/RecordingsApiClient.kt \
        app/src/test/java/com/benzn/grandtime/net/RecordingsApiClientTest.kt
git commit -m "refactor(upload): let an error say which error it was"
```

---

### Task 2: The classification table

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/upload/UploadFailure.kt`
- Test: `app/src/test/java/com/benzn/grandtime/upload/UploadFailureTest.kt`

**Interfaces:**
- Consumes: `UploadUrlResult.Error(code, message)` from Task 1.
- Produces:
  - `enum class FailureClass { TRANSIENT, SITE_FIXABLE, OPERATOR_FIXABLE, DEAD }`
  - `data class UploadFailure(val cls: FailureClass, val code: String?)`
  - `object UploadFailures` with `fun ofUploadUrl(r: UploadUrlResult): UploadFailure?`, `fun ofComplete(status: Int): UploadFailure?`, `val NEEDS_LOGIN`, `val FILE_MISSING`, `val AGED_OUT`, `val EXCEPTION`.
  - `null` return means "not a failure" (i.e. `Ok` / 2xx).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.benzn.grandtime.upload

import com.benzn.grandtime.net.RecordingsApiClient.UploadUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadFailureTest {

    @Test fun `ok is not a failure`() {
        assertNull(UploadFailures.ofUploadUrl(UploadUrlResult.Ok("r", "https://u", "k")))
    }

    @Test fun `busy is the world being unhelpful, not us`() {
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofUploadUrl(UploadUrlResult.Busy(503))!!.cls)
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofUploadUrl(UploadUrlResult.Busy(0))!!.cls)
    }

    // The pair the old code conflated: a 401 reached only after freshIdToken() handed us a
    // token means the token is good and the SERVER rejected the identity. Waiting cannot fix it.
    @Test fun `a 401 on a fresh token is ours to fix`() {
        val f = UploadFailures.ofUploadUrl(UploadUrlResult.AuthExpired)!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("uploadurl_401", f.code)
    }

    @Test fun `a dead session is the site's to fix, by signing in`() {
        assertEquals(FailureClass.SITE_FIXABLE, UploadFailures.NEEDS_LOGIN.cls)
        assertEquals("needs_login", UploadFailures.NEEDS_LOGIN.code)
    }

    @Test fun `403 from upload-url is ours`() {
        val f = UploadFailures.ofUploadUrl(UploadUrlResult.Error(403, "HTTP 403: denied"))!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("uploadurl_403", f.code)
    }

    @Test fun `any other real code from upload-url is ours, and keeps its number`() {
        val f = UploadFailures.ofUploadUrl(UploadUrlResult.Error(404, "HTTP 404"))!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("uploadurl_404", f.code)
    }

    @Test fun `an unusable 2xx has its own fingerprint`() {
        val f = UploadFailures.ofUploadUrl(UploadUrlResult.Error(0, "malformed response"))!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("uploadurl_malformed", f.code)
    }

    @Test fun `complete 2xx is not a failure`() {
        assertNull(UploadFailures.ofComplete(200))
        assertNull(UploadFailures.ofComplete(204))
    }

    @Test fun `complete transient stays transient`() {
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofComplete(503)!!.cls)
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofComplete(429)!!.cls)
        assertEquals(FailureClass.TRANSIENT, UploadFailures.ofComplete(0)!!.cls)
    }

    // complete 403 is the mis-scoped-identity case this whole design exists for.
    @Test fun `complete 401 and 403 are ours, and are told apart`() {
        assertEquals("complete_401", UploadFailures.ofComplete(401)!!.code)
        assertEquals("complete_403", UploadFailures.ofComplete(403)!!.code)
        assertEquals(FailureClass.OPERATOR_FIXABLE, UploadFailures.ofComplete(403)!!.cls)
    }

    @Test fun `complete other 4xx is ours, and keeps its number`() {
        val f = UploadFailures.ofComplete(422)!!
        assertEquals(FailureClass.OPERATOR_FIXABLE, f.cls)
        assertEquals("complete_422", f.code)
    }

    @Test fun `a vanished file and an aged-out record are dead`() {
        assertEquals(FailureClass.DEAD, UploadFailures.FILE_MISSING.cls)
        assertEquals(FailureClass.DEAD, UploadFailures.AGED_OUT.cls)
    }

    @Test fun `an exception is the world, not the request`() {
        assertEquals(FailureClass.TRANSIENT, UploadFailures.EXCEPTION.cls)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew testProdDebugUnitTest --tests '*UploadFailureTest*'
```

Expected: compilation failure — `UploadFailures` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.benzn.grandtime.upload

import com.benzn.grandtime.net.RecordingsApiClient
import com.benzn.grandtime.net.RecordingsApiClient.UploadUrlResult

/**
 * Who can fix this failure.
 *
 * Classifying by responsibility rather than by HTTP status is deliberate: status
 * tables change, responsibility does not. Every remedy in the upload path hangs off
 * this answer, and only one of the four is worth retrying blindly.
 */
enum class FailureClass {
    /** The world is temporarily unhelpful. Back off and try again. */
    TRANSIENT,

    /** Someone at the site can change the physical situation — sign in, find Wi-Fi. */
    SITE_FIXABLE,

    /**
     * Only a change on our side can make this succeed. Retrying a mis-mapped identity
     * does not fix a mis-mapped identity; before this class existed, such records were
     * retried for seven days and then dropped without telling anyone.
     */
    OPERATOR_FIXABLE,

    /** Cannot ever succeed. */
    DEAD,
}

/** A classified failure. [code] is the fingerprint the ledger groups on. */
data class UploadFailure(val cls: FailureClass, val code: String?)

object UploadFailures {
    val NEEDS_LOGIN = UploadFailure(FailureClass.SITE_FIXABLE, "needs_login")
    val FILE_MISSING = UploadFailure(FailureClass.DEAD, "file_missing")
    val AGED_OUT = UploadFailure(FailureClass.DEAD, "aged_out")
    val EXCEPTION = UploadFailure(FailureClass.TRANSIENT, null)

    private val TRANSIENT = UploadFailure(FailureClass.TRANSIENT, null)

    /** null = not a failure. */
    fun ofUploadUrl(r: UploadUrlResult): UploadFailure? = when (r) {
        is UploadUrlResult.Ok -> null
        is UploadUrlResult.Busy -> TRANSIENT
        // Reached only after freshIdToken() returned a token, so the token is good and
        // the server rejected the identity. Waiting has never fixed that.
        is UploadUrlResult.AuthExpired -> UploadFailure(FailureClass.OPERATOR_FIXABLE, "uploadurl_401")
        // An Error always means the server answered and the answer was wrong — a total
        // absence of response is Busy. So it is ours by default, not the world's.
        is UploadUrlResult.Error ->
            if (r.code == 0) UploadFailure(FailureClass.OPERATOR_FIXABLE, "uploadurl_malformed")
            else UploadFailure(FailureClass.OPERATOR_FIXABLE, "uploadurl_${r.code}")
    }

    /** null = not a failure. */
    fun ofComplete(status: Int): UploadFailure? = when {
        status in 200..299 -> null
        RecordingsApiClient.isTransient(status) -> TRANSIENT
        else -> UploadFailure(FailureClass.OPERATOR_FIXABLE, "complete_$status")
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew testProdDebugUnitTest --tests '*UploadFailureTest*'
```

Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/upload/UploadFailure.kt \
        app/src/test/java/com/benzn/grandtime/upload/UploadFailureTest.kt
git commit -m "feat(upload): classify a failure by who can fix it"
```

---

### Task 3: Schema v5 — the columns honesty needs

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/db/CaptureRecord.kt` (append fields after `groupId`)
- Modify: `app/src/main/java/com/benzn/grandtime/db/CaptureDb.kt:10` (version 4 → 5), add `MIGRATION_4_5`, register it at `:41`

**Interfaces:**
- Consumes: nothing.
- Produces: `CaptureRecord.failureClass: String?`, `.failureCode: String?`, `.lastAttemptAt: Long?`, `.frozenAtBuild: String?`, `.frozenSinceMs: Long?`, `.frozenCreditMs: Long` (default 0). Status vocabulary becomes `pending | uploading | retrying | frozen | dead | uploaded`.

- [ ] **Step 1: Add the entity columns**

Append inside `CaptureRecord`, after `groupId`:

```kotlin
    /**
     * Upload failure classification — the name of a [com.benzn.grandtime.upload.FailureClass],
     * or null when the last attempt did not fail. Stored rather than derived because the
     * decision of whether to retry is made in a fresh worker process that has no memory of
     * the attempt that failed.
     */
    val failureClass: String? = null,

    /** Fingerprint the device ledger groups on, e.g. `uploadurl_403`. Null when not failed. */
    val failureCode: String? = null,

    val lastAttemptAt: Long? = null,

    /**
     * The server build in force when this record froze. The freeze happens on the upload
     * path, which carries no build, so this is the last build a status probe reported —
     * and null if none ever has. Null must NOT be read as "different from the current
     * build": that would thaw, refail and refreeze on every probe, forever.
     */
    val frozenAtBuild: String? = null,

    /** When the current freeze began; null when not frozen. */
    val frozenSinceMs: Long? = null,

    /**
     * Time already spent frozen, in ms. Credited against the give-up age so that a record
     * frozen on day 2 and thawed on day 9 still gets its retry budget. Without this,
     * freezing quietly becomes deleting — the exact loss the freeze exists to prevent.
     */
    val frozenCreditMs: Long = 0,
```

- [ ] **Step 2: Bump the version and write the migration**

`CaptureDb.kt:10`: `version = 5`.

Add after `MIGRATION_3_4`:

```kotlin
        /**
         * Upload honesty (spec 2026-08-06). "failed" used to mean three different things —
         * backing off, gone for good, and stale — so every existing row is moved to the
         * one that is true of all of them: it will be tried again.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE capture_records ADD COLUMN failureClass TEXT")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN failureCode TEXT")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN frozenAtBuild TEXT")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN frozenSinceMs INTEGER")
                db.execSQL("ALTER TABLE capture_records ADD COLUMN frozenCreditMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE capture_records SET uploadStatus = 'retrying' WHERE uploadStatus = 'failed'")
            }
        }
```

Register it: `.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)`.

- [ ] **Step 3: Build**

```bash
./gradlew assembleProdDebug
```

Expected: BUILD SUCCESSFUL. A Room schema/entity mismatch fails here, at compile time — that is the check this step exists for.

- [ ] **Step 4: Run the full suite**

```bash
./gradlew testProdDebugUnitTest
```

Expected: PASS (unchanged count — no behaviour has moved yet).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/db/CaptureRecord.kt \
        app/src/main/java/com/benzn/grandtime/db/CaptureDb.kt
git commit -m "feat(db): room v5 — room for why an upload failed, and for time spent frozen"
```

- [ ] **Step 6: Verify the migration on device (no automated harness exists)**

There is no `app/src/androidTest` and `exportSchema = false`, so Room's `MigrationTestHelper` is unavailable. Verify by hand, and record the output in the task ledger:

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
# 1. Install the PREVIOUS build (origin/main) first so a v4 database exists, record something.
# 2. Install this build over it WITHOUT uninstalling:
"$ADB" install -r app/build/outputs/apk/prod/debug/app-prod-debug.apk
# 3. Confirm the app opens the DB without crashing and the file list still shows old recordings.
"$ADB" logcat -d | grep -i "IllegalStateException\|Migration didn't properly"
```

Expected: no migration exception; previously-recorded rows still listed. An `install -r` that wipes data has not tested anything — if the file list is empty, the database was recreated and the migration was not exercised.

---

### Task 4: Age arithmetic — frozen time is not spent time

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/upload/UploadAging.kt`
- Test: `app/src/test/java/com/benzn/grandtime/upload/UploadAgingTest.kt`

**Interfaces:**
- Consumes: `CaptureRecord` fields from Task 3.
- Produces: `object UploadAging` with
  `fun effectiveAgeMs(record: CaptureRecord, now: Long): Long` and
  `fun shouldGiveUp(record: CaptureRecord, now: Long): Boolean`, plus
  `fun creditOnThaw(record: CaptureRecord, now: Long): Long` returning the new `frozenCreditMs`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.benzn.grandtime.upload

import com.benzn.grandtime.db.CaptureRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 24L * 60 * 60 * 1000

private fun record(
    startedAt: Long,
    status: String = "pending",
    frozenSinceMs: Long? = null,
    frozenCreditMs: Long = 0,
    lastAttemptAt: Long? = null,
) = CaptureRecord(
    id = "r", kind = "video", filePath = "/x", fileName = "f.mp4",
    startedAt = startedAt, codec = "h264", sessionId = "s", createdAt = startedAt,
    uploadStatus = status, frozenSinceMs = frozenSinceMs,
    frozenCreditMs = frozenCreditMs, lastAttemptAt = lastAttemptAt,
)

class UploadAgingTest {

    @Test fun `an ordinary record ages in real time`() {
        assertEquals(2 * DAY, UploadAging.effectiveAgeMs(record(startedAt = 0), now = 2 * DAY))
    }

    @Test fun `time spent frozen right now does not count`() {
        val r = record(startedAt = 0, status = "frozen", frozenSinceMs = 1 * DAY)
        assertEquals(1 * DAY, UploadAging.effectiveAgeMs(r, now = 5 * DAY))
    }

    @Test fun `time spent frozen earlier does not count either`() {
        val r = record(startedAt = 0, frozenCreditMs = 4 * DAY)
        assertEquals(1 * DAY, UploadAging.effectiveAgeMs(r, now = 5 * DAY))
    }

    @Test fun `an old record with no freeze is given up on`() {
        assertTrue(UploadAging.shouldGiveUp(record(startedAt = 0), now = 8 * DAY))
    }

    /**
     * The regression this file exists for. Frozen on day 2, fixed on day 9: without the
     * credit the thawed record hits the age check with a cleared class and dies without a
     * single retry — the freeze mechanism destroying the data it exists to protect.
     */
    @Test fun `a record thawed after a long freeze still gets its budget`() {
        val thawed = record(startedAt = 0, frozenCreditMs = 7 * DAY, lastAttemptAt = 2 * DAY)
        assertFalse(UploadAging.shouldGiveUp(thawed, now = 9 * DAY))
    }

    @Test fun `a frozen record is never given up on while frozen`() {
        val r = record(startedAt = 0, status = "frozen", frozenSinceMs = 1 * DAY)
        assertFalse(UploadAging.shouldGiveUp(r, now = 90 * DAY))
    }

    @Test fun `thaw banks the frozen span`() {
        val r = record(startedAt = 0, status = "frozen", frozenSinceMs = 2 * DAY, frozenCreditMs = 1 * DAY)
        assertEquals(4 * DAY, UploadAging.creditOnThaw(r, now = 5 * DAY))
    }

    @Test fun `crediting a record that was never frozen changes nothing`() {
        assertEquals(0L, UploadAging.creditOnThaw(record(startedAt = 0), now = 5 * DAY))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew testProdDebugUnitTest --tests '*UploadAgingTest*'
```

Expected: compilation failure — `UploadAging` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.benzn.grandtime.upload

import com.benzn.grandtime.db.CaptureRecord

/**
 * How long a recording has been *trying*, as opposed to how long it has existed.
 *
 * The give-up rule is age-based rather than attempt-based because an attempt count charges
 * a server outage to the recording (GrandTime#3). Freezing introduces the same hazard one
 * level up: a record frozen because only we can fix it is not failing to upload, it is
 * waiting for us — so the wait must not be billed to it. Frozen time is subtracted, and
 * banked on thaw, so a fix that lands on day nine still finds a record with budget left.
 */
object UploadAging {

    fun effectiveAgeMs(record: CaptureRecord, now: Long): Long {
        val frozenNow = record.frozenSinceMs?.let { now - it } ?: 0L
        return now - record.startedAt - record.frozenCreditMs - frozenNow
    }

    fun shouldGiveUp(record: CaptureRecord, now: Long): Boolean {
        // A frozen record is waiting on us, not failing. It has no deadline.
        if (record.frozenSinceMs != null) return false
        // Belt and braces for the case that motivated this file: a record that has ever been
        // frozen is owed one attempt after its thaw before any deadline applies.
        if (record.frozenCreditMs > 0 && (record.lastAttemptAt ?: 0) < now - record.frozenCreditMs) {
            return false
        }
        return effectiveAgeMs(record, now) > GIVE_UP_AFTER_MS
    }

    fun creditOnThaw(record: CaptureRecord, now: Long): Long =
        record.frozenCreditMs + (record.frozenSinceMs?.let { now - it } ?: 0L)
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew testProdDebugUnitTest --tests '*UploadAgingTest*'
```

Expected: PASS, 8 tests. If `a record thawed after a long freeze still gets its budget` fails, the guarantee is wrong — fix it here, not by loosening the test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/upload/UploadAging.kt \
        app/src/test/java/com/benzn/grandtime/upload/UploadAgingTest.kt
git commit -m "feat(upload): do not bill a record for the time it spent waiting on us"
```

---

### Task 5: The thaw predicate

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/upload/ThawDecision.kt`
- Test: `app/src/test/java/com/benzn/grandtime/upload/ThawDecisionTest.kt`

**Interfaces:**
- Consumes: `CaptureRecord` fields from Task 3.
- Produces: `object ThawDecision` with
  `fun shouldThaw(record: CaptureRecord, currentAuthorSub: String?, serverBuild: String?, thawList: List<String>): Boolean`
  and `fun shouldAdoptBuild(record: CaptureRecord): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.benzn.grandtime.upload

import com.benzn.grandtime.db.CaptureRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun frozen(
    authorSub: String? = "sub-a",
    code: String = "uploadurl_403",
    build: String? = "build-1",
) = CaptureRecord(
    id = "r", kind = "video", filePath = "/x", fileName = "f.mp4",
    startedAt = 0, codec = "h264", sessionId = "s", createdAt = 0,
    uploadStatus = "frozen", authorSub = authorSub,
    failureClass = FailureClass.OPERATOR_FIXABLE.name, failureCode = code,
    frozenAtBuild = build, frozenSinceMs = 1,
)

class ThawDecisionTest {

    @Test fun `a redeploy thaws what it may have fixed`() {
        assertTrue(ThawDecision.shouldThaw(frozen(), "sub-a", "build-2", emptyList()))
    }

    @Test fun `the same build thaws nothing`() {
        assertFalse(ThawDecision.shouldThaw(frozen(), "sub-a", "build-1", emptyList()))
    }

    @Test fun `an explicit instruction thaws`() {
        assertTrue(ThawDecision.shouldThaw(frozen(), "sub-a", "build-1", listOf("uploadurl_403")))
    }

    @Test fun `an instruction for a different fingerprint thaws nothing`() {
        assertFalse(ThawDecision.shouldThaw(frozen(), "sub-a", "build-1", listOf("complete_403")))
    }

    /**
     * These devices rotate between clients monthly, and a uploadurl_403 is most likely an
     * identity mis-scoping — precisely the failure a new account might be allowed to commit.
     * Thawing it under whoever is signed in now is the cross-tenant upload leak, again.
     */
    @Test fun `a thaw never crosses accounts`() {
        assertFalse(ThawDecision.shouldThaw(frozen(authorSub = "sub-a"), "sub-b", "build-2", listOf("uploadurl_403")))
    }

    @Test fun `an unowned row is never thawed`() {
        assertFalse(ThawDecision.shouldThaw(frozen(authorSub = null), null, "build-2", listOf("uploadurl_403")))
    }

    /** Null build must not read as "different". Otherwise: thaw, refail, refreeze, forever. */
    @Test fun `a record frozen before the first probe is adopted, not thawed`() {
        val r = frozen(build = null)
        assertFalse(ThawDecision.shouldThaw(r, "sub-a", "build-2", emptyList()))
        assertTrue(ThawDecision.shouldAdoptBuild(r))
    }

    @Test fun `an explicit instruction still thaws a record with no build`() {
        assertTrue(ThawDecision.shouldThaw(frozen(build = null), "sub-a", "build-2", listOf("uploadurl_403")))
    }

    @Test fun `a record that already knows the build is not re-adopted`() {
        assertFalse(ThawDecision.shouldAdoptBuild(frozen()))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew testProdDebugUnitTest --tests '*ThawDecisionTest*'
```

Expected: compilation failure — `ThawDecision` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.benzn.grandtime.upload

import com.benzn.grandtime.db.CaptureRecord

/**
 * Whether a frozen record may be tried again.
 *
 * Two ways to earn it, and one veto that outranks both.
 *
 * The veto is ownership. A frozen `uploadurl_403` is most likely an identity that the
 * backend refused to map, and these devices change hands between clients every month —
 * so the account signed in now is exactly the account that must NOT inherit the last
 * one's rejected upload. Same reason `CaptureRecordDao.listPendingForAuthor` exists.
 */
object ThawDecision {

    fun shouldThaw(
        record: CaptureRecord,
        currentAuthorSub: String?,
        serverBuild: String?,
        thawList: List<String>,
    ): Boolean {
        val owner = record.authorSub ?: return false
        if (currentAuthorSub == null || owner != currentAuthorSub) return false

        val explicit = record.failureCode != null && record.failureCode in thawList
        // A null frozenAtBuild is "we don't know yet", not "different". Reading it as
        // different thaws on every single probe: thaw, refail, refreeze, forever.
        val redeployed = record.frozenAtBuild != null &&
            serverBuild != null &&
            record.frozenAtBuild != serverBuild

        return explicit || redeployed
    }

    /** A record frozen before the device ever saw a build adopts the first one it hears. */
    fun shouldAdoptBuild(record: CaptureRecord): Boolean =
        record.frozenSinceMs != null && record.frozenAtBuild == null
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew testProdDebugUnitTest --tests '*ThawDecisionTest*'
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/upload/ThawDecision.kt \
        app/src/test/java/com/benzn/grandtime/upload/ThawDecisionTest.kt
git commit -m "feat(upload): a thaw crosses builds, never accounts"
```

---

### Task 6: The status-consumer audit

Renaming a status value is not a local change. `UploadSummary.kt:23` says `// unknown status values are ignored`, and two DAO counters hardcode `('pending','failed','uploading')` — so without this task the rename silently disables the sign-out data-loss warning, with every test still green.

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ui/UploadSummary.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/db/CaptureRecordDao.kt:67-78` (the two counters), plus new queries
- Test: `app/src/test/java/com/benzn/grandtime/ui/UploadSummaryTest.kt`

**Interfaces:**
- Consumes: the status vocabulary from Task 3.
- Produces: `UploadSummary(uploaded, inProgress, failed, stuck)`; DAO gains
  `countPendingLike(authorSub)`, `countOrphanedPendingLike()`,
  `freeze(id, cls, code, build, sinceMs, now)`, `thaw(id, creditMs)`,
  `listFrozen()`, `oldestUnsentStartedAt()`, `countByStatusGroup()`.

- [ ] **Step 1: Write the failing test**

Read `UploadSummaryTest.kt` first and follow its existing construction style. Append:

```kotlin
@Test
fun `retrying is still in progress`() {
    val s = summarizeUploads(listOf(UploadStatusCount("retrying", 3)))
    assertEquals(3, s.inProgress)
    assertEquals(0, s.stuck)
}

@Test
fun `frozen and dead are stuck, and are not silently dropped`() {
    val s = summarizeUploads(listOf(UploadStatusCount("frozen", 2), UploadStatusCount("dead", 1)))
    assertEquals(3, s.stuck)
    assertEquals(0, s.inProgress)
}

@Test
fun `every status in the vocabulary lands in a bucket`() {
    val all = listOf("pending", "uploading", "retrying", "frozen", "dead", "uploaded")
        .map { UploadStatusCount(it, 1) }
    val s = summarizeUploads(all)
    assertEquals(6, s.uploaded + s.inProgress + s.failed + s.stuck)
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew testProdDebugUnitTest --tests '*UploadSummaryTest*'
```

Expected: compilation failure — `UploadSummary` has no `stuck`.

- [ ] **Step 3: Widen the summary**

Replace the `when` in `UploadSummary.kt` and add the field to the data class:

```kotlin
        when (c.status) {
            "uploaded" -> uploaded += c.n
            "uploading", "pending", "retrying" -> inProgress += c.n
            // Waiting on us, or gone. Counted so a stuck backlog cannot look like an idle one;
            // the recording screen does not show these, because the user cannot act on them.
            "frozen", "dead" -> stuck += c.n
            "failed" -> failed += c.n   // pre-v5 rows, until the migration has run
            else -> failed += c.n       // never silently drop a status: an unknown one is a bug,
                                        // and burying it is how a rename deletes a warning
        }
```

- [ ] **Step 4: Widen the DAO counters and add the new queries**

In `CaptureRecordDao.kt`, change **both** hardcoded lists at `:69` and `:76` from
`IN ('pending','failed','uploading')` to
`IN ('pending','failed','uploading','retrying','frozen')`.

`dead` is deliberately excluded: a dead record is not "unsent work you are about to lose", it is already lost, and counting it in the sign-out warning would make that warning un-clearable.

Then append:

```kotlin
    @Query(
        "UPDATE capture_records SET uploadStatus = 'frozen', failureClass = :cls, " +
            "failureCode = :code, frozenAtBuild = :build, frozenSinceMs = :sinceMs, " +
            "lastAttemptAt = :now WHERE id = :id"
    )
    suspend fun freeze(id: String, cls: String, code: String, build: String?, sinceMs: Long, now: Long)

    @Query(
        "UPDATE capture_records SET uploadStatus = 'pending', failureClass = NULL, " +
            "failureCode = NULL, frozenAtBuild = NULL, frozenSinceMs = NULL, " +
            "frozenCreditMs = :creditMs WHERE id = :id"
    )
    suspend fun thaw(id: String, creditMs: Long)

    @Query("UPDATE capture_records SET frozenAtBuild = :build WHERE id = :id")
    suspend fun adoptFrozenBuild(id: String, build: String)

    @Query(
        "UPDATE capture_records SET uploadStatus = 'dead', failureClass = :cls, " +
            "failureCode = :code, lastAttemptAt = :now WHERE id = :id"
    )
    suspend fun markDead(id: String, cls: String, code: String, now: Long)

    @Query(
        "UPDATE capture_records SET uploadStatus = 'retrying', failureClass = :cls, " +
            "failureCode = :code, lastAttemptAt = :now WHERE id = :id"
    )
    suspend fun markRetrying(id: String, cls: String, code: String?, now: Long)

    @Query("SELECT * FROM capture_records WHERE uploadStatus = 'frozen' AND missing = 0")
    suspend fun listFrozen(): List<CaptureRecord>

    /** Oldest still-unsent recording, for the ledger's backlog age. Null when nothing is waiting. */
    @Query(
        "SELECT MIN(startedAt) FROM capture_records WHERE " +
            "uploadStatus IN ('pending','uploading','retrying','frozen') AND missing = 0"
    )
    suspend fun oldestUnsentStartedAt(): Long?

    /**
     * Dead rows for the probe. Deliberately NOT filtered on `missing`: a vanished file is
     * invisible on the device by design, but the ledger must still hear about it once.
     */
    @Query("SELECT COUNT(*) FROM capture_records WHERE uploadStatus = 'dead'")
    suspend fun countDead(): Int

    @Query(
        "SELECT COUNT(*) FROM capture_records WHERE " +
            "uploadStatus IN ('pending','uploading','retrying') AND missing = 0"
    )
    suspend fun countUnsent(): Int

    @Query("SELECT DISTINCT failureCode FROM capture_records WHERE uploadStatus = 'frozen' AND failureCode IS NOT NULL")
    suspend fun frozenFingerprints(): List<String>
```

- [ ] **Step 5: Run the full suite**

```bash
./gradlew testProdDebugUnitTest
```

Expected: PASS. Grep for any other consumer the audit missed before moving on:

```bash
grep -rn "'failed'\|\"failed\"" app/src/main/java/ | grep -v "UploadFailure"
```

Expected: only the migration in `CaptureDb.kt` and the `"failed"` arm of `UploadSummary`. Any other hit is an unaudited consumer — fix it in this task.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/ui/UploadSummary.kt \
        app/src/main/java/com/benzn/grandtime/db/CaptureRecordDao.kt \
        app/src/test/java/com/benzn/grandtime/ui/UploadSummaryTest.kt
git commit -m "feat(upload): count the records that are stuck, do not drop them"
```

---

### Task 7: Wire the worker

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt` (all nine `markUploadStatus` sites)

**Interfaces:**
- Consumes: `UploadFailures` (Task 2), `UploadAging` (Task 4), the DAO mutators (Task 6).
- Produces: no new API. Behaviour: `OPERATOR_FIXABLE` → `freeze` + `Result.failure()`; `TRANSIENT`/`SITE_FIXABLE` → `markRetrying` + `Result.retry()`; `DEAD` → `markDead` + `Result.failure()`.

- [ ] **Step 1: Replace the age check**

`UploadWorker.kt:62-65` becomes:

```kotlin
            val now = System.currentTimeMillis()
            // A frozen record is waiting on us and has no deadline; a thawed one is owed the
            // budget it never got to spend. See UploadAging for why this is a credit and not
            // an exemption — the exemption version killed records the moment they thawed.
            if (UploadAging.shouldGiveUp(record, now)) {
                dao.markDead(recordId, UploadFailures.AGED_OUT.cls.name, UploadFailures.AGED_OUT.code!!, now)
                return Result.failure()
            }
            if (record.uploadStatus == "frozen") return Result.failure()
```

The second line matters: a stray enqueue must not drag a frozen record back onto the retry path. Only `DeviceStatusWorker` thaws.

- [ ] **Step 2: Add the single decision helper at the bottom of the file**

```kotlin
/**
 * One place where a classified failure becomes a status and a WorkManager verdict.
 *
 * Before this existed, nine call sites wrote the same word "failed" for three different
 * meanings — backing off, gone for good, and stale — which is why nothing downstream could
 * tell a busy backend from a broken one.
 */
private suspend fun CaptureRecordDao.record(
    recordId: String,
    failure: UploadFailure,
    build: String?,
    now: Long,
): androidx.work.ListenableWorker.Result = when (failure.cls) {
    FailureClass.OPERATOR_FIXABLE -> {
        freeze(recordId, failure.cls.name, failure.code!!, build, now, now)
        // failure(), not retry(): retrying is precisely what does not work here, and what
        // used to burn seven days before dropping the recording without telling anyone.
        androidx.work.ListenableWorker.Result.failure()
    }
    FailureClass.DEAD -> {
        markDead(recordId, failure.cls.name, failure.code!!, now)
        androidx.work.ListenableWorker.Result.failure()
    }
    FailureClass.TRANSIENT, FailureClass.SITE_FIXABLE -> {
        markRetrying(recordId, failure.cls.name, failure.code, now)
        androidx.work.ListenableWorker.Result.retry()
    }
}
```

- [ ] **Step 3: Replace the nine call sites**

Read the current build from settings once, near the top of `doWork()`. Note that
`GrandTimeApp` exposes **only** `authManager` and `applicationScope` — there is no
`app.settings`. The codebase's pattern is to construct the store on the spot
(`CoreService.kt:121,225,280`), so follow it:

```kotlin
            val serverBuild = SettingsStore(applicationContext.settingsDataStore).lastKnownServerBuild()
```

Add the accessor to `SettingsStore` in this task (Task 8 uses the setter):

```kotlin
        private val KEY_LAST_SERVER_BUILD = stringPreferencesKey("last_server_build")
```

```kotlin
    /**
     * The build the server last reported. A freeze stamps it so that a later redeploy can
     * be recognised as "the thing that broke this may now be fixed". Null until the first
     * status probe answers — and null must never be read as "different from the current
     * build" (see ThawDecision).
     */
    suspend fun lastKnownServerBuild(): String? =
        dataStore.data.first()[KEY_LAST_SERVER_BUILD]

    suspend fun setLastKnownServerBuild(value: String) {
        dataStore.edit { it[KEY_LAST_SERVER_BUILD] = value }
    }
```

Add `import kotlinx.coroutines.flow.first` to `SettingsStore.kt`.

Then:

| Line (original) | Replace with |
|---|---|
| 78 (`freshIdToken` null, LoggedOut) | `return dao.record(recordId, UploadFailures.NEEDS_LOGIN, serverBuild, now)` |
| 81-83 (transient refresh) | `return dao.record(recordId, UploadFailures.EXCEPTION, serverBuild, now)` |
| 110 (`AuthExpired`) | `dao.record(recordId, UploadFailures.ofUploadUrl(urlResult)!!, serverBuild, now)` |
| 117 (`Busy`) | `dao.record(recordId, UploadFailures.ofUploadUrl(urlResult)!!, serverBuild, now)` |
| 121 (`Error`) | `dao.record(recordId, UploadFailures.ofUploadUrl(urlResult)!!, serverBuild, now)` |
| 128 (file missing) | `dao.markMissing(listOf(recordId)); return dao.record(recordId, UploadFailures.FILE_MISSING, serverBuild, now)` |
| 154, 160 (`complete` non-2xx) | `dao.record(recordId, UploadFailures.ofComplete(status)!!, serverBuild, now)` |
| 167 (`catch`) | `dao.record(recordId, UploadFailures.EXCEPTION, serverBuild, now)` |

Note lines 154 and 160 collapse into **one** branch — `ofComplete` already distinguishes transient from ours, which is the whole point.

Keep line 63's give-up and line 48's early `uploaded` return as they are.

- [ ] **Step 4: Build and run the full suite**

```bash
./gradlew assembleProdDebug && ./gradlew testProdDebugUnitTest
```

Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 5: Confirm no `markUploadStatus("failed")` survives**

```bash
grep -n "markUploadStatus" app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt
```

Expected: only `markUploadStatus(recordId, "uploading")` (line 85) and `"uploaded"` (line 148). Any remaining `"failed"` is an unconverted branch.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/upload/UploadWorker.kt
git commit -m "feat(upload): stop retrying what retrying cannot fix"
```

---

### Task 8: The status probe

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/net/DeviceStatusClient.kt`
- Create: `app/src/main/java/com/benzn/grandtime/upload/DeviceStatusWorker.kt`
- Test: `app/src/test/java/com/benzn/grandtime/net/DeviceStatusClientTest.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/GrandTimeApp.kt` (schedule the periodic work)

**Interfaces:**
- Consumes: `ThawDecision` (Task 5), `UploadAging` (Task 4), DAO vitals queries (Task 6), and `SettingsStore.lastKnownServerBuild()` / `.setLastKnownServerBuild()` (added in Task 7).
- Produces: `DeviceStatusClient.report(idToken, DeviceVitals): DeviceStatusResponse?`;
  `data class DeviceVitals(oldestPendingAgeS: Long?, pending: Int, frozen: Int, dead: Int, fingerprints: List<String>)`;
  `data class DeviceStatusResponse(serverBuild: String?, thaw: List<String>)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.benzn.grandtime.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusClientTest {

    @Test fun `vitals serialize as the contract says`() {
        val body = DeviceStatusClient.requestBody(
            DeviceVitals(oldestPendingAgeS = 93600, pending = 12, frozen = 3, dead = 1,
                fingerprints = listOf("uploadurl_401", "complete_403"))
        )
        val json = org.json.JSONObject(body)
        assertEquals(93600, json.getLong("oldestPendingAgeS"))
        assertEquals(12, json.getInt("pending"))
        assertEquals(2, json.getJSONArray("fingerprints").length())
    }

    @Test fun `an idle device reports a null age, not a zero`() {
        val json = org.json.JSONObject(
            DeviceStatusClient.requestBody(DeviceVitals(null, 0, 0, 0, emptyList()))
        )
        assertTrue(json.isNull("oldestPendingAgeS"))
    }

    @Test fun `a response is read`() {
        val r = DeviceStatusClient.parse("""{"serverBuild":"9495bcd","thaw":["uploadurl_401"]}""")!!
        assertEquals("9495bcd", r.serverBuild)
        assertEquals(listOf("uploadurl_401"), r.thaw)
    }

    /** The probe is telemetry. It must never be able to change what an upload does. */
    @Test fun `an unreadable response is no response, not a crash`() {
        assertEquals(null, DeviceStatusClient.parse("not json"))
        assertEquals(null, DeviceStatusClient.parse(null))
    }

    @Test fun `a response with no thaw list is an empty one`() {
        assertEquals(emptyList<String>(), DeviceStatusClient.parse("""{"serverBuild":"x"}""")!!.thaw)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew testProdDebugUnitTest --tests '*DeviceStatusClientTest*'
```

Expected: compilation failure.

- [ ] **Step 3: Write the client**

Read `RecordingsApiClient.kt:20-40` first and copy its `HttpFns`/`RealHttp` construction and its `attachDeviceHeaders` usage exactly — the device headers must ride this request (it is the ledger heartbeat) and must never reach S3.

```kotlin
package com.benzn.grandtime.net

import org.json.JSONArray
import org.json.JSONObject

/** What the device knows about its own backlog. */
data class DeviceVitals(
    /** Age of the oldest still-unsent recording, or null when nothing is waiting. */
    val oldestPendingAgeS: Long?,
    val pending: Int,
    val frozen: Int,
    val dead: Int,
    val fingerprints: List<String>,
)

data class DeviceStatusResponse(val serverBuild: String?, val thaw: List<String>)

/**
 * The device ledger's backlog channel: vitals up, thaw decisions down, one round trip.
 *
 * Best-effort by construction. A probe that fails changes nothing — it must never be able
 * to fail an upload or surface to the user, because it is telemetry riding alongside the
 * work, not part of it.
 */
class DeviceStatusClient(
    private val baseUrl: String,
    private val http: HttpFns = RealHttp(),
) {
    fun report(idToken: String, vitals: DeviceVitals): DeviceStatusResponse? = runCatching {
        val r = http.postJson("$baseUrl/org/device/status", idToken, requestBody(vitals))
        if (r.code !in 200..299) null else parse(r.body)
    }.getOrNull()

    companion object {
        fun requestBody(v: DeviceVitals): String = JSONObject().apply {
            if (v.oldestPendingAgeS == null) put("oldestPendingAgeS", JSONObject.NULL)
            else put("oldestPendingAgeS", v.oldestPendingAgeS)
            put("pending", v.pending)
            put("frozen", v.frozen)
            put("dead", v.dead)
            put("fingerprints", JSONArray(v.fingerprints))
        }.toString()

        fun parse(body: String?): DeviceStatusResponse? = runCatching {
            val json = JSONObject(body ?: return null)
            val arr = json.optJSONArray("thaw")
            DeviceStatusResponse(
                serverBuild = json.optString("serverBuild").takeIf { it.isNotBlank() },
                thaw = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) },
            )
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew testProdDebugUnitTest --tests '*DeviceStatusClientTest*'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Write the worker**

```kotlin
package com.benzn.grandtime.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.benzn.grandtime.BuildConfig
import com.benzn.grandtime.GrandTimeApp
import com.benzn.grandtime.db.CaptureDb
import com.benzn.grandtime.net.DeviceStatusClient
import com.benzn.grandtime.net.DeviceVitals

/**
 * Tells the ledger how far behind this device is, and hears back whether anything it froze
 * may be tried again.
 *
 * Runs rarely on purpose: twenty devices at four calls a day is nothing, and there is no
 * urgency in either direction — a freeze is already not costing the recording its budget.
 */
class DeviceStatusWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GrandTimeApp
        val dao = CaptureDb.get(applicationContext).captureRecords()
        return try {
            app.authManager.silentLogin()
            val idToken = app.authManager.freshIdToken() ?: return Result.retry()

            val now = System.currentTimeMillis()
            val oldest = dao.oldestUnsentStartedAt()
            val vitals = DeviceVitals(
                oldestPendingAgeS = oldest?.let { (now - it) / 1000 },
                pending = dao.countUnsent(),
                frozen = dao.listFrozen().size,
                dead = dao.countDead(),
                fingerprints = dao.frozenFingerprints(),
            )

            val response = DeviceStatusClient(BuildConfig.ORG_API_BASE_URL).report(idToken, vitals)
                ?: return Result.retry()
            val settings = SettingsStore(applicationContext.settingsDataStore)
            response.serverBuild?.let { settings.setLastKnownServerBuild(it) }

            // silentLogin() above has already restored the accurate state, so this is the
            // account that actually owns the session — not a default from a cold worker process.
            val currentSub = (AppState.loginState.value as? LoginState.LoggedIn)?.sub
            val enqueuer = WorkManagerUploadEnqueuer(applicationContext)
            for (record in dao.listFrozen()) {
                when {
                    ThawDecision.shouldThaw(record, currentSub, response.serverBuild, response.thaw) -> {
                        dao.thaw(record.id, UploadAging.creditOnThaw(record, now))
                        // replace = true: a request sitting in backoff counts as unfinished, and
                        // KEEP would drop this one — the reason the Retry button did nothing.
                        enqueuer.enqueue(record.id, replace = true)
                    }
                    ThawDecision.shouldAdoptBuild(record) ->
                        response.serverBuild?.let { dao.adoptFrozenBuild(record.id, it) }
                }
            }
            Result.success()
        } catch (e: Exception) {
            // Telemetry must never fail loudly. Nothing above has changed an upload's fate.
            Result.retry()
        }
    }
}
```

Imports this worker needs beyond the ones shown: `com.benzn.grandtime.core.AppState`,
`com.benzn.grandtime.core.LoginState`, `com.benzn.grandtime.core.SettingsStore`,
`com.benzn.grandtime.core.settingsDataStore`.

- [ ] **Step 6: Schedule it**

In `GrandTimeApp.onCreate()`, after the existing `DeviceIdentity.init(this)`:

```kotlin
        // The backlog channel. Rare on purpose: a frozen record is not losing budget while
        // it waits, so there is no urgency in either direction, and twenty devices at four
        // calls a day is nothing against org-api's reserved concurrency.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "device_status",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeviceStatusWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build(),
        )
```

Imports: `androidx.work.Constraints`, `androidx.work.ExistingPeriodicWorkPolicy`,
`androidx.work.NetworkType`, `androidx.work.PeriodicWorkRequestBuilder`,
`androidx.work.WorkManager`, `com.benzn.grandtime.upload.DeviceStatusWorker`,
`java.util.concurrent.TimeUnit`.

`KEEP` is correct here — unlike the upload queue, a duplicate probe has nothing to
rescue, so coalescing is exactly what you want.

- [ ] **Step 7: Build and run the full suite**

```bash
./gradlew assembleProdDebug && ./gradlew testProdDebugUnitTest
```

Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/net/DeviceStatusClient.kt \
        app/src/main/java/com/benzn/grandtime/upload/DeviceStatusWorker.kt \
        app/src/main/java/com/benzn/grandtime/core/SettingsStore.kt \
        app/src/main/java/com/benzn/grandtime/GrandTimeApp.kt \
        app/src/test/java/com/benzn/grandtime/net/DeviceStatusClientTest.kt
git commit -m "feat(device): say how far behind we are, and hear when to try again"
```

---

### Task 9: The backend accepts the report (fieldsight-pipeline)

Phase 1 keeps the backend inert: it records vitals and always answers with an empty `thaw` list. The freeze table is created now so Phase 2 adds no migration.

**Files:**
- Create: `src/migrations/0033_device_upload_freezes.sql`
- Create: `src/device_status.py`
- Modify: `src/lambda_org_api.py` (route registration, next to the existing device routes)
- Modify: `src/template.yaml` (`SERVER_BUILD` env var on the org-api function)
- Test: `tests/unit/test_device_status.py`

**Interfaces:**
- Consumes: the request body from Task 8.
- Produces: `device_status.record(conn, device_id, vitals) -> dict` returning
  `{"serverBuild": <str|None>, "thaw": []}`.

- [ ] **Step 0: Create a clean worktree — do NOT use the main tree**

```bash
cd /c/Users/camil/Dropbox/fieldsight-pipeline
git fetch origin
git worktree add /c/Users/camil/Dropbox/worktrees/fp-device-status -b feat/device-status-uplink origin/develop
cd /c/Users/camil/Dropbox/worktrees/fp-device-status
```

The `fieldsight-pipeline` main tree is ~30 commits stale and carries another session's uncommitted work. Branch off `develop`, not `main` — that is this repo's integration branch.

- [ ] **Step 1: Write the migration**

`src/migrations/0033_device_upload_freezes.sql`:

```sql
-- Upload freeze register (spec docs/superpowers/specs/2026-08-06-upload-freeze-thaw-design.md).
--
-- A recording that cannot upload because of something only we can fix is frozen rather than
-- retried: retrying a mis-mapped identity does not fix a mis-mapped identity, and the seven
-- days it used to spend trying were pure delay before a silent loss.
--
-- The table is created in Phase 1 and stays empty until Phase 2, so that promoting the
-- feature adds no migration to an already-busy deploy.

create table if not exists device_upload_freezes (
  device_id         uuid not null references devices(id),
  fingerprint       text not null,
  first_seen_at     timestamptz not null default now(),
  last_seen_at      timestamptz not null default now(),
  record_count      integer not null default 0,
  observed_build    text,
  -- Stamped when the operator is first told, so a known freeze is not re-pushed every run.
  first_notified_at timestamptz,
  thaw_requested_at timestamptz,
  thaw_requested_by text,
  -- Set when the DEVICE stops reporting the fingerprint, not when the instruction is sent:
  -- a lost response must not discard the operator's decision.
  thawed_at         timestamptz,
  primary key (device_id, fingerprint)
);

create index if not exists device_upload_freezes_open_idx
  on device_upload_freezes (device_id) where thawed_at is null;

-- Backlog vitals live on the device row: one number per device, overwritten each report.
alter table devices add column if not exists backlog_oldest_age_s bigint;
alter table devices add column if not exists backlog_pending      int;
alter table devices add column if not exists backlog_reported_at  timestamptz;
```

- [ ] **Step 2: Write the failing test**

Read `tests/unit/test_device_heartbeat.py` first and copy its connection-stub style exactly.

`tests/unit/test_device_status.py`:

```python
import device_status


def test_phase1_never_thaws(fake_conn):
    out = device_status.record(fake_conn, "dev-1", {
        "oldestPendingAgeS": 93600, "pending": 12, "frozen": 3, "dead": 0,
        "fingerprints": ["uploadurl_401"],
    })
    assert out["thaw"] == []


def test_reports_the_deployed_build(fake_conn, monkeypatch):
    monkeypatch.setattr(device_status, "SERVER_BUILD", "9495bcd")
    out = device_status.record(fake_conn, "dev-1", {"pending": 0, "frozen": 0, "dead": 0})
    assert out["serverBuild"] == "9495bcd"


def test_an_idle_device_reports_a_null_age(fake_conn):
    device_status.record(fake_conn, "dev-1", {"pending": 0, "frozen": 0, "dead": 0})
    sql, params = fake_conn.executed[-1]
    assert params[0] is None


def test_a_malformed_body_is_absorbed_not_raised(fake_conn):
    # Telemetry must never fail a user's request. Same rule as device_heartbeat.
    out = device_status.record(fake_conn, "dev-1", {"pending": "twelve"})
    assert out["thaw"] == []


def test_an_unknown_device_writes_nothing_and_still_answers(fake_conn):
    fake_conn.rowcount = 0
    out = device_status.record(fake_conn, "no-such-device", {"pending": 1, "frozen": 0, "dead": 0})
    assert out["thaw"] == []
```

- [ ] **Step 3: Run to verify it fails**

```bash
python -m pytest tests/unit/test_device_status.py -v
```

Expected: `ModuleNotFoundError: No module named 'device_status'`.

- [ ] **Step 4: Write the implementation**

```python
"""Device backlog vitals, and the channel that will one day carry a thaw.

Rides the same discipline as `device_heartbeat`: nothing here may raise. Failing to
record how far behind a device is must never fail the request that carried the report.

Phase 1 answers with an empty `thaw` list unconditionally. The register exists, the
endpoint records vitals, and no device is ever told to try again — so this ships
without changing a single device's behaviour.
"""

import logging
import os

logger = logging.getLogger()

SERVER_BUILD = os.environ.get("SERVER_BUILD", "")

_VITALS = """
update devices set
  backlog_oldest_age_s = %s,
  backlog_pending      = %s,
  backlog_reported_at  = now()
where id = %s
"""


def _int_or_none(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def record(conn, device_id, vitals):
    """Record what the device said. Returns the response body."""
    try:
        with conn.cursor() as cur:
            cur.execute(_VITALS, (
                _int_or_none(vitals.get("oldestPendingAgeS")),
                _int_or_none(vitals.get("pending")),
                device_id,
            ))
    except Exception:
        logger.exception("device status vitals not recorded for %s", device_id)

    return {"serverBuild": SERVER_BUILD or None, "thaw": []}
```

- [ ] **Step 5: Run to verify it passes**

```bash
python -m pytest tests/unit/test_device_status.py -v
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Route it**

`dispatch()` in `src/lambda_org_api.py` calls `device_heartbeat.record(...)` **before** the
caller guard, on the argument that a device whose account is not provisioned yet must still
register as alive. The status probe belongs in exactly that position, for exactly that
reason: a backlog report from a device whose account is mis-provisioned is the single most
valuable report there is, and a 403 would discard it.

So place it immediately after the heartbeat line, before `caller = users.get_user_by_sub(...)`:

```python
    ident = device_heartbeat.parse_headers(event.get("headers"))
    device_heartbeat.record(conn, ident, sub)
    # Before the caller guard, same as the heartbeat above and for the same reason: the
    # device most worth hearing from is the one whose account is not yet right.
    if route == "/device/status" and method == "POST":
        return ok(device_status.record(conn, device_heartbeat.device_id(conn, ident), parse_body(event)))
```

Adjust the existing heartbeat line to reuse `ident` rather than parsing twice. Add
`import device_status` beside `import device_heartbeat` (line 130) — bare module name, the
Lambda zip is flat.

`device_heartbeat.device_id(conn, ident)` may not exist yet. Check `device_heartbeat.record`'s
return value first; if it does not already return the row id, add a small
`device_id(conn, ident)` helper there rather than duplicating the tag/uuid lookup in
`device_status`. It must return `None` for absent headers or an unknown device, and
`device_status.record` must tolerate `None` by writing nothing and still answering.

Use whatever the file's existing success helper is named (`ok(...)` above is a placeholder
for it) — read a neighbouring route's return statement and match it exactly.

Add a route test to `tests/unit/test_org_api_device_heartbeat.py`:

```python
def test_device_status_answers_before_the_caller_guard(...):
    """A device whose account is not provisioned still gets to report its backlog."""
    resp = handler_with_headers({"X-Device-Tag": "FS-01", "X-Device-Id": "uuid-1"},
                                route="/device/status", method="POST",
                                body={"pending": 3, "frozen": 1, "dead": 0},
                                sub="not-provisioned")
    assert resp["statusCode"] == 200
    assert json.loads(resp["body"])["thaw"] == []
```

Match the file's existing event-construction helpers rather than inventing
`handler_with_headers` — read the top of that test file and reuse what is there.

- [ ] **Step 7: Add the build env var**

In `src/template.yaml`, on the org-api function's `Environment.Variables`, add
`SERVER_BUILD: !Ref ServerBuild` and declare a `ServerBuild` parameter defaulting to `""`.
Then pass it from the deploy workflow's `sam deploy` as
`--parameter-overrides ServerBuild=${{ github.sha }}` alongside the existing overrides.

Check `.github/workflows/` for the exact override syntax already in use and match it.

- [ ] **Step 8: Run the full suite**

```bash
python -m pytest tests/unit -q
```

Expected: PASS (1343+ tests green).

- [ ] **Step 9: Commit**

```bash
git add src/migrations/0033_device_upload_freezes.sql src/device_status.py \
        src/lambda_org_api.py src/template.yaml \
        tests/unit/test_device_status.py tests/unit/test_org_api_device_heartbeat.py
git commit -m "feat(device): accept a backlog report, and answer with no instruction yet"
```

---

## Verification before calling Phase 1 done

- [ ] `./gradlew testProdDebugUnitTest` green in the GrandTime worktree; note the count.
- [ ] `python -m pytest tests/unit -q` green in the pipeline worktree; note the count.
- [ ] `grep -rn "markUploadStatus" app/src/main/` shows only `"uploading"` and `"uploaded"`.
- [ ] Task 3 Step 6's on-device migration check performed, with the logcat output recorded.
- [ ] On device (dev flavor against fieldsight-test): a forced `upload-url` 403 freezes the record, no retry storm appears in logcat, `UploadSummary` shows it as stuck, and the "Tonight: charger + Wi-Fi" line does **not** appear.
- [ ] On device: sign out, sign in as a second account — the frozen record stays frozen.
- [ ] Deployed test org-api returns `{"serverBuild": "...", "thaw": []}` for a real probe.

## What Phase 1 deliberately does not do

- No freeze rows are written and no thaw is ever issued (Phase 2).
- No Notion projection, no `Thaw` checkbox, no alerting (Phase 3).
- No "Tonight: charger + Wi-Fi" line yet — the classification it keys off ships here, the UI copy lands with Phase 2 so it can be verified against real frozen records rather than synthetic ones.
