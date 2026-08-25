# C1 — VizField flavour, package/label, and `session_type=meeting` entry

**Batch:** VizField pivot Line C, batch C1 (PLAYBOOK §4 Line C; frozen decision F4).
**Repo decision:** implemented as new flavours in the existing GrandTime repo. Migration to
`benzn-tech/vizfield-android` is a separate, command-approved step; nothing here blocks it.

## Scope

1. **Two new product flavours**, same `env` dimension as `prod`/`dev`:

   | flavour | applicationId | label | ORG_API_BASE_URL | site voice | QR env |
   |---|---|---|---|---|---|
   | `vizfield` | `com.benzn.vizfield` | **VizField** | `https://api.vizfield.com/api` | disabled | `vizfield` |
   | `vizfieldDev` | `com.benzn.vizfield.dev` | **devvizfield** | `https://api-test.vizfield.com/api` | disabled | `vizfield-test` |

   - The gateway is compile-time burned (it is a flavour, not config) — unchanged principle.
   - `api.vizfield.com` is the frozen production gateway domain (PLAYBOOK F4). The backend is
     not live yet (waits on A2.5), so end-to-end upload verification is deferred; the network
     completion standard for C1 is **structural flavour isolation**: the vizfield flavours must
     be shown — by test and by built-APK inspection — to contain no FieldSight gateway or
     bucket reference.
   - `api-test.vizfield.com` is a reserved test hostname on the same zone (does not resolve
     yet; A2.5 binds it). A dev build can therefore never reach any production bucket even by
     accident. Reported to command as a cross-line contract note.
   - Site voice stays off for vizfield flavours (`SITE_VOICE_ENABLED=false`, empty WS URL):
     the FieldSight WS gateways are FieldSight resources and out of bounds; VizField has no WS
     backend yet.
   - Cognito stays the shared pool for now **deliberately**: F2 mandates a new pool in the
     VizField account, which does not exist until A2.5. Tokens from the old pool are useless at
     the vizfield gateway, and Cognito is neither a bucket nor a gateway, so isolation of the
     data path holds. Swap the four `COGNITO_*` fields per-flavour when A2.5 lands (TODO in
     build file).

2. **`session_type=meeting` entry** — one big button + one metadata field (F4 wording):
   - Home screen Meeting card gets a **Start meeting** button (visible when signed in and
     capture is idle). Pressing it starts a normal audio session whose metadata carries
     `sessionType: "meeting"`.
   - The intent travels like `groupId` does: an `AppState` pending value read once at
     `fireSessionOpen`. It is timestamped and expires after 10 s so a lost dispatch (service
     not running) can never mislabel a later hardware-key recording as a meeting.
   - The field rides on **both** channels, exactly like `groupId` and for the same reason
     (`/open` is fire-and-forget; the upload is the one call guaranteed to arrive):
     - `POST /org/sessions/{sid}/open` body: `sessionType` (omitted when absent — the
       non-meeting request stays byte-identical to today's).
     - `POST /org/recordings/upload-url` body: `sessionType` (same omission rule).
   - Persisted per row (`capture_records.session_type`, Room v5→v6 additive migration) so an
     upload retried days later still carries it.
   - JSON key is camelCase `sessionType`, matching every existing body field (`siteId`,
     `groupId`, `startedAt`); the backend column is `session_type`. Flagged to command as the
     one-line cross-line contract for A2.5's ingest.

3. **Out of scope** (unchanged, per F4): all other UI; recorder-screen polish (C2, gated on
   the behavior gate); any keymap/hardware-key change; backend anything.

## Definition of done

- Reverting the change makes the new tests red:
  - `FlavourIsolationTest` — per-variant BuildConfig assertions (vizfield hosts are
    `*.vizfield.com`, no FieldSight gateway id in any BuildConfig URL; fieldsight flavours
    still point where they pointed).
  - `SessionsApiClientTest` / `RecordingsApiClientTest` additions — `sessionType` sent when
    set, absent otherwise.
  - `MeetingStartTest` — claim/expiry semantics of the pending intent.
- `assembleVizfieldRelease` + `assembleProdRelease` both build; unit tests pass for both
  variants.
- Built vizfield APK inspected: label **VizField**, package `com.benzn.vizfield`, dex/res
  contain `api.vizfield.com` and none of the FieldSight gateway ids
  (`ys94qy2tk0`, `wdsgobb7b0`, `ouv5cmq6si`, `i1r3tuv9bh`).
- On-device: vizfield build installed, Home screen with the Start meeting button
  screenshot-measured on the 320×427 dp F2SP screen, then **uninstalled** (the ROM key
  broadcast reaches every installed app — two record-capable apps on one device is the known
  double-recording landmine).
