# QR Terminal Sign-In v2 — Mobile Implementation Plan (GrandTime)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or executing-plans. Steps use `- [ ]`. TDD where a JVM test fits (parser, request shaping); Camera/Compose = device-verified.

**Goal:** Replace the mobile CUSTOM_AUTH path with: redeem the scanned code at the org-api for a refresh token, then `REFRESH_TOKEN_AUTH` (reusing the existing `refresh()`), then the existing persist path.

**Architecture:** Trim `QrLoginPayload` to `{code, env}` (v2); drop the `CustomAuthOutcome`/initiate/respond methods; add a tiny unauthenticated `redeemQrCode(code)` HTTP call to the org gateway; `signInWithQrCode(code)` = redeem → `refresh()` → `persistAndEnter`.

**Tech Stack:** Kotlin/Compose, bare-HTTP (`CognitoClient` uses an injectable `http`), the `net/` org-api client, `TokenStore`. Build/test: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"; ./gradlew testProdDebugUnitTest`.

**Design spec:** `docs/superpowers/specs/2026-07-31-qr-login-refresh-handoff-design.md` (§6).
**Base:** cut `feat/qr-login-v2-mobile` off `feat/qr-login-mobile` (the v1 branch — these are edits ON TOP of v1) or off `main` then re-apply; simplest is off the v1 branch.

## Global Constraints
- No new Gradle deps. Reuse the existing persist path (`persistAndEnter`) + `refresh()` (REFRESH_TOKEN_AUTH) — identical `PersistedSession`, one identity path. Generic failure copy ("Invalid or expired QR code — generate a new one"). Env guard unchanged (prod rejects non-prod; dev any). **Never** log the code/token. Redeem is UNAUTHENTICATED (no Authorization header). QR payload = `{v:2, c, env}`.

---

### Task 1: `QrLoginPayload` v2 (drop username)

**Files:** Modify `auth/QrLoginPayload.kt`; Test `auth/QrLoginPayloadTest.kt`.

- [ ] **Step 1:** Update tests: `data class QrLoginPayload(val code: String, val env: String)` (no username); `parse` accepts `{"v":2,"c":..,"env":..}`, rejects `v!=2` / missing `c`/`env` / non-JSON / blank. Rewrite the existing test bodies to the v2 shape (valid v2 → `QrLoginPayload("CODE","prod")`; `rejects wrong version` uses `"v":1`).
- [ ] **Step 2:** Run `./gradlew testProdDebugUnitTest --tests "*QrLoginPayloadTest*"` → FAIL.
- [ ] **Step 3:** Implement: `optInt("v",-1)!=2 → null`; read `c`,`env` (not `u`); return `QrLoginPayload(c, env)`.
- [ ] **Step 4:** Run tests → PASS. Commit `feat(auth): QrLoginPayload v2 (code+env, drop username)`.

---

### Task 2: `redeemQrCode` + drop CUSTOM_AUTH methods

**Files:** Modify `auth/CognitoClient.kt` (+ `CognitoClientTest.kt`).

**Interfaces:** Produces `redeemQrCode(code: String): String?` — POSTs `{ "code": code }` to `{orgBaseUrl}/api/org/auth/qr/redeem` (NO auth header), returns the `refreshToken` string on 200, else null. Remove `CustomAuthOutcome`, `initiateCustomAuth`, `respondToCustomChallenge`, `signInWithCustomAuth`.

- [ ] **Step 1:** Determine the org base URL the app uses for the redeem POST — grep `net/` (RecordingsApiClient/SitesApiClient) + `BuildConfig`/config for the org gateway base (prod `ys94qy2tk0`, dev `wdsgobb7b0`). `redeemQrCode` posts to `<orgBase>/api/org/auth/qr/redeem`. Reuse the app's existing HTTP client (OkHttp) rather than adding one.
- [ ] **Step 2:** Write a JVM test (inject fake http): a 200 body `{"refreshToken":"RT-1"}` → `redeemQrCode` returns `"RT-1"`; a 401 → returns null. Assert the request URL ends `/api/org/auth/qr/redeem`, method POST, body has `code`, and NO Authorization header.
- [ ] **Step 3:** Run → FAIL. Implement `redeemQrCode` (bare-HTTP, parse `refreshToken`, null on non-200 / parse error / network — never log the code/token). Delete `CustomAuthOutcome` + the 3 custom-auth methods.
- [ ] **Step 4:** Run `./gradlew testProdDebugUnitTest --tests "*CognitoClientTest*"` → PASS (redeem tests + remaining refresh/signIn tests; delete the old custom-auth tests). Commit `feat(auth): redeemQrCode over org-api; remove CUSTOM_AUTH client methods`.

---

### Task 3: `signInWithQrCode(code)` = redeem → refresh → persist

**Files:** Modify `auth/AuthManager.kt` (interface) + `auth/CognitoAuthManager.kt`.

- [ ] **Step 1:** Interface: change to `suspend fun signInWithQrCode(code: String): SignInResult` (drop username).
- [ ] **Step 2:** Impl:
```kotlin
override suspend fun signInWithQrCode(code: String): SignInResult {
    val rt = withContext(Dispatchers.IO) { client.redeemQrCode(code) }
        ?: return SignInResult.Failure("Invalid or expired QR code — generate a new one")
    return when (val r = withContext(Dispatchers.IO) { client.refresh(rt) }) {
        is AuthOutcome.Tokens -> persistAndEnter(r)
        else -> SignInResult.Failure("Invalid or expired QR code — generate a new one")
    }
}
```
(`persistAndEnter` + `refresh()` already exist from v1 / silentLogin.)
- [ ] **Step 3:** `./gradlew testProdDebugUnitTest` (whole suite green) + `assembleDevDebug` (compiles). Commit `feat(auth): signInWithQrCode = redeem code → REFRESH_TOKEN_AUTH → persist`.

---

### Task 4: Wire the scanner (v2 payload, code-only)

**Files:** Modify `ui/QrScanScreen.kt` (+ `LoginScreen.kt` if the call site signature changed).

- [ ] **Step 1:** In `onDecoded`: `QrLoginParser.parse(raw)` → payload `{code, env}`; env-guard unchanged (`payload.env != BuildConfig.QR_ENV && BuildConfig.QR_ENV == "prod"` → reject); on valid → `auth.signInWithQrCode(payload.code)` (drop the username arg). Keep the busy/lastAttempted re-arm + camera machinery.
- [ ] **Step 2:** `./gradlew testProdDebugUnitTest assembleDevDebug assembleProdDebug` → unit green + both APKs build. Commit `feat(auth): scanner redeems v2 code (drop username)`.

- [ ] **Step 3: Device acceptance (needs backend redeem live):** install dev APK on F2SP → scan a real web QR → redeem → refresh → **enters the app**; record something → uploads under the right folder; negative: expired code (>90s) / random QR → generic messages.

## Self-Review
**Spec §6 coverage:** payload v2 → Task 1; redeemQrCode + remove custom-auth → Task 2; signInWithQrCode(code) redeem→refresh→persist → Task 3; scanner → Task 4. **Placeholder:** the org base URL + HTTP client are "grep the existing net/ layer" — real repo-specific lookups. **Consistency:** `QrLoginPayload(code, env)` used in Task 1 (produced) + Task 4 (`payload.code`/`.env`); `signInWithQrCode(code)` signature identical in Tasks 3 (def) + 4 (call); `redeemQrCode(code): String?` → `refresh(rt)` → `AuthOutcome.Tokens`.
