# FieldSight mobile app → prod pipeline integration fixes (G1–G5a) + app rename

**Date:** 2026-07-16
**Status:** design approved (verbal 2026-07-16), pending written review → writing-plans
**Repo:** this one (GrandTime / F2SP recorder — to be renamed "FieldSight mobile app", Phase 2)
**Cross-repo:** G5b (pipeline consumes `recordings.site_id`) is a SEPARATE fieldsight-pipeline task, NOT here. Source spec with all evidence/IDs: `fieldsight-pipeline/docs/superpowers/specs/2026-07-16-grandtime-app-prod-integration.md`.

## Problem
2026-07-16 morning test recordings (Ellesmere project selected in-app) never reached the customer PROD site. Root cause: the app uploads to the **TEST** org-api gateway (recordings land in `fieldsight-data-test-509194952652`, registered in the TEST Aurora), plus format/date/filename mismatches with the pipeline ingest.

## Code reality (verified 2026-07-16 in this repo) — scope is smaller than the source spec assumed
- **Cognito is already PROD**: `COGNITO_POOL_ID = ap-southeast-2_q88pd6XXr`, `COGNITO_CLIENT_ID = 4ratjdjonqm17tln6bs2761ci3` (`app/build.gradle.kts`). No change.
- **G2 upload flow already implemented** (SP4b): `UploadWorker` + `RecordingsApiClient` do `POST /org/recordings/upload-url` → `PUT` presigned → `POST /complete`; `UploadUrlReq` already carries `siteId`, `fileName`, `startedAt`, `clientUuid`, `contentType`. `uploadUrl()` sends `siteId = record.siteId` (stamped from the in-app site pick). **NOT a direct-to-bucket writer.** The source spec's "no recordings row" observation is because it checked PROD Aurora; the rows are in TEST. So G2 needs **zero code** — G1 (host) alone routes it to prod, registered, with siteId.
- `ORG_API_BASE_URL = https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api` — the TEST gateway (the `/prod` is the APIGW stage name, not the environment). Prod gateway is `ys94qy2tk0`.
- `UploadWorker.iso8601(millis) = Instant.ofEpochMilli(millis).toString()` → **UTC `Z`** → server `startedAt[:10]` = UTC date → off-by-one for NZ evenings (evidence: filename `20260716`, folder `2026-07-15`).
- `MediaStorage` stamps `yyyyMMdd_HHmmss` (compact, no dashes), device-local timezone (already NZ). AUDIO kind ext = `m4a`.
- `AudioRecorder` = `MediaRecorder` AAC/MPEG_4 (M4A). **Reused by SP-Ask's `AskRecorder`** (records the ask clip sent to DashScope STT as `format="m4a"`) — a cross-feature coupling (see Prerequisite).

## Global constraints
- Multi-tenant attribution is by **explicit company+site tag on the recording** (`siteId` → site → company), NEVER by the recorder's role/membership. The admin/test account (Ben_Lin) is the deliberate cross-cutting exception. (This is why G2's `siteId` is the fix, and why pipeline G5b consuming `recordings.site_id` is the durable answer — out of scope here.)
- ALL new dev content ENGLISH (comments/commits). No new Gradle deps / native libs (armeabi 32-bit), no GMS, Android framework only.
- Prod S3 lake `fieldsight-data-509194952652`; prod org gateway `ys94qy2tk0`; company FieldSight `dc2eafa9-1260-4bd9-8d65-862f47dacb3c`; site SB1108 Ellesmere `2f6b0776-02bf-425e-bbc1-994c170f11da`.
- Recording S3 key convention (server-built from `fileName` + caller identity): `users/{display}/{video|audio|pictures}/{YYYY-MM-DD}/{Prefix}_{YYYY-MM-DD}_{HH-MM-SS}.{ext}`.
- **Any prod-facing acceptance step requires explicit user sign-off before it runs against prod.**

## Prerequisite: merge SP-Ask first
SP-Ask is validated end-to-end (device-verified) on branch `feature/sp-ask` and unmerged. Because G5a changes the shared `AudioRecorder`, merge SP-Ask to `main` first (close its read-timeout fix review + merge backend already on develop/test), then do this work on a clean `main` so the shared `AudioRecorder` change is single-branch. Note: with the prod flavor pointing at prod host, SP-Ask **voice** won't have a backend endpoint until the SP-Ask backend is promoted develop→main→prod (the separately-deferred prod-voice decision); the **dev flavor (test host)** remains where SP-Ask voice is exercised. Recording upload correctness (this task) is independent and must go to prod.

## Phase 1 — the five gaps

### G1 — Point release builds at PROD (dev/prod build flavors)
`app/build.gradle.kts`: add `flavorDimensions + productFlavors`:
- `prod` (default/release): `ORG_API_BASE_URL = "https://ys94qy2tk0.execute-api.ap-southeast-2.amazonaws.com/prod/api"`.
- `dev`: `ORG_API_BASE_URL = "https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api"` (test gateway; where SP-Ask voice + safe testing happen).
- Cognito buildConfigFields stay as-is (already prod, shared pool). Keep the debug build type; the shipping build = prod flavor. Confirm `assembleProdDebug`/`assembleDevDebug` both build.

### G2 — Presigned recordings flow with siteId
No code change. Verified already implemented. G1 routes it to prod. (Acceptance confirms a prod recording creates a prod `recordings` row with the selected `siteId`.)

### G3 — NZ-local date in `startedAt`
`UploadWorker.iso8601()`: emit NZ-local offset instead of UTC:
`OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of("Pacific/Auckland")).toString()` → e.g. `2026-07-16T09:50:00+12:00`; server `startedAt[:10]` = `2026-07-16` (NZ). Applies to both `startedAt` and `endedAt`.

### G4 — Dashed filename the pipeline can parse
`MediaStorage`: change the timestamp format `yyyyMMdd_HHmmss` → `yyyy-MM-dd_HH-mm-ss` (keep the device-local timezone — already NZ). Result e.g. `ben_lin_2026-07-16_09-50-00.wav`, satisfying the pipeline BUG-01 regex `\d{4}-\d{2}-\d{2}_(\d{2})-(\d{2})-(\d{2})`. Since G2's presigned server builds the S3 key from `fileName` (= this generated name), fixing `MediaStorage` fixes both the local file and the uploaded key. The `{Prefix}` (user/device token, e.g. `ben_lin`) is unconstrained by the regex.

### G5a — Audio output WAV (16 kHz mono 16-bit), not M4A
Rewrite `AudioRecorder` to produce **WAV 16 kHz mono 16-bit PCM**: capture raw PCM via `AudioRecord` (16000 Hz, MONO, PCM_16BIT), write a standard 44-byte RIFF/WAVE header + PCM to the output file (reuse the PCM→WAV header approach already validated in SP-Ask's `dashscope tts` / a small pure `pcmToWav` helper). `MediaStorage` AUDIO ext `m4a` → `wav`; `UploadWorker.contentTypeFor` audio → `audio/wav`. Because `AskRecorder` (SP-Ask) reuses `AudioRecorder`, its ask clip becomes WAV too — update `AskApiClient`'s `format` from `"m4a"` to `"wav"` (WAV is accepted by qwen3-asr STT; validate on the next SP-Ask device check). Video (MP4) and photos (JPG) already ingest fine — unchanged.

## Phase 2 — Rename to "FieldSight mobile app" (breaking; sequenced LAST, may be its own plan)
Full rename per user (2026-07-16), done AFTER Phase 1 ships and is accepted, because it is disruptive and must be deliberate:
- **GitHub repo** `benzn-tech/GrandTime` → `fieldsight-mobile` (GitHub auto-redirects old URLs); update `git remote`.
- **Docs/CLAUDE.md/README** "GrandTime" → "FieldSight mobile app".
- **App display/launcher name** → "FieldSight" (if not already — the UI reskin already shows FieldSight branding; confirm the launcher label).
- **applicationId** `com.benzn.grandtime` → `com.benzn.fieldsight` (BREAKING: a new install identity — the F2SP device gets a *separate* app; the old install's app-private state, login tokens, DataStore, Room DB are NOT carried over; shared-storage media under `/sdcard/FieldSight/` survives). Requires reinstall + re-login on the device; plan a clean cutover. Whether to also rename the Kotlin source namespace `com.benzn.grandtime` (large mechanical refactor) vs only the `applicationId` is a plan-time decision — recommend applicationId + namespace together for a clean result, but it is a big diff.
- **Local folder** `C:/Users/camil/Dropbox/GrandTime` rename is NOT done inside an active session (breaks in-flight paths) — do it as the very last step or a fresh session.

## Testing / acceptance
- **Unit (JVM):** `iso8601()` yields NZ-local date across a UTC-midnight boundary; `MediaStorage` filename matches `^.+_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.\w+$`; `pcmToWav` header fields (RIFF/WAVE/fmt/data, 16000/mono/16-bit); `AskApiClient` sends `format="wav"`.
- **Device (dev flavor, no prod risk):** record an audio clip → pull it → confirm `.wav` 16 kHz mono, dashed filename, lands in the TEST lake under the NZ-date folder with the selected `siteId` on the `recordings` row, and the pipeline transcribes it (proves format+name are consumed). Re-check SP-Ask voice still works (ask clip now WAV).
- **Prod (ONLY after user sign-off):** switch to prod flavor, record one real clip with Ellesmere selected → confirm it lands in the PROD lake attributed to Ellesmere.

## Out of scope
- G5b (pipeline consumes `recordings.site_id`) — separate fieldsight-pipeline task/session.
- The orchestrator RealPTT timezone fix (already shipped, pipeline PR #70) and the one-off migration of today's 5 files (already done).
- Local folder rename during an active session.
