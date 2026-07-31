# QR Terminal Sign-In v2 — Refresh-Token Handoff (Design Spec)

**Date:** 2026-07-31
**Status:** Design (approved) — **supersedes** the Cognito-CUSTOM_AUTH design
(`2026-07-30-qr-login-design.md`), which was deployed but is blocked in prod (see §1).
**Repos touched:** fieldsight-pipeline (backend) · fieldsight-ui (web) · GrandTime (mobile)

---

## 1. Why this redesign

The v1 design authenticated the terminal via a Cognito **CUSTOM_AUTH** flow (Define/Create/Verify
Lambda triggers + a one-time code). It was fully built + reviewed + **deployed to prod**, but the
end-to-end login **cannot complete**, for two facts discovered at deployment:

1. **The prod pool `ap-southeast-2_q88pd6XXr` is ESSENTIALS-tier with "choice-based" sign-in**
   (`Policies.SignInPolicy.AllowedFirstAuthFactors = ["PASSWORD"]`). Cognito's choice-based model
   has **no custom-auth first factor** (valid values: `SOFTWARE_TOKEN, SMS_OTP, EMAIL_OTP,
   EMAIL_MAGIC_LINK, WEB_AUTHN, PASSWORD`). `initiate-auth CUSTOM_AUTH` is rejected
   (`NotAuthorizedException`) **before the Define trigger is ever invoked** (its log group never
   gets created). The `SignInPolicy` cannot be removed via `update-user-pool` (it is sticky on
   ESSENTIALS). So legacy CUSTOM_AUTH is not usable on this pool without a risky, uncertain,
   all-customer-impacting tier/sign-in reconfiguration.

2. **v1's core premise was wrong.** v1 chose CUSTOM_AUTH because "the web uses a *different* app
   client, so its refresh token can't be reused by the terminal." **The pool has exactly ONE app
   client** (`fieldsight-web-client` = `4ratjdjonqm17tln6bs2761ci3`); **web and mobile both use it**
   (fieldsight-ui `scripts/auth/cognito.js:43`; the mobile app). Refresh-token rotation is **off**.

Because web + terminal share one app client with no rotation, a **refresh token minted for the web
session is valid for the terminal via `REFRESH_TOKEN_AUTH`** — and `REFRESH_TOKEN_AUTH` is **not**
governed by `SignInPolicy`, so it works on this pool as-is. This makes the entire CUSTOM_AUTH
machinery unnecessary. This spec replaces it with a simpler **refresh-token handoff**.

### Session model (accepted)
The terminal ends up holding the **same** refresh token as the web session (one client, no
rotation). Practical effect: terminal + web are the same session lineage — a **global sign-out**
from web also signs the terminal out; the terminal independently renews id/access tokens until the
refresh token's validity (30 days) lapses, then re-scans. This coupling is accepted.

---

## 2. End-to-end flow (B1: one-time code + redeem endpoint)

```
Web (already signed in; holds a refresh token from its own login)
 1. Operator opens "Log in a terminal".
 2. Web → POST /api/org/auth/qr/create  (Bearer web idToken)  body { refreshToken: <web's RT> }
 3. Backend mints a one-time code, stores { code → refreshToken (+ sub), 90 s, single-use } in DynamoDB.
 4. Web renders QR = { "v":2, "c":<code>, "env":<env> } + a 90 s countdown / "Regenerate".
        │  (operator points the terminal at the screen)
        ▼
GrandTime terminal
 5. "Scan QR to Sign in" → camera/ZXing decodes → parse { v==2, env==flavor(prod)|any(dev), c }.
 6. POST /api/org/auth/qr/redeem  { code }   (UNAUTHENTICATED — the terminal has no session yet)
      └─ backend: code exists AND not consumed AND now < expiresAt → atomic consume → return { refreshToken }.
 7. Terminal: CognitoClient.refresh(refreshToken)  → REFRESH_TOKEN_AUTH → { IdToken, AccessToken }.
 8. persistAndEnter(tokens) → into the app (identical persist path as password login).
```

The **credential is never in the QR** (only the opaque 90 s single-use code). The refresh token
lives server-side for ≤90 s and is returned once, to the terminal, on redeem.

## 3. QR payload (v2)
`{ "v":2, "c":"<~256-bit url-safe code>", "env":"prod" }` — **no `u`/username** (redemption is
code-only; the terminal gets the token, not a username). `v` bumps to `2`; the terminal rejects
`v!=2`. `env` guard unchanged (prod flavor rejects non-`prod`; dev accepts any).

---

## 4. Backend (fieldsight-pipeline)

### 4.1 `POST /api/org/auth/qr/create` (authed — modify)
- Body now carries `refreshToken` (the web's). Store `{ code, refreshToken, sub, consumed:false,
  createdAt, expiresAt = now+90 }`. (`sub` from token claims, kept for audit/binding.)
- Unchanged: existing Cognito authorizer, rate-limit ≤5/min/user, `secrets.token_urlsafe(32)`,
  201 `{ code, expiresAt, ttlSeconds }`. **Never log** `code` or `refreshToken`.

### 4.2 `POST /api/org/auth/qr/redeem` (UNAUTHENTICATED — new)
- **Public route** (no Cognito authorizer). Exposure approach: an **explicit
  `/api/org/auth/qr/redeem` POST method on the org-api gateway with NO authorizer**, integrated to
  the **same** `lambda_org_api` function. `dispatch()` handles it **before** the caller-guard
  (so it never requires a resolved caller). *This is the one route that bypasses the caller-guard —
  keep it minimal.*
- Body `{ code }`. Logic: get item; valid = exists AND `consumed==false` AND `now < expiresAt`;
  atomic single-use consume (`ConditionExpression consumed=false`); on success return `200
  { refreshToken }`; else `401/400 { error: "Invalid or expired code" }` (generic, no enumeration).
- Rate-limit per source (reuse the per-minute counter pattern, keyed by code-prefix / IP-ish).
- **Never log** `code` or `refreshToken`.

### 4.3 Remove the CUSTOM_AUTH machinery (revert the deployed v1 infra)
- **Delete** `src/lambda_qr_auth.py` + its tests.
- **Template:** remove `QrAuthDefineFunction`/`QrAuthCreateFunction`/`QrAuthVerifyFunction`, the 3
  `QrAuth*Permission`, and the 3 `QrAuth*FunctionArn` Outputs. **Keep** `QrLoginCodesTable`
  (still gated `IsProdWithOrgApi`; add nothing — `refreshToken` is a non-key attribute, no schema
  change). Keep OrgApiFunction's `QR_CODES_TABLE` env + Put/Update IAM; **add** the redeem route's
  gateway method (no-authorizer) to the template.
- **Un-wire the shared pool** (out-of-band, user-run or CLI): `update-user-pool` to clear
  `LambdaConfig` back to `{}` (describe→merge→update, preserving all other fields — the exact
  reverse of what was applied; rollback capture already exists at
  `deploy-backup/pool.json`). Optionally remove `ALLOW_CUSTOM_AUTH` from the client (harmless to
  leave). This restores the pool/client to their pre-QR state.

### 4.4 Data store `fieldsight-qr-login-codes` (DynamoDB — repurpose)
`{ code (S, PK), refreshToken (S), sub (S), consumed (BOOL), createdAt (N), expiresAt (N, TTL) }`.
Encrypted at rest (DynamoDB default). Single-use (atomic conditional update). 90 s TTL (enforced in
code too). The refresh token is the sensitive attribute — never logged, consumed on first redeem.

---

## 5. Web (fieldsight-ui)
- `FS.api.qrLogin.create()`: include the **web's current refresh token** in the POST body. The web
  holds it in its Cognito session (`scripts/auth/cognito.js`); read it and send over the existing
  authenticated `orgRequest` (HTTPS). No new persistence.
- `QrLoginModal`: build the QR from `{ v:2, c:res.code, env }` (drop `u`). Countdown / Regenerate /
  `_notFound`-guard / `ariaLabel` — unchanged from what shipped.
- Registration + `?v=` bumps as before.

## 6. Mobile (GrandTime)
- `QrLoginPayload` → `{ code, env }` (drop `username`); parser rejects `v!=2` / wrong shape.
- `CognitoClient`: **remove** `CustomAuthOutcome` + `initiateCustomAuth`/`respondToCustomChallenge`/
  `signInWithCustomAuth`. **Add** a tiny `redeemQrCode(code): String?` bare-HTTP call to
  `POST {orgBaseUrl}/api/org/auth/qr/redeem { code }` → returns the `refreshToken` (or null on
  failure). Reuse the existing `refresh(refreshToken)` (REFRESH_TOKEN_AUTH) for step 7.
- `AuthManager.signInWithQrCode(code)` (drop the username param): `redeemQrCode(code)` → on token,
  `refresh(rt)` → `persistAndEnter`; on any failure → generic "Invalid or expired QR code —
  generate a new one".
- `QrScanScreen`: `onDecoded` parses `{v,c,env}`, env-guards, calls `signInWithQrCode(payload.code)`.
  Camera machinery + re-arm (busy/lastAttempted) unchanged.

---

## 7. Security model
- QR carries only a **90 s single-use** opaque code — a photo/shoulder-surf is useless after 90 s or
  first redeem (**credential never in the QR**).
- Redeem endpoint: unauthenticated **by necessity** (terminal has no session), but returns a token
  **only** for a valid unconsumed unexpired code; **atomic single-use**; rate-limited; generic
  errors (no enumeration); **never logs** code/token. This is the same security posture as v1's
  Cognito-direct redemption — only the mechanism changes.
- Refresh token stored server-side ≤90 s, encrypted at rest, consumed on first redeem.
- Web → backend refresh-token transfer rides the existing authenticated HTTPS create call.
- Create requires an authenticated web session; v1-style self-service only (the code logs in the
  caller's own account).

## 8. Testing
- **Backend (pytest, FakeConn + Dynamo stub):** create (stores refreshToken + code shape + TTL +
  rate-limit); redeem (valid returns token / expired / consumed / unknown → generic error); single-
  use race (2nd redeem fails); redeem is reachable pre-caller-guard (dispatch routes it public).
- **Web:** manual — QR renders `{v:2,c,env}`, countdown/regenerate, real terminal redeems.
- **Mobile (JVM):** `QrLoginPayload` parser (v2 shape / wrong v / wrong env / non-JSON);
  `redeemQrCode` request shaping (inject fake http, assert body/URL). **Device:** full scan→redeem→
  refresh→login on the F2SP; wrong/expired code path.

## 9. Deployment order
1. Backend: create-mod + redeem endpoint + remove trigger funcs (keep table) → deploy (prod, via
   the same hotfix-off-main discipline, or the team's release train).
2. Un-wire the shared pool `LambdaConfig` (describe→merge→update, restore to `{}`; pool otherwise
   untouched). No customer impact (removing an inert trigger config).
3. Web + mobile ship after the redeem endpoint is live.
4. Device acceptance on the F2SP.
No Cognito custom-auth / no SignInPolicy change anywhere — the whole choice-based-pool blocker is
side-stepped.

## 10. What carries over from v1 (already built/deployed)
- `QrLoginCodesTable` (keep). Web `QrLoginModal` + create-endpoint scaffold + mobile
  `QrScanScreen`/`persistAndEnter`/`refresh()` (keep, adapt). The Cognito triggers + pool
  `LambdaConfig` + `ALLOW_CUSTOM_AUTH` are **removed/unwound** per §4.3.

## 11. Open items for review
1. Redeem rate-limit keying (per-code-prefix vs per-IP) — the endpoint is unauthenticated, so an
   IP/source-based limiter is the natural guard; confirm the gateway passes a usable source id.
2. Whether to also remove `ALLOW_CUSTOM_AUTH` from the client during cleanup (recommend: leave it;
   harmless) — decide at cleanup time.
