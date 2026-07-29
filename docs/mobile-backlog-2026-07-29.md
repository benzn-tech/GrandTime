# FieldSight Mobile (GrandTime / F2SP) — Backlog & Pending Work (2026-07-29)

**Scope:** everything the mobile app still owes, *beyond* the chunk-session client
contract (which has its own full spec, `mobile-client-session-contract-design.md`).
Ordered by priority. Device: SDJW-F2SP body-cam, Android; account `509194952652`
(prod + test share one Aurora). Companion context: the `/fieldsight-mobile` skill.

---

## P0 — Chunk-session client contract (the flagship prerequisite)

**Full spec:** `mobile-client-session-contract-design.md` (this folder). Listed here so
it's never lost from the backlog — it is the **highest-priority mobile work** and the
thing the whole ≤2-minute confirmation email + per-meeting separation depends on.
In short: mint a device `session_id` (32-hex) per record-press, chop into ~1-min chunks
(`{device}_{date}_{HH-MM-SS}_sid{32hex}_c{NNNN}.{ext}`), keep a local session manifest
(open/close/pause + timestamps), store-and-forward offline, Pause/End UX + best-effort
`POST /api/org/sessions/{id}/open|close`. **Backend is shipped but inert until the device
sends `session_id`.**

Why it matters here: the backend already derives one `session_base` per *recording file*
from the filename timestamp, so *today* multiple real app recordings on a day should
already separate into sessions — but that is fragile (clock, offline, one-file-per-press).
The `session_id` work makes the separation **durable and offline-proof**, and is what the
"web shows RECORDINGS=0 / can't separate meeting minutes" problem is ultimately solved by
(real recordings → extraction-sourced topics carrying session identity).

---

## P1 — Photo-during-recording bug (data loss, user-facing)

**Symptom:** taking a photo *while* recording video → black screen + the recording's
upload fails.
**Root cause (diagnosed):** a full-5MP still capture grabs the camera sensor and starves
the recording's GL stream; separately, a truncated/unvalidated MP4 gets queued for upload.
**Fix direction:**
1. Do **not** grab the full sensor mid-recording — take a downscaled still, or grab a frame
   from the live recording stream, so the video pipeline isn't starved.
2. **Validate the MP4** (moov atom present, non-zero duration) before enqueuing; drop or
   repair truncated files instead of uploading a corrupt one.
**Needs:** real-device repro on the SDJW-F2SP.

---

## P2 — G5b app-side finish (recording → site attribution)

**Backend:** write side shipped 2026-07-17 — `recordings.site_id` is stamped from the
in-app project pick and overrides the membership fallback (the *only* way an admin-account
recording attributes to a site).
**App-side owed:** guarantee the in-app site/project selection is **always** sent as
`siteId` in `create_recording_upload_url`, for every capture path (worker and admin
accounts, video/audio/photo). Confirm reliability — a missing `siteId` silently falls back
to membership resolution (or nothing for admin accounts).

---

## P3 — prod-voice (SP-Ask on production)

**SP-Ask** = voice Q&A over site history: STT / RAG / TTS all cloud, device only records +
plays (no on-device voice packs), PTT-triggered.
**Owed:** run it against **prod** endpoints (confirm the wiring is prod, not test/dev), and
keep the **independent, trimmed voice system prompt** (per the 2026-07-15 constraint — the
voice path uses its own concise prompt, not the screen prompt).

---

## P4 — Phase-2 package rename → `com.benzn.fieldsight`

Rename the app's `applicationId` / package from the current (GrandTime/legacy) id to
`com.benzn.fieldsight`. Touch points to update: `AndroidManifest`, signing config, any
Play/store listing, deep-link schemes, and — if the app is a Cognito client — the allowed
callback/identifiers. Watch the disabled `com.corget` (a legacy component; `pm enable`
restores it) so the rename doesn't collide.

---

## Device landmines (not tasks — must-not-break invariants)

- **ROM falsely reports `SENSOR_ORIENTATION=0`** → sideways-video landmine; legacy
  landscape clips exist. New capture is **720P / 16:9, no wide-angle crop**; fixing
  wide-angle needs **on-device sensor-size enumeration** (don't trust ROM metadata).
- **Boot auto-start** works without a whitelist; **Dropbox holds a build lock** — pause
  Dropbox sync or build outside the synced folder or the build fails.
- Auth: Cognito pool `q88pd6XXr` (`fieldsight-users`), `USER_PASSWORD_AUTH` (no secret),
  raw **idToken in `Authorization`**, `sub` = `author_sub`.

**Memory pointers:** `grandtime-project`, `grandtime-photo-during-recording-bug`,
`grandtime-f2sp-device-facts`, `grandtime-smartptt-led-recon`, `grandtime-sp-ask-design`,
`fieldsight-cognito-mobile-auth`, `fieldsight-recording-site-attribution-gap`.
