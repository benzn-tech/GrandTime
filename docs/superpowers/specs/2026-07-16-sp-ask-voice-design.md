# SP-Ask Design: Hands-free Voice Q&A over FieldSight RAG

**Date:** 2026-07-16
**Status:** design approved (verbal), pending written review → writing-plans
**Repos (cross-repo):** GrandTime (`C:/Users/camil/Dropbox/GrandTime`, mobile) + fieldsight-pipeline (`C:/Users/camil/Dropbox/fieldsight-pipeline`, backend)
**Prereqs shipped:** FieldSight RAG `/ask` (ACL-scoped, Claude Haiku synthesis) live on the `fieldsight-test` stack; GrandTime Cognito auth + recordings upload pattern.

## 1. Goal

A field worker on an F2SP bodycam holds a dedicated push-to-talk key, speaks a question about FieldSight site data, and hears a short spoken answer back — **fully hands-free, no screen**. Speech-to-text, retrieval-augmented answer, and text-to-speech all run server-side; the device only records and plays audio.

## 2. Locked decisions (from brainstorming 2026-07-16)

1. **Trigger:** the dedicated, currently-unregistered PTT physical key (`lolaage.ptt.*` broadcast, deliberately ignored during "去对讲化"). Press-and-hold-to-talk via raw down/up. Does not steal any of the 4 existing keys.
2. **Mic exclusivity:** ASK is **disabled while a video recording is active** (folds into the existing video/audio mutual-exclusion in the capture state machine). Holding ASK during video recording plays a "busy" tone and does nothing. Rationale: zero mic-contention risk on MediaTek hardware for v1.
3. **Providers:** STT = DashScope **Qwen-ASR-Realtime** (low-latency realtime ASR); RAG synthesis = **Claude Haiku 4.5** (existing `/ask`); TTS = DashScope **CosyVoice / Qwen-Audio-TTS** series. (Chosen over AWS-native; DashScope already integrated for embeddings, same urllib3 pattern, single vendor.)
4. **Output:** voice only — **no on-screen answer text**, no answer overlay.
5. **Process feedback:** **audio cues only, zero screen UI** — beep on start-listening, a short "thinking" tone, spoken answer, spoken/toned errors. Walkie-talkie style, eyes-free.
6. **Voice prompt:** a separate, shorter, TTS-friendly system prompt (`RAG_SYSTEM_CONTEXT_VOICE`) selected via a `mode` request field — same retrieval, punchier synthesis than the screen/web Ask.
7. **ACL:** the mobile sends the same Cognito `idToken`; company/site scope is re-derived server-side from `caller_sub` exactly like the existing `/ask` — **zero new ACL code**.
8. **Orchestration:** **single synchronous call** — one Lambda chains STT → RAG → TTS and returns the answer audio. (Fits the APIGW 29s ceiling for short clips + short answers on Haiku; escalate to async job+poll only if testing shows timeouts.)

**Controller defaults (approved, overridable):** recording cap **~15s**; audit = **one row per ask** in a small table; **error cues bundled in the APK** (not downloaded).

## 3. Architecture

```
[F2SP device]  hold PTT ─beep─ record AAC ─release─ thinking-tone
   │  POST /org/ask/voice  { audio: base64, mode: "voice" }  + Authorization: <idToken>
   ▼
[fieldsight-test backend]  one Lambda:
   1. base64-decode audio
   2. DashScope Qwen-ASR-Realtime (STT)      → question text
   3. existing RAG /ask path (caller_sub → ACL scope, mode="voice" → short prompt, Claude Haiku)
                                             → citation-free short answer text
   4. DashScope CosyVoice (TTS)              → answer audio bytes
   5. audit row (caller, company, transcript, answer, ts)
   ▼  return { audioBase64, answerText, transcript }
[F2SP device]  play answer audio (MediaPlayer)
```

Short clip (a few seconds AAC ≈ tens of KB) and short answer audio are both well under the APIGW/Lambda synchronous payload limits, so **audio is carried inline (base64) in the request/response** — no presigned-S3 round-trip, lower latency. (If a future longer-form mode is added, switch to presigned upload.)

## 4. Mobile components (GrandTime — new `ask/` package)

- **`PttKeySource`** — registers the raw `lolaage.ptt.down` / `lolaage.ptt.up` broadcasts and emits discrete PTT-down / PTT-up events. Needed because `PressTypeDetector` only classifies SHORT/LONG at 1000ms and does not expose hold-until-release; SP-Ask consumes raw down/up instead.
- **`AskManager`** (in `CoreService`, sibling to `CaptureManager`) — orchestrates one ask:
  - PTT-down: if `CaptureState` is **not** `RecordingVideo` → play listening beep, start `AskRecorder`. If video is recording → play busy tone, no-op.
  - PTT-up: stop recorder, play thinking tone, call `AskApiClient` (foreground/synchronous), then `AskPlayer.play(answerAudio)`. On any error → error cue.
  - Enforces the recording cap (~15s auto-stop).
- **`AskRecorder`** — reuses the existing `AudioRecorder` (MediaRecorder, AAC/M4A one-shot) writing to a temp file. Reused, not rebuilt.
- **`AskSounds`** — plays the three short cues (listening / thinking / error) from tiny assets bundled in the APK (no download). Distinct from `CaptureSounds` (which only plays fixed system tones).
- **`AskPlayer`** — a new `MediaPlayer`-based one-shot player for the returned TTS audio (the app has no general audio player today).
- **`AskApiClient`** — mirrors `RecordingsApiClient`'s `HttpFns` DI (real OkHttp + injectable fake). One `POST /org/ask/voice` with `{ audio, mode }` + idToken; parses `{ audioBase64, answerText, transcript }`. Direct synchronous call, NOT the WorkManager upload queue.
- **Keymap:** `ASK_AGENT` is already a first-class `KeyAction` (label + stub); bind it to the PTT key by default (and it remains user-rebindable via the existing Key Bindings screen). Replace the "coming soon" stub in `CoreService.handleAction` with `AskManager`.
- **Foreground service:** existing `CoreService` FGS type `camera|microphone` + `RECORD_AUDIO` already cover mic-only capture — no manifest change.

## 5. Backend components (fieldsight-pipeline — develop → fieldsight-test)

- **New endpoint `POST /org/ask/voice`** — accepts `{ audio: base64, mode }`; caller identity from the Cognito authorizer claims (`sub`), never client-supplied.
- **`dashscope_utils` additions** — `stt(audio_bytes) -> str` (Qwen-ASR-Realtime) and `tts(text) -> bytes` (CosyVoice), each following the existing `embed()` urllib3 + `DASHSCOPE_API_KEY` pattern.
- **Voice prompt** — add module constant `RAG_SYSTEM_CONTEXT_VOICE` in `lambda_ask_agent.py` (short, no markdown, no `[n]` citation markers, spoken-sentence style); thread a `mode` field through the ask request → `build_rag_prompt` / `_rag_answer` to select it. The screen/web prompt (`RAG_SYSTEM_CONTEXT`) is unchanged; `mode` defaults to the existing screen behavior when absent (backward compatible).
- **Answer chaining** — the voice endpoint reuses the existing RAG retrieval + synthesis path (embed → RagSearch (ACL) → Claude Haiku) with `mode="voice"`, then TTS-synthesizes the returned `answer` (citation-free). Citations are dropped for voice (not spoken).
- **ACL** — `caller_sub` → existing `RagSearchFunction` company/site scoping. No new ACL logic.
- **Audit** — new additive migration for a `voice_ask_log` table (id, company_id, caller_sub, transcript, answer, created_at); insert one row per ask. Lightweight, for traceability.
- **Timeouts** — the voice prompt enforces brevity; Haiku is fast; the whole chain targets well under 29s. If integration testing shows spikes near the ceiling, escalate to async job+poll (out of scope for v1).
- **Deploy** — merge `develop` → CI auto-deploys `fieldsight-test` + applies the audit migration. (RAG+ACL path only exists on `fieldsight-test`, which is the stack the mobile app connects to.)

## 6. Data flow (one ask, happy path)

1. User holds PTT (device idle/standing-by) → listening beep → `AskRecorder` records AAC to temp file.
2. User releases (or 15s cap) → recorder stops → thinking tone → `AskApiClient` POSTs base64 clip + `mode="voice"` + idToken.
3. Backend: decode → Qwen-ASR-Realtime → question text → RAG (voice prompt, caller_sub ACL, Haiku) → short answer text → CosyVoice → answer audio → audit row → respond.
4. Device receives `audioBase64` → `AskPlayer` plays it. Done.

## 7. Error handling (all-audio, no screen)

| Condition | Behavior |
|---|---|
| No network / request timeout (device-side) | Error cue from bundled APK asset (does not depend on server) |
| ASK held during active video recording | Busy tone, no recording started |
| Empty transcript (STT caught nothing) | Server TTS's a short "Didn't catch that" (or device error cue if STT stage failed) |
| No retrieval hits | RAG's existing "no relevant records" answer, spoken |
| Server 5xx / TTS failure | Device error cue |

Device-side error cues must be **pre-bundled audio/tones** (offline can't reach server-side TTS). Server-reachable errors may be spoken by TTS for clarity.

## 8. Testing

**Mobile — JVM-testable (TDD):** `AskApiClient` request-building + response parsing; `AskManager` state logic (refuses when `RecordingVideo`; cap auto-stop; error → error-cue path); PTT raw down/up → event mapping. **Device-verified:** actual PTT key press-and-hold, mic capture, TTS playback, the three audio cues, mic exclusivity with an active video recording, end-to-end latency within 29s.

**Backend — TDD:** `mode` → voice-vs-screen prompt selection in `build_rag_prompt`; `/org/ask/voice` request/response shape; `stt`/`tts` DashScope wrappers with a mocked HTTP client; ACL passthrough (caller_sub scoping unchanged); audit-row insertion. **Integration (CI / fieldsight-test):** real DashScope STT+TTS round-trip; end-to-end voice ask honoring ACL.

## 9. Delivery boundary & sequencing

Single spec, single plan. The plan sequences **backend first** (the `/org/ask/voice` endpoint is independently verifiable via `curl` with a base64 clip before any mobile work), then mobile (PTT source → AskManager → record → upload → play → cues → keymap binding). Cross-repo deploy: backend via `develop` → `fieldsight-test`; mobile via GrandTime `main`. Device acceptance covers the full hands-free loop.

## 10. Explicit non-goals (v1)

- No offline/on-device speech (armeabi 32-bit + no GMS; no downloaded voice packs).
- No on-screen answer text, overlay, or citation display.
- No async job+poll orchestration (single sync call unless testing forces it).
- No ASK during active video recording (mic exclusivity; revisit concurrent mic later if needed).
- No presigned-S3 audio transport (inline base64 for short clips; revisit for longer-form).
- No follow-up/multi-turn conversation memory (each ask is independent).

## 11. Open risks / to validate during implementation

- **Latency budget:** confirm STT + Haiku RAG + TTS stays under 29s on a cold Lambda with a ~10–15s clip; if not, escalate to async.
- **NZ/AU accent STT accuracy** with Qwen-ASR-Realtime (mis-transcription → wrong RAG query). Validate on real device audio; the mode/provider is swappable if accuracy is poor.
- **Exact lambda placement** of `/org/ask/voice` (org-API stack vs the fieldsight-api stack that hosts `/ask`) — resolve in the plan with a targeted read of the current ask routing/stack wiring.
- **DashScope realtime API shape** for a complete short clip (vs a live stream) — confirm single-shot usage during backend implementation.
