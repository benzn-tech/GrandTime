# Auto screen-off (honor app-set timeout) — design

**Date:** 2026-07-17
**Status:** design approved (verbal 2026-07-16/17), probe-gated before plan
**Repo:** GrandTime (FieldSight mobile app), `com.benzn.grandtime`

## Problem
The in-app "screen off after N minutes" setting (`screenOffMinutes`, options 1/3/5/Never, default 3) never turns the screen off. Root cause (verified on device F2S202503103054, 2026-07-16):
- The app's mechanism is **passive**: on a video-recording timer it only *clears* its own `FLAG_KEEP_SCREEN_ON`, deferring to the OS idle timer. It never actively powers the display off.
- The device's **system `screen_off_timeout` = 1 800 000 ms (30 min)**, so after the app releases keep-awake the screen still won't sleep for 30 min (and any touch resets it). The app's "1 min" is therefore never honored.
- The timer is also armed **only during video recording segment 1** (`CaptureManager.kt:247`); idle on Home/Files/Settings, or audio/photo capture, never arms it.
- A normal (non-system) app cannot force the display off except via `DevicePolicyManager.lockNow()` (device admin, also locks).

## Chosen approach (user decisions 2026-07-16/17)
- **Mechanism:** app writes the **system** `Settings.System.SCREEN_OFF_TIMEOUT` to the chosen minutes via the special `WRITE_SETTINGS` permission. The OS then sleeps the display naturally at N minutes of inactivity — **no lock, no re-unlock, recording continues**. Accepted trade-off: this changes a device-**global** setting (fine — single-purpose device).
- **Scope:** applies everywhere (recording **and** idle) — the system idle timer already means "N min of no user interaction", which is exactly the desired semantics.

## Critical dependency — recording must survive screen-off
The codebase holds **no WakeLock anywhere**. Recording currently keeps running only because `FLAG_KEEP_SCREEN_ON` keeps the display (and therefore the CPU) awake. If the screen is now allowed to sleep during recording, the CPU may suspend and the recording would truncate/corrupt. Therefore the design must:
- Add `WAKE_LOCK` permission + hold a **`PARTIAL_WAKE_LOCK`** while any capture (video or audio) is active; release it when capture stops. (CPU stays awake with the screen off.)
- Rely on the existing **OverlayGuard** (1×1 overlay) to keep camera/mic AppOps alive when the app is non-top/screen-off — it was built for exactly this scenario.
- **Remove** the now-redundant, counterproductive keep-awake path: `RecordingScreen`'s `addFlags/clearFlags(FLAG_KEEP_SCREEN_ON)`, `AppState.screenOffRequest`, and `CaptureManager`'s `screenOffTimer`/`start/stopScreenOffTimer`. The system timeout replaces all of it.

## Probe FIRST (kill-gate, before any plan)
"Recording continues after the screen sleeps" has never been exercised on this MediaTek OEM ROM (the screen never slept during recording before). Per the project's "probe to decide life-or-death first" rule:

1. Start a recording, force the display off (`adb shell input keyevent 26` / power), wait ~60 s, wake, stop.
2. Pull the segment(s), `ffprobe` → confirm non-zero, non-truncated, audio+video present, expected duration.
3. Test both **without** and (if needed) **with** a `PARTIAL_WAKE_LOCK` to learn whether the foreground service alone suffices or the wakelock is required.

**If recording dies on screen-off even with wakelock + overlay:** the ROM forbids it → fall back (keep the display on during active recording; apply the system-timeout sleep to **idle only**). Revisit the design before planning.

## Behavior details
- `screenOffMinutes` value → system timeout: `1/3/5` → `minutes*60_000`; `Never (0)` → `Int.MAX_VALUE` (OS treats as never).
- Write the system timeout: when the setting changes, and on service start (re-assert, since it's global and may drift). Guard with `Settings.System.canWrite(context)`; if not granted, no-op and surface the permission entry.
- `WRITE_SETTINGS` UX: same pattern as existing MANAGE_EXTERNAL_STORAGE / SYSTEM_ALERT_WINDOW — an entry that launches `ACTION_MANAGE_WRITE_SETTINGS` for the user to grant once.
- No save/restore of the device's original timeout (YAGNI — the app owns this device).

## Global constraints
- All Android framework, no new Gradle deps / native libs; no Google Play Services. English for all new code/comments; Chinese only for user-facing copy already in that style.
- WAKE_LOCK is a normal permission (auto-granted at install); WRITE_SETTINGS is a special/settings-page permission.

## Testing / acceptance
- **Unit (JVM):** `screenOffMinutes` → timeout-ms mapping (1/3/5/Never→MAX); write skipped when `canWrite` is false.
- **Device (F2SP):** probe above (recording survives screen-off); after full impl — set 1 min, leave idle on Home → screen sleeps at ~1 min; set 1 min, record → screen sleeps at ~1 min while recording, segment stays intact and keeps rolling; "Never" → stays awake; re-enable path when WRITE_SETTINGS revoked.

## Out of scope
- Device-admin/force-lock mechanism (rejected in favor of system-timeout).
- Phase-2 app rename; unrelated capture-UX tickets.
