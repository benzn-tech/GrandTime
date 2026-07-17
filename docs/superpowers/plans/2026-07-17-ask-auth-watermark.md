# Ask auth token expiry fix + watermark shade removal — Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** (A) Fix `freshIdToken()` returning an expired cached idToken (root cause of Ask "AuthExpired" after ~1h and intermittent empty site list); (B) remove the full-width translucent black shade behind the watermark so it doesn't cover the picture.

**Architecture:** (A) Decode the cached JWT's `exp` and refresh via the refresh token when it's expired/near-expiry instead of blindly returning the cache. (B) Drop the `Canvas.drawColor` band in `WatermarkRenderer`; the existing black stroke around white text keeps legibility.

**Tech Stack:** Kotlin, Android; pure JVM for JWT decode; no new deps.

## Global Constraints
- All Android framework; no new Gradle deps. English for new code/comments.
- Flavored gradle: unit test `testProdDebugUnitTest`; build `assembleProdDebug`. `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`; Dropbox build-lock → rerun once.
- idToken goes BARE in the Authorization header (unchanged). Cognito refresh flow unchanged; only add a client-side expiry check before reusing the cache.

---

### Task 1: freshIdToken() must refresh an expired cached token

**Root cause:** `CognitoAuthManager.freshIdToken()` does `idTokenCache?.let { return it }` — returns the cached idToken with no expiry check. Cognito idTokens last ~1h; after that every authed call (ask, `/org/sites`, recordings) sends the stale token → 401. `silentLogin()` populates the cache once at start and it's only cleared on logout, so the app serves an expired token for the rest of a long session.

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/auth/JwtDecoder.kt` (add `exp` + pure `isExpired`)
- Modify: `app/src/main/java/com/benzn/grandtime/auth/CognitoAuthManager.kt` (`freshIdToken` expiry check)
- Test: `app/src/test/java/com/benzn/grandtime/auth/JwtDecoderTest.kt` (extend — may already exist)

**Interfaces:**
- Produces: `JwtClaims.exp: Long` (epoch seconds; 0 if absent); `JwtDecoder.isExpired(idToken, nowMillis, skewSeconds): Boolean`.

- [ ] **Step 1: Failing tests** — add to `JwtDecoderTest.kt` (create if absent). Use a helper to build an unsigned JWT with a given `exp`:

```kotlin
private fun tokenWithExp(expSeconds: Long): String {
    val header = android.util.Base64.encodeToString("{\"alg\":\"none\"}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    val payload = android.util.Base64.encodeToString("{\"sub\":\"u1\",\"exp\":$expSeconds}".toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    return "$header.$payload.sig"
}
```

If `android.util.Base64` isn't available under the JVM unit test, use `java.util.Base64.getUrlEncoder().withoutPadding()` instead (matching how `JwtDecoder` decodes — it uses `java.util.Base64.getUrlDecoder()`, so encode with `java.util.Base64.getUrlEncoder()`). Tests:

```kotlin
@Test fun `decode reads exp`() {
    val t = tokenWithExp(1_800_000_000L)
    assertEquals(1_800_000_000L, JwtDecoder.decode(t)!!.exp)
}
@Test fun `isExpired true for past token`() =
    assertTrue(JwtDecoder.isExpired(tokenWithExp(1_000_000_000L), nowMillis = 2_000_000_000_000L))
@Test fun `isExpired false for future token`() =
    assertFalse(JwtDecoder.isExpired(tokenWithExp(2_000_000_000L), nowMillis = 1_000_000_000_000L))
@Test fun `isExpired true within skew window`() =
    // exp = now+30s, skew 60s → treated expired
    assertTrue(JwtDecoder.isExpired(tokenWithExp(1_000_000_030L), nowMillis = 1_000_000_000_000L, skewSeconds = 60))
@Test fun `isExpired true for unparseable token`() =
    assertTrue(JwtDecoder.isExpired("not-a-jwt", nowMillis = 1_000_000_000_000L))
```

- [ ] **Step 2: Run → FAIL** — `./gradlew testProdDebugUnitTest --tests '*JwtDecoderTest*'` (unresolved `exp`/`isExpired`).

- [ ] **Step 3: Implement** — `JwtDecoder.kt`:
  - Add `exp: Long` to `JwtClaims` (last param).
  - In `decode`, read `val exp = obj.optLong("exp", 0L)` and pass it.
  - Add:

```kotlin
/** True if the idToken is unreadable, has no exp, or expires within skewSeconds of now (default 60s). */
fun isExpired(idToken: String, nowMillis: Long = System.currentTimeMillis(), skewSeconds: Long = 60): Boolean {
    val exp = decode(idToken)?.exp ?: return true
    if (exp <= 0L) return true
    return nowMillis / 1000 >= exp - skewSeconds
}
```

- [ ] **Step 4: Run → PASS.** Also fix any EXISTING `JwtDecoder.decode(...)` call sites / tests that construct or destructure `JwtClaims` positionally so they compile with the new `exp` field (search: `JwtClaims(`).

- [ ] **Step 5: Wire into freshIdToken** — `CognitoAuthManager.kt`, change the first line of `freshIdToken()` from:

```kotlin
        idTokenCache?.let { return it }
```
to:
```kotlin
        // Reuse the cached idToken only while it's still valid; a Cognito idToken lasts ~1h and
        // the cache is otherwise held for the whole session, so without this check every call
        // after the first hour ships an expired token (401 -> ask/sites fail). Expired -> refresh.
        idTokenCache?.let { if (!JwtDecoder.isExpired(it)) return it }
```
(Leave the rest of `freshIdToken` — the refresh-token path — unchanged.)

- [ ] **Step 6: Build + test** — `./gradlew testProdDebugUnitTest` (all green) + `./gradlew assembleProdDebug`.

- [ ] **Step 7: Commit** — `git commit -m "fix(auth): refresh expired cached idToken in freshIdToken (fixes Ask AuthExpired + intermittent empty sites)"` naming the 3 files.

---

### Task 2: Remove the watermark shade band

**Problem:** `WatermarkRenderer.render` fills the whole (full-width) bitmap with `Color.argb(110,0,0,0)`, so the watermark sits on a full-width translucent-black band that covers the picture. The white text already has a black stroke outline for legibility, so the band is unnecessary.

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/camera2/WatermarkRenderer.kt`

- [ ] **Step 1: Remove the band** — delete line `c.drawColor(Color.argb(110, 0, 0, 0)) // 半透明黑底条`. The bitmap stays `ARGB_8888` (transparent by default), so only the stroked+filled text is drawn; everything else is transparent over the live frame.

- [ ] **Step 2: Update the doc comment** — change the class KDoc from "半透明黑底条 + 白字黑描边" to describe just "white text with black stroke, transparent background" (English or keep the file's existing Chinese style — this file's KDoc is Chinese; match it). Optionally bump the stroke width for legibility without the band: `strokeWidth = textSize * 0.12f` → `0.16f` (small, improves contrast over bright frames). Keep if it reads well; otherwise leave 0.12f.

- [ ] **Step 3: Build** — `./gradlew assembleProdDebug` (this file has no unit test — GL/photo overlay is device-verified). Confirm `testProdDebugUnitTest` still green (unaffected).

- [ ] **Step 4: Commit** — `git commit -m "feat(watermark): drop full-width black shade band; keep white text + black stroke"` naming the file.

---

## Self-Review
- A: `exp` added to `JwtClaims`; existing `JwtClaims(` call sites updated; `isExpired` pure + tested (past/future/skew/unparseable); `freshIdToken` reuses cache only when `!isExpired`. Refresh-token path untouched.
- C: only `drawColor` removed; text draw loop + sizing untouched; transparent background preserves the picture.
- Device acceptance (T3, separate): after ~1h of app runtime, Ask still answers (token auto-refreshed); site list stable; watermark legible with no black band on video + photo.
