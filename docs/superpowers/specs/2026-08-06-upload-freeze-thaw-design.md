# Upload freeze & thaw — design

Date: 2026-08-06
Revision: 2 (after adversarial review; see §10 for what changed and why)
Repos: GrandTime (device), fieldsight-pipeline (org-api, ledger, Notion)
Builds on GrandTime#3 (upload backlog resilience) and the device ledger
(`2026-08-03-device-management-design.md`).

## 1. The problem

`UploadWorker.doWork()` writes `markUploadStatus(recordId, "failed")` at nine
points (lines 63, 78, 110, 117, 121, 128, 154, 160, 167). Six of them then
return `Result.retry()`. Two further non-success exits write no status at all
(line 41, missing `recordId`; lines 81–83, a transient token-refresh failure),
so a row can also sit at a status that describes an attempt two attempts ago.

`"failed"` therefore means three different things: *backing off, will return*,
*gone for good*, and *stale*. No failure reason is persisted anywhere —
`CaptureRecord.kt:27` carries `uploadStatus` and nothing else.

Three consequences follow directly:

- **The field user cannot act.** The UI can say "failed" and nothing more. It
  cannot distinguish "no Wi-Fi" (they can fix it) from "tenant mis-mapping"
  (they cannot).
- **The operator cannot see.** Whatever the device reports, it reports one word.
- **The device cannot self-heal.** Without a class there is no remedy to pick.

And one silent data-loss path: a failure only the operator can fix — a 401 on a
token that just refreshed (`UploadWorker.kt:107–111`), a 403 from a mis-scoped
identity, a real 4xx from `complete` — is retried blindly for seven days on
exponential backoff, then marked dead. Nobody is told. Retrying a mis-mapped
identity does not fix a mis-mapped identity; the seven days are pure delay
before silent loss.

## 2. Principles

1. **Classify by who can fix it, not by HTTP status.** Status tables change;
   responsibility does not.
2. **Aurora is the sole source of truth. Notion is a projection plus an intent
   inbox.** A derived document with two writers loses values silently (the
   `programme.json` lesson).
3. **The downlink has exactly one verb: `thaw`.** Its worst-case abuse is a
   device uploading a file it already owns, one more time.
4. **Deployment is the default thaw signal.** Most operator-fixable failures are
   fixed by shipping code; the fix should thaw without anyone remembering to.
5. **One user-visible sentence.** The only physical facts a site worker controls
   are power and Wi-Fi.
6. **Freezing must not become losing.** Time spent frozen is not time spent
   trying, and must not be charged against the give-up budget (§4.5).
7. **A thaw never crosses an account.** These devices rotate between clients
   monthly; a re-enqueue that ignores `authorSub` is how one client's recording
   uploads into another client's tenant.

## 3. Failure classes

| Class | Meaning | Device behaviour | Who is told |
|---|---|---|---|
| `transient` | The world is temporarily unhelpful | WorkManager exponential backoff, unchanged | nobody |
| `site_fixable` | A person at the site can change the physical situation | Slow retry | the one sentence |
| `operator_fixable` | Only a change on our side can make this succeed | **Freeze**: stop retrying, stop the age clock | operator, via ledger |
| `dead` | Cannot ever succeed | Stop, keep the row | operator, via ledger |

### 3.1 Prerequisite: `UploadUrlResult` must carry the status code

`RecordingsApiClient.kt:126` declares `data class Error(val message: String)`,
and the code survives only inside a human string
(`Error("HTTP ${r.code}: ${r.body}")`, line 222). The device **cannot currently
tell a 403 from a 404**, so the table below is unimplementable without a
contract change. Required, and it is a change to a class with existing tests:

```kotlin
data class Error(val code: Int, val message: String) : UploadUrlResult
```

`code = 0` for the malformed-2xx and missing-`recordingId` cases (lines 225,
231), which are genuinely codeless. Call sites: `UploadWorker.kt:120` and
`RecordingsApiClientTest`.

Note also that a total absence of response maps to `Busy(0)`, never `Error`
(line 134). `Error` therefore **always** means a real non-2xx, non-401,
non-transient HTTP response — which under Principle 1 makes it operator
territory by default, not transient.

### 3.2 Classification table

Implemented in a new `upload/UploadFailure.kt` — pure Kotlin so it is
JVM-testable; `UploadWorker` itself is not.

| Observation in `doWork()` | Class | Fingerprint |
|---|---|---|
| `Exception` from network/IO (line 166) | `transient` | — |
| `UploadUrlResult.Busy` (429/5xx/no response) | `transient` | — |
| `isTransient(completeStatus)` | `transient` | — |
| `freshIdToken()` null while `LoginState.LoggedOut` | `site_fixable` | `needs_login` |
| `AuthExpired` **after** `freshIdToken()` returned a token | `operator_fixable` | `uploadurl_401` |
| `Error(403, …)` from `upload-url` | `operator_fixable` | `uploadurl_403` |
| `Error(code, …)` from `upload-url`, any other real code | `operator_fixable` | `uploadurl_<code>` |
| `Error(0, …)` — malformed 2xx / missing `recordingId` | `operator_fixable` | `uploadurl_malformed` |
| `complete` 401 | `operator_fixable` | `complete_401` |
| `complete` 403 | `operator_fixable` | `complete_403` |
| `complete` other 4xx | `operator_fixable` | `complete_<code>` |
| Local file absent (line 126) | `dead` | `file_missing` |
| Effective age exceeded, class was `transient`/`site_fixable` | `dead` | `aged_out` |

The `AuthExpired` row is the one that changes behaviour most. Today it retries;
`freshIdToken()` succeeded immediately above, so the token is good and the
*server* rejected the identity. That is never fixed by waiting.

`complete` 401/403 are called out explicitly because a `complete` 403 is exactly
the mis-scoped-identity case §1 opens with — the case this design exists for.

## 4. Device changes (GrandTime)

### 4.1 Honest state

Room migration on `capture_records`:

- `uploadStatus` value set becomes `pending | uploading | retrying | frozen |
  dead | uploaded`. Retry paths write `retrying`, not `failed`. Existing
  `"failed"` rows migrate to `retrying`.
- `failureClass: String?` — one of the four classes, null when none.
- `failureCode: String?` — the fingerprint.
- `lastAttemptAt: Long?`
- `frozenAtBuild: String?` — server build observed when the freeze was set (§4.4).
- `frozenSinceMs: Long?` — when the current freeze began.
- `frozenCreditMs: Long` (default 0) — accumulated time spent frozen (§4.5).

### 4.2 Status-consumer audit (mandatory, same phase as the rename)

Renaming a status value is not a local change. Every string-matching consumer
must be updated in the same commit, or counts silently drop to zero:

| Consumer | Current | Required |
|---|---|---|
| `UploadSummary.kt:16–24` | `when` over `uploaded`/`uploading`,`pending`/`failed`, with an explicit `// unknown status values are ignored` fallthrough | `retrying` → in-progress; `frozen`, `dead` → a new `stuck` bucket. The silent fallthrough must become exhaustive or log. |
| `CaptureRecordDao.kt:69` (orphan/unattributed count) | `IN ('pending','failed','uploading')` | add `'retrying','frozen'` |
| `CaptureRecordDao.kt:76` (sign-out warning) | `IN ('pending','failed','uploading')` | add `'retrying','frozen'` |
| `CaptureRecordDao.kt:61` (`listPendingForAuthor`) | caller-supplied status list | audit every call site |
| `CaptureRecordDao.kt:86` (boot rescan) | caller-supplied status list | must **exclude** `frozen` and `dead` |

That `// unknown status values are ignored` comment is precisely how a
"harmless rename" would delete the sign-out data-loss warning without a single
failing test.

### 4.3 `dead` versus `missing`

`markMissing` sets `missing = 1` (`UploadWorker.kt:129`), and **every** observe
and count query filters `missing = 0` (`CaptureRecordDao.kt:17,22,62,68,75,86,
89,92`). So a `file_missing` row is invisible locally by design — the file is
gone, there is nothing to show or retry.

Resolution: `missing` stays exactly as it is and keeps its local invisibility.
`dead` is the *upload* verdict and is orthogonal. **The probe (§4.6) counts dead
rows from a query that does not filter on `missing`**, so a vanished file is
still reported to the ledger once, even though the device shows nothing. §4.7's
"already visible where they belong" therefore applies to `frozen`, not to
`file_missing`.

### 4.4 Server build provenance

`serverBuild` is learned only from probe responses; a freeze happens on the
upload path, which carries no build. So:

- On every probe response, persist `serverBuild` to `SettingsStore` as
  `lastKnownServerBuild`.
- A freeze stamps `frozenAtBuild = lastKnownServerBuild` (may be null before the
  first probe).
- **If `frozenAtBuild` is null, the build-mismatch rule does not fire.** Instead
  the next probe adopts its `serverBuild` into `frozenAtBuild` *without*
  thawing.

Without that null rule, `frozenAtBuild != serverBuild` is true on every probe
for a device that froze before its first probe, producing a
thaw → refail → refreeze loop at probe cadence, forever.

### 4.5 The age clock, and why freezing must credit it

Today the give-up check runs at the top of `doWork()` against `startedAt`
(`UploadWorker.kt:62`). Exempting frozen rows is not enough: §4.8 clears
`failureClass` on thaw, so a record frozen on day 2 and thawed on day 9 arrives
at the age check with a null class and dies **without a single retry** — the
mechanism producing exactly the loss it exists to prevent.

The rule is therefore a credit, not an exemption:

```
effectiveAge = now - startedAt - frozenCreditMs - (frozen ? now - frozenSinceMs : 0)
```

`frozenCreditMs += now - frozenSinceMs` on thaw. Give up when
`effectiveAge > GIVE_UP_AFTER_MS`. A thawed record is thereby guaranteed the
budget it never got to spend.

Additionally, a thawed record gets **one guaranteed attempt** regardless: the
age check is skipped when `frozenCreditMs > 0 && lastAttemptAt < thawedAt`.
Belt and braces, because this is the failure mode that silently destroys data.

### 4.6 The status probe — the only new contract

`POST /org/device/status`, authenticated, carrying the existing device headers.
Vitals up, thaw decisions down, one round trip.

Request:

```json
{ "oldestPendingAgeS": 93600, "pending": 12, "frozen": 3, "dead": 1,
  "fingerprints": ["uploadurl_401", "complete_403"] }
```

Response:

```json
{ "serverBuild": "9495bcd", "thaw": ["uploadurl_401"] }
```

Cadence: every 6h while `frozen > 0 || pending > 0`, otherwise once a day.
`NetworkType.CONNECTED`. Twenty devices at four calls a day is 80 requests —
irrelevant against org-api's reserved concurrency of 200.

Best-effort by construction: a probe failure never touches an upload, never
changes a record, never surfaces.

**Known limit:** the probe is Cognito-authenticated, so a device in
`site_fixable (needs_login)` — or sitting in stock between clients — reports
nothing at all. `backlog_stuck` cannot fire for exactly the device most likely
to be in trouble. The ledger's existing `quiet` heading is the only cover for
that case, and it is a coarse one. Accepted, not solved.

### 4.7 Thaw rule

Thaw a frozen record when **both** of these hold:

1. `record.authorSub == the currently signed-in sub`. Frozen rows belonging to
   another author stay frozen, always. `CaptureRecordDao.kt:41–64` exists
   because unscoped re-enqueue attributes one client's recordings to another;
   a `uploadurl_403` is *most likely* an identity mis-scoping, so thawing it
   under a new account is the highest-probability path back into the
   cross-tenant upload incident already on record.
2. Either `frozenAtBuild` is non-null and differs from `response.serverBuild`,
   or `failureCode ∈ response.thaw`.

On thaw: add the frozen span to `frozenCreditMs`, clear
`failureClass`/`failureCode`/`frozenAtBuild`/`frozenSinceMs`, set status
`pending`, re-enqueue with `replace = true` (the `KEEP` trap — a work request in
backoff counts as unfinished and `KEEP` discards the new one,
`UploadEnqueuer.kt:28–33,64–67`).

If it fails the same way again it re-freezes with a fresh `frozenAtBuild`. The
loop is self-correcting and bounded by deploy frequency.

**Deploy-thaw cost, stated plainly:** `serverBuild` is the repo git sha and this
repo deploys often. Every deploy thaws every eligible frozen record on every
device — N frozen records means N `upload-url` calls, once, spread over the
2-concurrency cap. At the fleet's scale (20 devices, tens of records) that is
noise. If deploy frequency or fleet size grows, scope the signal to org-api's
own artifact hash rather than the repo sha.

### 4.8 The one sentence

Shown when any record is `site_fixable`, or `oldestPendingAgeS > 12h`:

> Tonight: charger + Wi-Fi.

`frozen` and `dead` records show nothing to the field user — there is no action
they can take, and showing them trains people to ignore the surface. They are
counted in `UploadSummary`'s new `stuck` bucket and in the sign-out warning
(§4.2), and reported to the ledger by the probe.

The Retry button stays, keeps `replace = true`, and is understood as
reassurance rather than mechanism. Recovery must not depend on a person
pressing anything.

## 5. Backend changes (fieldsight-pipeline)

### 5.1 Schema

Migration `0033_device_upload_freezes.sql`:

```sql
create table if not exists device_upload_freezes (
  device_id         uuid not null references devices(id),
  fingerprint       text not null,
  first_seen_at     timestamptz not null default now(),
  last_seen_at      timestamptz not null default now(),
  record_count      integer not null default 0,
  observed_build    text,
  first_notified_at timestamptz,
  thaw_requested_at timestamptz,
  thaw_requested_by text,
  thawed_at         timestamptz,
  primary key (device_id, fingerprint)
);

alter table devices add column if not exists backlog_oldest_age_s bigint;
alter table devices add column if not exists backlog_pending int;
alter table devices add column if not exists backlog_reported_at timestamptz;
```

The three `devices` columns ship in **Phase 1** (the probe records vitals from
day one); the freeze table ships in Phase 2. One migration file, applied at
Phase 1 — the table is simply unused until Phase 2.

Written only by the status endpoint, upserting under the same discipline as
`device_heartbeat` (`device_heartbeat.py:19–31`): conditional, so a repeat
report with unchanged vitals writes no row. Nothing here may raise into a
user's request.

`serverBuild` is the deployed git sha, injected as an org-api environment
variable by the CFN template at deploy time.

Route addition is regex dispatch inside `lambda_org_api` — no CFN route
resource, and an Aurora-only endpoint needs no new runtime-role IAM (the
IAM trap is S3-prefix-specific). Migration 0033 rides the existing migration
path; the deploy role needs nothing new.

### 5.2 Thaw emission — delivery by disappearance, not by fire-and-forget

Marking `thawed_at` at emission time drops the instruction whenever the response
is lost in transit — the same client-gives-up-after-the-server-committed
asymmetry that produced 69 orphaned S3 objects on 2026-08-03. The operator's
action would evaporate, the register would report it resolved, and recovery
would depend on an unrelated deploy.

Contract instead:

- Emit `fingerprint` in `thaw[]` on **every** probe while `thaw_requested_at is
  not null and thawed_at is null` **and** the device still reports that
  fingerprint.
- Set `thawed_at = now()` on the first probe where the device **stops** reporting
  it. Disappearance is the acknowledgement.

Worst case is a duplicate re-upload attempt, which Principle 3 already declares
acceptable. It is idempotent, self-healing, and it removes the "fresh freeze vs.
probe raced the thaw" ambiguity entirely.

### 5.3 Staleness

The same mechanism gives expiry for free: a fingerprint not reported by its
device for **14 days** drops out of the frozen view and the Notion projection.
Without this, an app reinstall or a retired device leaves rows with null
`thawed_at` forever, and `/org/devices/frozen` accumulates standing garbage that
nobody can clear.

The rows are kept, not deleted — the history is the only record that the freeze
happened.

### 5.4 Operator surface A — org-api endpoints

- `GET /org/devices/frozen` — clusters by fingerprint across devices: device
  count, record count, oldest freeze, observed builds, per-device asset tags.
  Excludes stale (§5.3) and thawed rows.
- `POST /org/devices/thaw` — `{ "fingerprint": "...", "deviceIds": [...] }`,
  `deviceIds` optional (omit = every device holding that fingerprint). Sets
  `thaw_requested_at`/`thaw_requested_by`. Idempotent.

Both `platform_admin` only, on the existing Cognito path. No new authentication
route, no shared secret, no unauthenticated endpoint — the three identity-
boundary incidents already on record (empty-list-means-no-filter, legacy gateway
403, cross-tenant upload leak) are reason enough not to open a fourth door.

> **`POST /org/devices/thaw` is a write endpoint.** `platform_admin`
> cross-company reach applies automatically only on graded *read* paths; every
> write endpoint has had to be taught span-all separately (Team/sites, task
> edit, project edit). A test asserts a `platform_admin` from company A can thaw
> a device belonging to company B.

**Capability limit, deliberate:** `thaw` is the only verb this channel will
carry in v1. No delete, no configuration push, no forced upload, no log pull.
This bounds the worst case: if the channel is ever misused, the outcome is a
device re-sending a file it already holds. Extra verbs are a separate decision
with a separate threat model, not an extension.

### 5.5 Operator surface B — Notion (Phase 3)

`lambda_device_report` already writes Notion by property and already has
`notion_client.list_rows`. The two-hop split holds: `lambda_device_report` is
non-VPC and reaches Notion; `lambda_device_ledger` is in-VPC and reaches Aurora;
the former invokes the latter (BUG-36). `lambda_device_ledger` gains an `action`
parameter (`"read"` default, `"thaw"`) and remains a zero-outbound leaf.

Per device row:

- **Projection properties** (system-written every run, never hand-edited):
  `Backlog age` (hours) and `Frozen` (fingerprints, comma-joined; empty when
  none).
- **Intent property** (hand-filled): `Thaw` — a checkbox. The system writes it
  only to clear it.

Each scheduled run, after the projection write:

1. `list_rows` → rows with `Thaw` checked.
2. **If that device has no currently-frozen fingerprints, leave the checkbox set
   and say so in the push.** An anticipatory tick must not be consumed by an
   empty thaw that "succeeds"; the freeze it was meant for may arrive an hour
   later.
3. Otherwise invoke `lambda_device_ledger` with `action = "thaw"` and that
   device's frozen fingerprints.
4. On success, `update_row` clearing the checkbox.
5. On failure, **leave the checkbox set** and include the failure in the push.
   A cleared checkbox must mean the thaw was recorded in Aurora — never that the
   attempt was made.

The checkbox is an *intent*, consumed and cleared; it is never state. State is
`device_upload_freezes`. That is what keeps Notion from becoming a second
writer.

> **Prerequisite bug, must be fixed before Phase 3 ships.**
> `lambda_device_report.py:60–71` passes the JSON-decoded ledger payload into
> `device_alerts.derive`, where `last_seen_at` is an ISO **string**
> (`lambda_device_ledger.py:66`) but `device_alerts.py:62` tests
> `isinstance(seen_at, dt.datetime)`. Across the real Lambda boundary
> `seen_date` is therefore always `None` — activation and quiet logic run on
> `None` in production while the unit tests, which pass datetimes, stay green.
> Phase 3 builds directly on this path. Fix the conversion and add a test that
> crosses the JSON boundary.

> **Reviewer dissent, recorded.** The adversarial review recommended deferring
> Surface B indefinitely: one operator and twenty devices means Surface A plus
> deploy-thaw covers nearly every real case, and the checkbox loop is the
> largest source of race machinery in the design for a marginal convenience.
> It is kept because a phone-side affordance was an explicit requirement, and
> because rules 2 and 5 above remove the two races that motivated the
> objection. It stays in Phase 3 so the register runs for a month first; if the
> `/frozen` endpoint turns out to be sufficient in practice, cut it then.

### 5.6 Alerting

`device_alerts` / `device_notify` gain two headings, placed above the existing
four by actionability:

- `backlog_stuck` — `backlog_oldest_age_s > 24h`. **Age, not count.** Count is
  diluted by new recordings and jumps around; age rises monotonically, is
  comparable across devices, and answers "is this device healthy" with one
  number. A device that has not reached Wi-Fi in a day is abnormal.
- `frozen_new` — a fingerprint whose `first_notified_at` is null. Pushed once,
  then stamped. A freeze already known and awaiting a fix does not need
  repeating; repetition is how a channel gets ignored (`device_notify`'s own
  docstring argues this).

The standing state lives in the Notion table, as that docstring prescribes.

### 5.7 Who operates it

The interactive loop is the operator's own Claude Code session, authenticating
with their own `platform_admin` credentials: pull `/org/devices/frozen`, cluster
by fingerprint, correlate with CloudWatch and Aurora, fix what is mechanical,
call `/org/devices/thaw`. No new credential, no new reach — exactly what a human
operator has.

An **unattended scheduled** agent is explicitly out of scope. It would need a
machine identity holding `platform_admin`, which is a secret-management and
blast-radius decision of its own, not a paragraph in this spec. Surface A is
built to make that decision possible later; this spec does not take it.

Nothing about this runs a model on the device. The device holds a fixed
classification table and obeys a one-verb downlink; non-deterministic behaviour
there would be unreproducible, expensive to debug on real hardware, and
impossible to verify on the bench.

## 6. Error handling

- The status probe never fails an upload and never raises into the UI.
- The freeze register never raises into a user's org-api request (the heartbeat
  rule).
- `thaw` is idempotent at every layer.
- Every degraded path reports. A cleared Notion checkbox, an emitted `thaw[]`,
  and a `dead` record each have exactly one meaning, and none of them is
  produced by a failure. Silent success and silent failure must not look alike.

## 7. Testing

Kotlin (JVM):
- `UploadFailureTest` — one case per row of §3.2, asserting class and
  fingerprint. Includes both 401 cases (with and without a fresh token), which
  are the pair the current code conflates, and both `complete` 401/403.
- `RecordingsApiClientTest` — `Error` carries the code; `code = 0` for malformed
  2xx and missing `recordingId`; absent response is still `Busy(0)`.
- Age-credit function — frozen span credited; a record frozen day 2 and thawed
  day 9 gets an attempt (the B1 regression); the one-guaranteed-attempt rule.
- Thaw decision — author mismatch never thaws (the B2 regression); null
  `frozenAtBuild` adopts without thawing; build change alone; explicit list
  alone; both; neither.
- Room migration — `"failed"` rows land on `retrying`.
- Status consumers — `UploadSummary` buckets `frozen`/`dead`/`retrying`; DAO
  counts include them; boot rescan excludes `frozen`/`dead`.

Python:
- Status endpoint: conditional upsert writes nothing on an unchanged repeat;
  vitals update; freeze rows created and counted.
- `thaw[]` re-emitted while the fingerprint is still reported; `thawed_at`
  stamped only on disappearance (the B3 regression).
- Staleness: a fingerprint unreported for 14 days leaves the frozen view.
- `platform_admin` from another company can call `POST /org/devices/thaw`
  (the span-all regression test).
- `device_alerts.derive` over a **JSON-round-tripped** ledger payload — the
  boundary the current tests skip.
- Notion loop: checkbox left set when nothing is frozen; left set on failure;
  cleared only after Aurora is written; projection does not overwrite
  hand-filled columns.
- SQL runs against the real database (`fieldsight_test`), not a stub.

On-device (real F2SP, dev flavor against fieldsight-test, built from a clean
worktree at `origin/main`):
- Force a 403 from `upload-url` → record freezes, no retry storm, age clock
  credited, `UploadSummary` shows it stuck, the one sentence does **not** appear.
- Sign out, sign in as another account → the frozen record stays frozen.
- Redeploy org-api with no other change → a frozen record thaws by build
  mismatch alone.
- Tick the Notion `Thaw` checkbox (Phase 3) → next run clears it → the record
  uploads within one probe interval.

## 8. Rollout

**Phase 1 — device honesty, inert backend.** `UploadUrlResult` code, Room
migration, classification table, freeze/thaw states, age credit, status-consumer
audit, probe uplink. Migration 0033 applied (only the `devices` columns are
read). Server records vitals and always returns an empty `thaw[]`.

**Phase 2 — register and surface A.** Freeze-row writes, thaw emission by
disappearance, staleness, the two `platform_admin` endpoints, span-all test.

**Phase 3 — surface B and alerts.** The `device_alerts` boundary fix first, then
Notion projection properties, the `Thaw` checkbox loop, `backlog_stuck` and
`frozen_new`.

Each phase is independently shippable. Phase 1 alone ends the silent seven-day
loss and starts real backlog telemetry — provided the age credit (§4.5) and the
consumer audit (§4.2) ship with it, since both are Phase-1 device changes.

## 9. Out of scope

- Any model or agent running on the device.
- Unattended scheduled operation (§5.7) — needs a machine-credential decision.
- General remote control (extra downlink verbs).
- Any authentication path that is not the existing Cognito `platform_admin`.
- Automatic deletion of dead records' files. They stay until a human decides.

## 10. What revision 2 changed

Adversarial review found two design holes that would have made the mechanism
destroy the data it exists to protect, plus a contract change the spec depended
on without naming:

- **§3.1** — `UploadUrlResult.Error` carries no status code today, so the
  classification table was unimplementable. Named as a prerequisite.
- **§3.2** — removed a row for a branch that cannot occur (`Error` with no
  response is `Busy(0)`); added `complete` 401/403; made `Error` operator
  territory by default rather than transient.
- **§4.2** — added the status-consumer audit. `UploadSummary` ignores unknown
  statuses by design, and two DAO queries hardcode the status list; the rename
  would have silently disabled the sign-out data-loss warning.
- **§4.3** — resolved `dead` versus `missing`.
- **§4.4** — defined `frozenAtBuild` provenance and the null case, closing a
  thaw/refail/refreeze loop.
- **§4.5** — replaced "the clock stops" with an accumulated credit. The
  exemption version killed any record whose freeze outlived the seven days,
  without a single retry.
- **§4.7** — thaw is now scoped to the signed-in author. Unscoped, a thawed
  `uploadurl_403` after a device rotates clients is a direct path back into the
  cross-tenant upload incident.
- **§5.2** — thaw is re-emitted until the fingerprint disappears, rather than
  once at emission. A lost response no longer discards the operator's action.
- **§5.3, §5.6** — added staleness expiry and `first_notified_at`, which the
  once-only push promise had no state to stand on.
- **§5.5** — added the empty-frozen-set rule; recorded the reviewer's dissent
  on Surface B rather than silently overriding it.
- **§5.7** — split the operator loop into the interactive case (no new
  credential) and the unattended case (out of scope).
- **§1** — corrected the count of `"failed"` writes and named the two exits that
  write no status at all.
