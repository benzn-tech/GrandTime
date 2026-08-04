# QR-Code Login (device link) — Design

**Date:** 2026-07-29
**Feature:** Let a field device sign in by scanning a QR code shown in the FieldSight web app — no typing email/password on the small device screen.
**Repos:** mobile `GrandTime` (this session's scope), backend + web `fieldsight-pipeline` (**backend deferred to another session**).

## Goal

A worker (already signed in on the FieldSight web app, where typing is easy) opens their web **Profile → "Link device"**, which shows a QR code. On the device, the login screen has a **"Scan QR to sign in"** button; scanning the QR signs the device in as that worker — without typing credentials. The device stays signed in for ~6 months.

## Scope (first version)

- **Self-service only** (worker links their own device from their own web Profile). Admin/bulk provisioning is a later, separate feature.
- **Auth mechanism = "beam" (chosen over Cognito CUSTOM_AUTH):** the web mints a short-lived one-time link token bound to its own Cognito refresh token; the device exchanges the link token for that refresh token and stores it. **No Cognito pool triggers, no new app client** — reuses the app's existing `TokenStore`/refresh plumbing.
- **Device session lifetime ≈ 6 months:** raise the shared Cognito app client's `RefreshTokenValidity` to **180 days**. NOTE: web and device share this client, so web sessions also become 180 days — accepted tradeoff of the shared-client beam approach.
- **QR decode = ZXing-core** (pure-Java, no native code → armeabi-safe). The repo's "no new Gradle deps" constraint exists because of the armeabi-32 ABI; a pure-Java library does not hit that concern, so ZXing-core is acceptable here.

## Architecture / flow

```
Web (signed in)                 Backend (fieldsight-pipeline)            Device (GrandTime)
  Profile "Link device"
   POST /api/org/device-link  →  mint: store {linkToken → refreshToken,
   {refreshToken}                 user, exp=+2min} (refreshToken encrypted)
                              ←  { linkToken, expiresAt }
   render QR(linkToken) + 2min countdown
                                                                          Login "Scan QR"
                                                                          → camera → ZXing decode → linkToken
                                POST /api/device-link/exchange  ←────────  { linkToken }
                                (UNAUTHENTICATED route)
                                validate (exists / not expired / not
                                consumed / attempt-limit) → mark consumed
                                → { refreshToken, sub, email, name }  ───→  TokenStore.save(refreshToken, identity)
                                                                          → LoggedIn (existing freshIdToken/refresh path)
```

## Components

### 1. Cognito config (backend session)
Raise the shared app client (`4ratjdjonqm17tln6bs2761ci3`, pool `ap-southeast-2_q88pd6XXr`) `RefreshTokenValidity` to **180 days**. Set on the actually-deployed client (the SAM template's `UserPoolClient` may be out-of-band from the live client — set carefully, verify with `describe-user-pool-client`). Applies to newly-issued tokens only.

### 2. Backend — DB (backend session)
Migration `00NN_device_link_tokens.sql` following the `0016_site_voice.sql` shape:
```
CREATE TABLE device_link_tokens (
  link_token   text PRIMARY KEY,          -- 128-bit random, opaque, URL-safe
  user_sub     text NOT NULL,             -- Cognito sub of the linking user
  refresh_token bytea NOT NULL,           -- the user's Cognito refresh token, ENCRYPTED at rest
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,      -- created_at + 2 min
  consumed_at  timestamptz,               -- set on successful exchange (one-time)
  attempts     int NOT NULL DEFAULT 0     -- exchange attempts, cap to block guessing
);
CREATE INDEX idx_device_link_tokens_exp ON device_link_tokens (expires_at);
```
`repositories/device_link.py`: `create(conn, user_sub, refresh_token) -> link_token`, `consume(conn, link_token) -> row|None` (atomic: check not expired / not consumed / attempts<cap, increment attempts, set consumed_at, return the row). A reaper (cron or lazy delete) prunes expired rows. Refresh token encrypted with a KMS data key or a app-managed key (never plaintext at rest).

### 3. Backend — mint endpoint (backend session)
`POST /api/org/device-link` — **authenticated** (existing Cognito authorizer; caller = `claims.sub`). Body `{ refreshToken }` (the web's current session refresh token). Creates a row, returns `{ linkToken, expiresAt }`. Added to `lambda_org_api.py` `dispatch()` beside the other POST routes.

### 4. Backend — exchange endpoint (backend session)
`POST /api/device-link/exchange` — **UNAUTHENTICATED** (the device is not yet signed in). Must be carved out of the default `CognitoAuthorizer` (`template.yaml` `DefaultAuthorizer`) — a dedicated small Lambda + route with `Auth: { Authorizer: NONE }` (mirrors the `HealthCheck` opt-out). Body `{ linkToken }`. Validates + consumes; returns `{ refreshToken, sub, email, name }` on success, else a typed error (`expired` / `consumed` / `invalid` / `too_many_attempts`). Rate-limit by IP + the per-row attempts cap.

### 5. Web — Profile "Link device" (backend/web session)
In the current `ui/` app: a "Link device" action on the Profile/Account area → calls `POST /api/org/device-link` with the session refresh token → renders `linkToken` as a QR (a small QR lib or a data-URL) with a 2-minute countdown + "regenerate". The current `ui/` prototype uses mocks and is not yet wired to the live org-api — this feature requires wiring the Profile page to the live backend for these two calls.

### 6. Mobile — `QrScanScreen` (THIS session)
A Compose screen: CameraX/Camera2 preview + per-frame **ZXing-core** decode (`MultiFormatReader`/`QRCodeReader` on the luminance frame) → on a decoded `linkToken`, stop scanning and hand it back. Reuse the app's existing camera permission flow. Handle: no camera permission, torch toggle (optional), a manual "cancel" back to the login screen. Pure-Java ZXing → add `com.google.zxing:core` (no `zxing-android-embedded`, which pulls native/camera deps — we drive the camera ourselves).

### 7. Mobile — login entry point (THIS session)
`LoginScreen` adds a prominent **"Scan QR to sign in"** button (opens `QrScanScreen`); the existing email/password form stays as a fallback. Also fixes, while here, the rotation-clears-fields bug already scoped separately (rememberSaveable / orientation lock) — but that fix ships independently and is NOT part of this spec.

### 8. Mobile — exchange + store (THIS session)
On a scanned `linkToken`: call `POST /api/device-link/exchange` (via a new `net/DeviceLinkClient` following `SitesApiClient`/`RecordingsApiClient` — but NO Authorization header, the route is unauthenticated) → on `{ refreshToken, sub, email, name }`, feed it into the existing session plumbing: derive folder (`UserFolder.derive`), `TokenStore.save(...)`, set `LoggedIn`, prime `freshIdToken()` via `client.refresh(refreshToken)`. This is a new `AuthManager` entry point (e.g. `signInWithRefreshToken(refreshToken, identity)`), reusing everything `CognitoAuthManager.silentLogin` already does. On exchange error, show the typed message and return to the login form.

## Security

- `linkToken`: ≥128-bit CSPRNG, opaque, URL-safe. **One-time** (consumed on first successful exchange). **2-minute** TTL. The QR encodes ONLY the opaque token (no email, no password, no refresh token).
- The user's refresh token is stored **encrypted at rest** in `device_link_tokens`, deleted on exchange (and reaped on expiry) — minimal exposure window.
- Exchange endpoint: per-row `attempts` cap (block guessing) + IP rate-limit; HTTPS only.
- Coupling accepted: device shares the web session's refresh token; a web `GlobalSignOut`/RevokeToken would also sign out the device. Web tab-close (sessionStorage clear) does NOT revoke, so it does not affect the device.

## Error handling

| Case | Behavior |
|---|---|
| linkToken expired / consumed / invalid | device shows "This code has expired — regenerate it on the web", returns to login |
| too many exchange attempts | device shows a generic failure; row locked |
| web mint without a valid session | web prompts re-login |
| device offline / timeout on exchange | retryable error, "check your connection" |
| camera permission denied | scan screen explains + offers the password form |

## Testing

- **Mobile (unit, JVM):** the exchange-response → session mapping (a pure function turning `{refreshToken, sub, email, name}` into a `PersistedSession` + folder), and the QR payload parse/validate. ZXing decode + camera = device-verified.
- **Mobile (device):** scan a real QR from the web → signed in; expired/invalid QR → correct error; session persists across app restart (6-month token).
- **Backend (backend session):** mint + exchange round-trip, one-time + TTL + attempts, encryption at rest, the unauthenticated-route carve-out.
- **End-to-end:** web Profile QR → device scan → signed-in as the correct user → can select site + use the app.

## Execution split

- **Backend + Cognito + web** (§1–§5): **another session**, in `fieldsight-pipeline`. This spec is the contract.
- **Mobile** (§6–§8) + the ZXing dependency: **this session** — the writing-plans + implementation target. Mobile can be built against the agreed endpoint contract and integration-tested once the backend session lands.

## Non-goals

- Admin/bulk device provisioning (a later feature; this beam design + the `device_link_tokens` table are a foundation it can reuse).
- Cognito CUSTOM_AUTH / a dedicated device app client (the cleaner "fully independent device session" path — deferred; revisit if the 6-month shared-client tradeoff proves insufficient).
- Fixing the rotation-clears-login-fields bug (tracked + shipped separately).
