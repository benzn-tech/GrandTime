# QR Passwordless Sign-In — Mobile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the decode-only "Scan QR to Sign in" probe (shipped in 0.5.7) into a real login: scan the web's QR, complete Cognito custom auth, and enter the app with the terminal's own tokens.

**Architecture:** A pure payload parser + two new `CognitoClient` custom-auth calls (`InitiateAuth CUSTOM_AUTH` → `RespondToAuthChallenge`) + an `AuthManager.signInWithQrCode` that reuses the exact same token-persist path as password login. `QrScanScreen` decodes → parses → signs in; `LoginScreen` already hosts the button.

**Tech Stack:** Kotlin/Compose, Camera2 + ZXing (already added in 0.5.7), bare-HTTP Cognito (`CognitoClient`), Room/`TokenStore` (unchanged). Tests: JUnit (`./gradlew testProdDebugUnitTest`). Camera/Compose are device-verified (no JVM harness for them — see CLAUDE.md).

**Depends on:** the backend plan (`fieldsight-pipeline/docs/superpowers/plans/2026-07-30-qr-login-backend.md`) being deployed + its Task 4 Step 5 CLI rehearsal green — the mobile flow cannot complete an end-to-end login until the Cognito triggers + `ALLOW_CUSTOM_AUTH` are live on the shared pool. Tasks 1–3 (pure code + unit tests) can be built before that; Task 4 device-acceptance needs the backend live.

**Design spec:** `../specs/2026-07-30-qr-login-design.md`.

## Global Constraints

- **No new Gradle dependencies** beyond ZXing (already present). All Android framework.
- **Reuse the existing persist path** — QR login must produce the identical `PersistedSession` as password login (`refreshToken`, `sub`, `displayName`, `folder`, `namePrefix`) and go through the same `onLoggedIn`. No second identity code path.
- **Cognito username = email**; identity in the token = `sub`. The terminal sends the QR's `u` (email) as `USERNAME`; Cognito resolves it. (Backend Verify matches on `sub`.)
- **Generic failure messages** — never reveal whether a user exists ("Invalid or expired QR code — generate a new one").
- **Env guard** — prod flavor accepts only `env=="prod"` payloads; dev flavor accepts any (v1 has a prod-only create endpoint, and redemption is env-agnostic, so dev terminals must be able to redeem prod codes for testing).
- Build/test: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` then `./gradlew testProdDebugUnitTest` / `assembleDevDebug`. Dropbox build-lock is transient → re-run.

---

## File Structure

- **Create:** `app/src/main/java/com/benzn/grandtime/auth/QrLoginPayload.kt` — payload data class + pure parser.
- **Create:** `app/src/test/java/com/benzn/grandtime/auth/QrLoginPayloadTest.kt`.
- **Modify:** `auth/CognitoClient.kt` — `CustomAuthOutcome`, `initiateCustomAuth`, `respondToCustomChallenge`, `signInWithCustomAuth`.
- **Modify:** `auth/CognitoClientTest.kt` — custom-auth tests.
- **Modify:** `auth/AuthManager.kt` — add `signInWithQrCode` to the interface.
- **Modify:** `auth/CognitoAuthManager.kt` — implement `signInWithQrCode`; refactor persist into `persistAndEnter`.
- **Modify:** `ui/QrScanScreen.kt` — decode→parse→sign-in (replace probe UI); take `onSignedIn`.
- **Modify:** `ui/LoginScreen.kt` — pass `onSignedIn` into `QrScanScreen`.
- **Modify (cleanup):** `ui/Screen.kt`, `ui/MainActivity.kt`, `ui/SettingsScreen.kt` — remove the logged-in `Screen.QR_SCAN` probe entry (QR login only makes sense pre-login).
- **Modify:** `app/build.gradle.kts` — per-flavor `QR_ENV` BuildConfig field.

---

### Task 1: QR payload parser

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/auth/QrLoginPayload.kt`
- Test: `app/src/test/java/com/benzn/grandtime/auth/QrLoginPayloadTest.kt`

**Interfaces:**
- Produces: `data class QrLoginPayload(val username: String, val code: String, val env: String)` and `object QrLoginParser { fun parse(raw: String): QrLoginPayload? }` (null = not a valid v1 login QR).

- [ ] **Step 1: Write the failing test**

Create `QrLoginPayloadTest.kt`:

```kotlin
package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrLoginPayloadTest {
    @Test fun `parses a valid v1 payload`() {
        val p = QrLoginParser.parse("""{"v":1,"u":"a@b.com","c":"CODE123","env":"prod"}""")
        assertEquals(QrLoginPayload("a@b.com", "CODE123", "prod"), p)
    }

    @Test fun `rejects wrong version`() {
        assertNull(QrLoginParser.parse("""{"v":2,"u":"a@b.com","c":"C","env":"prod"}"""))
    }

    @Test fun `rejects missing code`() {
        assertNull(QrLoginParser.parse("""{"v":1,"u":"a@b.com","env":"prod"}"""))
    }

    @Test fun `rejects missing username`() {
        assertNull(QrLoginParser.parse("""{"v":1,"c":"C","env":"prod"}"""))
    }

    @Test fun `rejects non-JSON (e.g. a random website QR)`() {
        assertNull(QrLoginParser.parse("https://example.com"))
    }

    @Test fun `rejects blank`() {
        assertNull(QrLoginParser.parse(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testProdDebugUnitTest --tests "*QrLoginPayloadTest*"`
Expected: FAIL — `QrLoginParser` unresolved.

- [ ] **Step 3: Write the implementation**

Create `QrLoginPayload.kt`:

```kotlin
package com.benzn.grandtime.auth

import org.json.JSONObject

/** Payload carried by a FieldSight login QR: {"v":1,"u":<email>,"c":<one-time code>,"env":"prod"}. */
data class QrLoginPayload(val username: String, val code: String, val env: String)

object QrLoginParser {
    /** Returns null for anything that isn't a valid v1 FieldSight login QR (wrong shape, other QRs). */
    fun parse(raw: String): QrLoginPayload? = runCatching {
        val o = JSONObject(raw)
        if (o.optInt("v", -1) != 1) return null
        val u = o.optString("u").takeIf { it.isNotBlank() } ?: return null
        val c = o.optString("c").takeIf { it.isNotBlank() } ?: return null
        val env = o.optString("env").takeIf { it.isNotBlank() } ?: return null
        QrLoginPayload(u, c, env)
    }.getOrNull()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testProdDebugUnitTest --tests "*QrLoginPayloadTest*"`
Expected: PASS (6 tests). (`org.json` is available in unit tests — see `build.gradle.kts` testImplementation.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/auth/QrLoginPayload.kt \
        app/src/test/java/com/benzn/grandtime/auth/QrLoginPayloadTest.kt
git commit -m "feat(auth): QR login payload parser"
```

---

### Task 2: CognitoClient custom-auth methods

**Files:**
- Modify: `auth/CognitoClient.kt`
- Test: `auth/CognitoClientTest.kt`

**Interfaces:**
- Consumes: existing `http: (target, body) -> HttpResult`, `clientId`, `parseInitiateAuth`, `AuthOutcome`.
- Produces:
  - `sealed interface CustomAuthOutcome { data class Challenge(val session: String); data class Error(val message: String) }`
  - `fun initiateCustomAuth(username: String): CustomAuthOutcome`
  - `fun respondToCustomChallenge(username: String, session: String, code: String): AuthOutcome`
  - `fun signInWithCustomAuth(username: String, code: String): AuthOutcome`

- [ ] **Step 1: Write the failing tests**

Add to `CognitoClientTest.kt`:

```kotlin
@Test fun `initiateCustomAuth returns the challenge session`() {
    val fake: (String, String) -> HttpResult = { target, _ ->
        assertEquals("InitiateAuth", target)
        HttpResult(200, """{"ChallengeName":"CUSTOM_CHALLENGE","Session":"sess-1","ChallengeParameters":{}}""")
    }
    val client = CognitoClient("clientId", "ap-southeast-2", fake)
    val r = client.initiateCustomAuth("a@b.com") as CustomAuthOutcome.Challenge
    assertEquals("sess-1", r.session)
}

@Test fun `signInWithCustomAuth two-step happy path returns Tokens`() {
    val fake: (String, String) -> HttpResult = { target, _ ->
        when (target) {
            "InitiateAuth" -> HttpResult(200, """{"ChallengeName":"CUSTOM_CHALLENGE","Session":"sess-1"}""")
            "RespondToAuthChallenge" -> HttpResult(200, """{"AuthenticationResult":{"IdToken":"idX","RefreshToken":"rtX"}}""")
            else -> HttpResult(400, "{}")
        }
    }
    val client = CognitoClient("clientId", "ap-southeast-2", fake)
    assertEquals(AuthOutcome.Tokens("idX", "rtX"), client.signInWithCustomAuth("a@b.com", "CODE"))
}

@Test fun `signInWithCustomAuth maps a wrong or expired code to Error`() {
    val fake: (String, String) -> HttpResult = { target, _ ->
        when (target) {
            "InitiateAuth" -> HttpResult(200, """{"ChallengeName":"CUSTOM_CHALLENGE","Session":"sess-1"}""")
            else -> HttpResult(400, """{"__type":"NotAuthorizedException","message":"Incorrect."}""")
        }
    }
    val client = CognitoClient("clientId", "ap-southeast-2", fake)
    assertTrue(client.signInWithCustomAuth("a@b.com", "BAD") is AuthOutcome.Error)
}

@Test fun `initiateCustomAuth maps a non-challenge response to Error`() {
    val fake: (String, String) -> HttpResult = { _, _ ->
        HttpResult(400, """{"__type":"UserNotFoundException"}""")
    }
    val client = CognitoClient("clientId", "ap-southeast-2", fake)
    assertTrue(client.initiateCustomAuth("nobody@b.com") is CustomAuthOutcome.Error)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testProdDebugUnitTest --tests "*CognitoClientTest*"`
Expected: FAIL — `CustomAuthOutcome` / `initiateCustomAuth` unresolved.

- [ ] **Step 3: Write the implementation**

In `CognitoClient.kt`, add the sealed type near `AuthOutcome`:

```kotlin
sealed interface CustomAuthOutcome {
    data class Challenge(val session: String) : CustomAuthOutcome
    data class Error(val message: String) : CustomAuthOutcome
}
```

Add these methods to the `CognitoClient` class (alongside `signIn`/`refresh`):

```kotlin
/** Step 1 of passwordless QR auth: begin CUSTOM_AUTH; returns the challenge Session. */
fun initiateCustomAuth(username: String): CustomAuthOutcome {
    val body = JSONObject()
        .put("AuthFlow", "CUSTOM_AUTH")
        .put("ClientId", clientId)
        .put("AuthParameters", JSONObject().put("USERNAME", username))
        .toString()
    return runCatching {
        val json = JSONObject(http("InitiateAuth", body).body)
        val session = json.optString("Session").takeIf { it.isNotBlank() }
        if (json.optString("ChallengeName") == "CUSTOM_CHALLENGE" && session != null) {
            CustomAuthOutcome.Challenge(session)
        } else {
            CustomAuthOutcome.Error(errorMessageFor(json.optString("__type")))
        }
    }.getOrElse { CustomAuthOutcome.Error("Network error — check your connection") }
}

/** Step 2: answer the CUSTOM_CHALLENGE with the one-time code; success yields Tokens. */
fun respondToCustomChallenge(username: String, session: String, code: String): AuthOutcome {
    val body = JSONObject()
        .put("ChallengeName", "CUSTOM_CHALLENGE")
        .put("ClientId", clientId)
        .put("Session", session)
        .put("ChallengeResponses", JSONObject().put("USERNAME", username).put("ANSWER", code))
        .toString()
    return runCatching { parseInitiateAuth(http("RespondToAuthChallenge", body)) }
        .getOrElse { AuthOutcome.Error("Network error — check your connection") }
}

/** Convenience: run both steps. Any challenge failure → AuthOutcome.Error. */
fun signInWithCustomAuth(username: String, code: String): AuthOutcome =
    when (val init = initiateCustomAuth(username)) {
        is CustomAuthOutcome.Challenge -> respondToCustomChallenge(username, init.session, code)
        is CustomAuthOutcome.Error -> AuthOutcome.Error(init.message)
    }
```

> `errorMessageFor` and `parseInitiateAuth` are already in the `companion object` — reference them directly (they're accessible from instance methods of the same class).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testProdDebugUnitTest --tests "*CognitoClientTest*"`
Expected: PASS (existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/auth/CognitoClient.kt \
        app/src/test/java/com/benzn/grandtime/auth/CognitoClientTest.kt
git commit -m "feat(auth): CognitoClient CUSTOM_AUTH (initiate + respond + signInWithCustomAuth)"
```

---

### Task 3: AuthManager.signInWithQrCode + shared persist path

**Files:**
- Modify: `auth/AuthManager.kt`
- Modify: `auth/CognitoAuthManager.kt`

**Interfaces:**
- Consumes: `client.signInWithCustomAuth` (Task 2), existing `JwtDecoder`, `UserFolder`, `tokenStore`, `onLoggedIn`.
- Produces: `AuthManager.signInWithQrCode(username: String, code: String): SignInResult`.

> **Testing note:** `CognitoAuthManager` has no JVM unit test today (it depends on a Room `CaptureRecordDao`), so this task is verified by (a) the CognitoClient tests from Task 2 covering the risky request/parse logic, (b) the full unit suite staying green after the refactor, and (c) device acceptance in Task 4. The success path reuses the *identical* code as password `signIn`, which is already proven in production.

- [ ] **Step 1: Add the interface method**

In `auth/AuthManager.kt`, add to the `AuthManager` interface:

```kotlin
/** Passwordless sign-in via a scanned QR code (Cognito custom auth). */
suspend fun signInWithQrCode(username: String, code: String): SignInResult
```

- [ ] **Step 2: Refactor the persist path in `CognitoAuthManager`**

Extract the success block currently inside `signIn`'s `is AuthOutcome.Tokens ->` branch into a private helper, and call it from `signIn`:

```kotlin
private fun persistAndEnter(tokens: AuthOutcome.Tokens): SignInResult {
    val claims = JwtDecoder.decode(tokens.idToken)
        ?: return SignInResult.Failure("Login failed — please try again")
    val refresh = tokens.refreshToken
        ?: return SignInResult.Failure("Login failed — please try again")
    val mediaScope = UserFolder.derive(claims.name, claims.email, claims.sub)
    val displayName = claims.name ?: claims.email ?: mediaScope.namePrefix ?: "User"
    idTokenCache = tokens.idToken
    tokenStore.save(
        PersistedSession(refresh, claims.sub, displayName, mediaScope.folder, mediaScope.namePrefix)
    )
    onLoggedIn(claims.sub, displayName, mediaScope)
    return SignInResult.Success
}
```

Replace `signIn`'s Tokens branch body with `return persistAndEnter(r)` (behaviour-preserving).

- [ ] **Step 3: Implement `signInWithQrCode`**

```kotlin
override suspend fun signInWithQrCode(username: String, code: String): SignInResult {
    return when (val r = withContext(Dispatchers.IO) { client.signInWithCustomAuth(username, code) }) {
        is AuthOutcome.Tokens -> persistAndEnter(r)
        else -> SignInResult.Failure("Invalid or expired QR code — generate a new one")
    }
}
```

- [ ] **Step 4: Verify no regression**

Run: `./gradlew testProdDebugUnitTest`
Expected: whole suite PASS (the refactor is behaviour-preserving; no test references the extracted helper directly). Also confirm it compiles (`assembleDevDebug`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/auth/AuthManager.kt \
        app/src/main/java/com/benzn/grandtime/auth/CognitoAuthManager.kt
git commit -m "feat(auth): AuthManager.signInWithQrCode reusing the password persist path"
```

---

### Task 4: Wire the scanner to real sign-in + env guard + probe cleanup

**Files:**
- Modify: `app/build.gradle.kts` (per-flavor `QR_ENV`)
- Modify: `ui/QrScanScreen.kt`, `ui/LoginScreen.kt`
- Modify: `ui/Screen.kt`, `ui/MainActivity.kt`, `ui/SettingsScreen.kt` (remove probe entry)

**Interfaces:**
- Consumes: `QrLoginParser` (Task 1), `AuthManager.signInWithQrCode` (Task 3), `BuildConfig.QR_ENV`.
- Produces: `QrScanScreen(onSignedIn: () -> Unit)`.

> Camera2 + Compose are device-verified (no JVM harness — CLAUDE.md). The only pure logic (parsing) is already unit-tested in Task 1.

- [ ] **Step 1: Add per-flavor `QR_ENV` BuildConfig**

In `app/build.gradle.kts`, in the `prod` flavor block add:
```kotlin
            buildConfigField("String", "QR_ENV", "\"prod\"")
```
and in the `dev` flavor block add:
```kotlin
            buildConfigField("String", "QR_ENV", "\"test\"")
```

- [ ] **Step 2: Rewrite `QrScanScreen` to sign in on decode**

Replace the probe's decoded-text UI. Change the signature to `fun QrScanScreen(onSignedIn: () -> Unit)`. Keep the existing camera/orientation-lock/rotate-retry machinery; change only what happens on a successful decode:

```kotlin
@Composable
fun QrScanScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { (context.applicationContext as GrandTimeApp).authManager }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Point the camera at the login QR") }
    var busy by remember { mutableStateOf(false) }

    val scanner = remember {
        QrScanner(
            context = context,
            onStatus = { status = it },
            onFrame = {},
            onDecoded = { raw ->
                if (busy) return@QrScanner
                val payload = QrLoginParser.parse(raw)
                when {
                    payload == null -> status = "Not a FieldSight login code — try again"
                    payload.env != BuildConfig.QR_ENV && BuildConfig.QR_ENV == "prod" ->
                        status = "This code is for a different environment"
                    else -> {
                        busy = true
                        status = "Signing in…"
                        scope.launch {
                            when (val r = auth.signInWithQrCode(payload.username, payload.code)) {
                                SignInResult.Success -> onSignedIn()
                                is SignInResult.Failure -> { status = r.message; busy = false }
                                SignInResult.NewPasswordRequired -> {
                                    status = "Set your password in the web app first"; busy = false
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    // ... keep the existing DisposableEffect (orientation lock + scanner.stop) and the
    //     Column { Box{ AndroidView(SurfaceView…) }  Text(status) } layout, minus the
    //     "Frames analysed / Content" probe rows.
}
```

Notes for the implementer:
- `QrScanner` (the private controller in this file) is unchanged; it must keep firing `onDecoded` per frame. The `busy`/`found`-style guard now lives in the composable so a failed sign-in can re-arm scanning (do **not** keep the old permanent `found=true` latch — instead, on a failed attempt reset so the user can rescan; on success the screen is dismissed by `onSignedIn`).
- Import `com.benzn.grandtime.BuildConfig`, `com.benzn.grandtime.GrandTimeApp`, `com.benzn.grandtime.auth.QrLoginParser`, `com.benzn.grandtime.auth.SignInResult`, `androidx.compose.runtime.rememberCoroutineScope`, `kotlinx.coroutines.launch`.

- [ ] **Step 3: Pass `onSignedIn` from `LoginScreen`**

In `ui/LoginScreen.kt`, the scanner branch becomes:
```kotlin
    if (showScanner) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = "Scan QR to Sign in", showBack = true, onBack = { showScanner = false }, serviceRunning = false)
            QrScanScreen(onSignedIn = onSignedIn)
        }
        return
    }
```
(`onSignedIn` is already `LoginScreen`'s parameter. On success, `signInWithQrCode` also flips `AppState.loginState`, so `MainScaffold` swaps to the app automatically — calling `onSignedIn` keeps it explicit and closes the scanner.)

- [ ] **Step 4: Remove the logged-in probe entry (cleanup)**

- `ui/SettingsScreen.kt`: delete the `SettingRow("Scan QR (test)", null) { onOpen(Screen.QR_SCAN) }` row + its trailing `RowDivider()`.
- `ui/MainActivity.kt`: remove `Screen.QR_SCAN -> QrScanScreen()` from the `when`, and drop `Screen.QR_SCAN` from the `isSubScreen` expression.
- `ui/Screen.kt`: remove the `QR_SCAN(...)` enum entry.

(QR login is a pre-login action; the only entry point is the login-screen button.)

- [ ] **Step 5: Build both flavors**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; ./gradlew testProdDebugUnitTest assembleDevDebug assembleProdDebug`
Expected: unit suite PASS; both APKs build. (Dropbox lock → re-run.)

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/benzn/grandtime/ui/QrScanScreen.kt \
        app/src/main/java/com/benzn/grandtime/ui/LoginScreen.kt \
        app/src/main/java/com/benzn/grandtime/ui/Screen.kt \
        app/src/main/java/com/benzn/grandtime/ui/MainActivity.kt \
        app/src/main/java/com/benzn/grandtime/ui/SettingsScreen.kt
git commit -m "feat(auth): QR scanner performs real custom-auth sign-in; remove decode-only probe entry"
```

- [ ] **Step 7: Device acceptance (needs backend live)**

Prereq: backend plan deployed + Task 4 Step 5 rehearsal green; `ALLOW_CUSTOM_AUTH` on the client; triggers on the pool.
1. Install dev: `adb -s <serial> install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk`.
2. On a phone/PC, sign into FieldSight web, open "Log in a terminal" (web plan), show the QR.
3. On the terminal login screen → "Scan QR to Sign in" → scan → **enters the app signed in as that account**.
4. Verify: record something → it uploads under the correct user folder (identity derived correctly).
5. Negative: expired code (wait >90 s) → "Invalid or expired QR code"; a random QR → "Not a FieldSight login code".

---

## Self-Review

**Spec coverage** (§8 mobile): 8.1 CognitoClient custom-auth → Task 2. 8.2 `signInWithQrCode` + `persistAndEnter` refactor → Task 3. 8.3 QrScanScreen real sign-in + payload parse + env guard → Tasks 1+4. 8.4 LoginScreen wiring → Task 4.3. Payload shape (§4) → Task 1.

**Placeholder scan:** Task 4 Step 2 shows a `// ... keep the existing …` note — that is a deliberate "preserve existing machinery" instruction with the exact surrounding structure named, not an unwritten block; the new behaviour (the `onDecoded` lambda) is given in full. All other steps have complete code.

**Type consistency:** `QrLoginPayload(username, code, env)` used identically in Task 1 (produced) and Task 4 (consumed: `payload.username`/`.code`/`.env`). `signInWithQrCode(username, code)` signature identical in Task 3 (interface + impl) and Task 4 (call). `CustomAuthOutcome.Challenge(session)` / `.Error(message)` consistent across Task 2. `signInWithCustomAuth` returns `AuthOutcome` (Task 2) consumed as `AuthOutcome.Tokens` vs else in Task 3.

**Cross-plan dependency:** device acceptance (Task 4 Step 7) requires the backend plan's Cognito wiring live — flagged in the header and the step. Tasks 1–3 are independently unit-testable now.
