# QR Sign-In (Passwordless, Terminal-Scans-Web) — Design Spec

**Date:** 2026-07-30
**Status:** Design (awaiting review) — supersedes the decode-only probe shipped in 0.5.7
**Repos touched:** GrandTime (mobile) · fieldsight-pipeline (backend + Cognito) · fieldsight-ui (web)

---

## 1. Problem & goal

Signing in on the F2SP terminal means typing an email + password on a hardware keypad — slow and
error-prone in the field. **Goal:** let an operator sign a terminal into their FieldSight account by
**scanning a one-time QR code shown in the FieldSight web app** — no typing on the terminal.

The 0.5.7 prod build already ships a **"Scan QR to Sign in"** button + a Camera2/ZXing scanner, but
it is **decode-only** (it shows the QR's raw text and does not authenticate). This spec defines the
real end-to-end flow that turns that scanner into an actual login.

### Non-goals (v1)
- **Admin provisioning** (an admin generating login QRs for *other* users). v1 is **self-service**:
  the QR logs in the account of whoever generated it. (Extension noted in §11.)
- Merging with the web Hosted-UI/PKCE path — the web keeps its existing login; we only add a
  "log in a terminal" affordance.
- Offline / pre-printed codes. Codes are live, 90-second, single-use.

---

## 2. Why Cognito Custom Auth (decision, already made)

The terminal's logged-in state is rooted in a **Cognito refresh token** bound to the **mobile app
client** (`4ratjdjonqm17tln6bs2761ci3`). Two facts force the architecture:

1. **Refresh tokens are per-app-client.** The web uses a *different* app client, so handing the web's
   refresh token to the terminal would be rejected by `REFRESH_TOKEN_AUTH`.
2. **The backend cannot mint Cognito tokens for a user** without either the password or a Cognito
   **custom auth** flow.

Therefore the terminal must obtain **its own** tokens by completing a **Cognito Custom Auth
(passwordless) challenge**, answering with a one-time code that the authenticated web session minted.
The terminal ends up with a normal Cognito id + refresh token for the mobile client and reuses **all**
existing plumbing (`TokenStore`, `silentLogin`, `freshIdToken`, identity derivation, uploads).

**Feasibility confirmed (0.5.7, device F2S202503103042):** Camera2 YUV + pure-Java ZXing decodes a
standard QR in 1–4 s on the 32-bit terminal. (Styled/logo QRs such as WeChat personal codes fail —
irrelevant; we generate a plain high-ECC login QR.)

---

## 3. End-to-end flow

```
┌─ FieldSight web (operator already signed in, on a phone/PC) ─┐
│ 1. Operator opens "Log in a terminal"                        │
│ 2. Web → POST /api/org/auth/qr/create  (Bearer web idToken)  │
│ 3. Backend mints one-time code, stores {code→user, 90s, 1×}  │
│ 4. Web renders QR = {v:1, u:<email>, c:<code>, env}          │
│    + 90s countdown / "Regenerate"                            │
└──────────────────────────────────────────────────────────────┘
                      │  (operator points terminal at the screen)
                      ▼
┌─ GrandTime terminal ─────────────────────────────────────────┐
│ 5. "Scan QR to Sign in" → camera → ZXing decodes payload      │
│ 6. Validate v==1, env==flavor; parse username + code          │
│ 7. InitiateAuth(CUSTOM_AUTH, USERNAME=u)  → Cognito           │
│      └─ DefineAuthChallenge → CreateAuthChallenge → Session   │
│ 8. RespondToAuthChallenge(answer = code, Session)             │
│      └─ VerifyAuthChallengeResponse Lambda checks the store   │
│         (exists · matches user · not expired · not consumed)  │
│         → marks consumed → answerCorrect=true                 │
│      └─ DefineAuthChallenge → issueTokens=true                │
│ 9. Cognito returns Id + Access + RefreshToken (mobile client) │
│ 10. Persist RT + onLoggedIn() → into the app                  │
└──────────────────────────────────────────────────────────────┘
```

The terminal talks **directly to Cognito** (`cognito-idp.<region>.amazonaws.com`) for steps 7–9,
exactly like the existing password flow — org-api is only involved in step 2 (minting the code).

---

## 4. QR payload

Compact JSON, decoded by the terminal:

```json
{ "v": 1, "u": "operator@example.com", "c": "<~128-bit url-safe code>", "env": "prod" }
```

- `v` — schema version (reject anything != 1).
- `u` — Cognito username (email). Needed because `InitiateAuth(CUSTOM_AUTH)` requires `USERNAME`
  up front. The username is not the secret; **`c` is**.
- `c` — the one-time code (≥128-bit entropy, URL-safe base64, no padding).
- `env` — `"prod"` | `"test"`. The terminal rejects a payload whose `env` != its build flavor to
  prevent accidental cross-environment logins (the Cognito pool is shared between test and prod, so
  this is a client-side guardrail, not an authorization boundary).

Non-FieldSight QRs (wrong shape / not JSON) → terminal shows "Not a FieldSight login code" and keeps
scanning.

---

## 5. Backend (fieldsight-pipeline)

### 5.1 New endpoint — `POST /api/org/auth/qr/create`
- **Auth:** existing org-api Cognito authorizer (caller must present a valid **web** idToken).
- **Body:** none. The target user = **the caller** (self-service). Username/sub read from token claims.
- **Logic:**
  1. Rate-limit: ≤5 creates/minute/user (see §7).
  2. Generate `code` = 24+ url-safe chars from a CSPRNG (≥128-bit).
  3. Put item in the code store (§6) with `expiresAt = now + 90s`, `consumed = false`,
     `username`, `createdAt`, `env`.
  4. Return `{ "code": "...", "username": "...", "expiresAt": <epoch_s>, "ttlSeconds": 90 }`.
- **Never log** the `code` value.

### 5.2 Cognito trigger Lambdas (3)
Deployed **once** against the shared pool `q88pd6XXr` (see §9 deployment nuance):

- **DefineAuthChallenge** — state machine:
  - no session yet → `challengeName=CUSTOM_CHALLENGE`, `issueTokens=false`, `failAuthentication=false`.
  - last challenge `challengeResult==true` → `issueTokens=true`.
  - else (wrong / >N attempts) → `failAuthentication=true`.
- **CreateAuthChallenge** — for `CUSTOM_CHALLENGE`: set `publicChallengeParameters={}` (nothing sent;
  the client already holds the code). No secret placed in params.
- **VerifyAuthChallengeResponse** — `challengeAnswer` = code, `userName` = the user:
  1. Look up `code` in the store.
  2. `answerCorrect` = record exists **AND** `record.username == event.userName` **AND**
     `now < record.expiresAt` **AND** `record.consumed == false`.
  3. If correct: **atomic** conditional update `consumed false→true` (single-use; a lost race →
     not correct).
  4. Never log the code.

Enumeration resistance: keep `PreventUserExistenceErrors=ENABLED` on the client so a bad/unknown user
looks the same as a wrong code.

### 5.3 App client change
Add `ALLOW_CUSTOM_AUTH` to the mobile client's `ExplicitAuthFlows` (keeps existing
`USER_PASSWORD_AUTH` + `REFRESH_TOKEN_AUTH`). The client has **no secret**, so custom auth works from
the app with `AuthParameters={USERNAME}` (plain CUSTOM_AUTH, **not** SRP).

---

## 6. Data store — `fieldsight-qr-login-codes` (DynamoDB)

| attr | type | notes |
|---|---|---|
| `code` | S (PK) | the one-time code |
| `username` | S | Cognito username the code logs in |
| `expiresAt` | N | epoch **seconds**; DynamoDB **TTL attribute** |
| `consumed` | BOOL | flipped true on first successful verify |
| `createdAt` | N | epoch seconds |
| `env` | S | `prod`/`test` (informational) |

- **Single shared table** (mirrors the shared Cognito pool). Both org-api deployments (test + prod)
  write to it; the single VerifyAuthChallenge trigger reads it. DynamoDB TTL is best-effort cleanup —
  Verify **also** checks `expiresAt` explicitly (TTL deletion can lag minutes).
- Ephemeral auth artifacts only — no customer data — so a shared table does not violate the
  test/prod data isolation (BUG-38).

---

## 7. Security model

- **Code entropy** ≥128-bit, CSPRNG, URL-safe.
- **TTL 90 s**, enforced in Verify (not just DynamoDB TTL).
- **Single-use** via atomic conditional update.
- **Bound to user**: Verify requires `record.username == challenge username`.
- **Create requires an authenticated web session**; v1 only mints for the caller's own account.
- **Rate limiting**: create ≤5/min/user; Verify inherits Cognito's per-session attempt cap
  (DefineAuthChallenge fails after N=3).
- **Never** put a password or refresh token in the QR — only an opaque one-time code + username.
- **No code in logs** anywhere (create, triggers, mobile).
- **Cross-env guardrail**: `env` in payload checked against build flavor on the terminal.
- **Shoulder-surf / photo risk** minimized by 90 s single-use; the QR is shown only to the
  authenticated web user.

---

## 8. Mobile (GrandTime)

### 8.1 `CognitoClient`
Add (mirroring the existing bare-HTTP `signIn`/`refresh`, injectable `http` for tests):
- `initiateCustomAuth(username): CustomAuthStep` → `InitiateAuth{AuthFlow:CUSTOM_AUTH,
  ClientId, AuthParameters:{USERNAME}}`; parse `{ChallengeName, Session}`.
- `respondCustomChallenge(username, session, code): AuthOutcome` →
  `RespondToAuthChallenge{ChallengeName:CUSTOM_CHALLENGE, ClientId, Session,
  ChallengeResponses:{USERNAME, ANSWER:code}}`; reuse `parseInitiateAuth` (returns
  `AuthOutcome.Tokens` on success).
- `signInWithCustomAuth(username, code): AuthOutcome` — the two-step convenience wrapper. On any
  challenge failure → `AuthOutcome.Error` with a generic message (no enumeration leak).

### 8.2 `AuthManager` / `CognitoAuthManager`
- Add `suspend fun signInWithQrCode(username: String, code: String): SignInResult`.
- Refactor the existing "Tokens → decode claims → derive MediaScope → tokenStore.save →
  onLoggedIn" block out of `signIn` into a private `persistAndEnter(tokens)` helper; both password
  and QR paths call it. **No new persistence shape** — QR login yields the same `PersistedSession`.

### 8.3 `QrScanScreen`
Evolve the probe into the real screen:
- On decode: parse `QrLoginPayload` (`{v,u,c,env}`). Reject non-login / wrong-`v` / wrong-`env`
  with an inline message; keep scanning.
- On valid payload: stop the camera, show a spinner "Signing in…", call
  `auth.signInWithQrCode(u, c)`.
  - `Success` → `onSignedIn()` (dismiss scanner, enter app).
  - `Failure(msg)` → show msg + "Scan again" (re-arm the camera).
- Remove the developer "Frames analysed / raw content" UI. Keep the orientation-lock, the
  rotate-retry decode, and the physical-back handling already shipped.
- New `QrLoginPayload` data class + a pure parser (unit-testable).

### 8.4 `LoginScreen`
- The "Scan QR to Sign in" button already exists (yellow, matches Sign in). Wire the scanner's
  `onSignedIn` to the real login callback so a successful scan enters the app.

---

## 9. Deployment (order + the shared-pool nuance)

**Top risk — the Cognito pool is shared across test *and* prod, so the 3 triggers + the client-flow
change are pool/client-level and take effect for both at once. There is no separate pool to rehearse
on.** Mitigation:

1. Deploy the **code table** + **3 trigger Lambdas** + attach the pool `LambdaConfig` **once**
   (from the prod SAM stack, or a dedicated auth mini-stack — **not** double-configured from two
   stacks, which would fight over the pool's LambdaConfig). Grant the deploy role IAM for the new
   resources (memory *org-api-new-route-iam-trap*: a missing grant → CREATE_FAILED rolls back the
   whole stack).
2. Add `ALLOW_CUSTOM_AUTH` to the mobile client.
3. Deploy `POST /api/org/auth/qr/create` to **test** org-api first; grant both org-api roles IAM to
   the shared code table.
4. **Rehearse end-to-end on the same pool** using the **test** create endpoint + **dev** mobile
   flavor (dev + prod mobile share the pool and app client) before announcing prod.
5. Deploy the create endpoint to prod; ship web + mobile.

Because triggers only fire for `CUSTOM_AUTH`, existing `USER_PASSWORD_AUTH` (web + mobile password
login) is unaffected throughout.

---

## 10. Web (fieldsight-ui)

- Add a **"Log in a terminal"** affordance (Settings page, or a small dedicated view).
- On open: `POST /api/org/auth/qr/create` via the existing token-bearing fetch; render the QR of
  `{v:1,u:<session.user.email>,c:<code>,env:<from config>}`.
- **QR rendering with no build step**: vendor a single-file pure-JS QR generator (e.g.
  `qrcode-generator`, MIT) into `scripts/vendor/` and draw to a `<canvas>`; use high error
  correction (H) and no center logo. (Consistent with the repo's "no npm/build" rule — see its
  CLAUDE.md.)
- Show a **90 s countdown**; on expiry, disable the code and offer **"Regenerate"**.
- Copy: explain "Scan this with the terminal's *Scan QR to Sign in* button. Expires in 90 s."

---

## 11. Testing

- **Mobile (JVM):** `CognitoClient` custom-auth request shaping (inject fake `http`, assert body);
  `QrLoginPayload` parser (valid / wrong v / wrong env / non-JSON / missing fields). **Device:**
  full scan→login on the F2SP; wrong/expired code path.
- **Backend (their uv/pytest harness, FakeConn + a DynamoDB stub):** create (code shape, TTL,
  rate-limit); DefineAuthChallenge state machine; VerifyAuthChallenge (valid / expired / consumed /
  wrong-user); single-use race (second verify fails).
- **Web:** manual — QR renders, countdown, regenerate, real terminal scans it.

---

## 12. Extensions (later, not v1)
- **Admin provisioning**: an admin picks a user and mints a login QR for them (needs role check +
  user picker; the create endpoint gains an optional `targetUsername` gated by admin authz).
- **Per-device naming / revocation** UI (custom auth already gives the terminal its own session, so
  GlobalSignOut / device tracking becomes possible).
- Speeding decode below 1 s (higher analysis resolution / tighter loop) if field use needs it.

---

## 13. Open questions for review
1. **Code-table ownership** — prod SAM stack vs a dedicated auth mini-stack? (Recommend: prod stack,
   referenced by test org-api via parameter + IAM.)
2. **Self-service only for v1?** (Recommend yes; admin provisioning as a fast follow.)
3. **Web entry placement** — Settings page vs a dedicated "Devices" view? (Recommend: Settings for
   v1.)
4. **90 s TTL** acceptable, or do field conditions need longer (e.g., 180 s)?
