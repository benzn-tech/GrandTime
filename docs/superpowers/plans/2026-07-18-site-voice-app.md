# Site Voice — GrandTime App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the F2SP bodycam a second hold-to-talk key (the SOS key) that records a short clip, uploads it, and pushes it to every online member of the currently-selected site over a persistent WebSocket, playing back inbound clips with a cue tone — site-scoped, off-the-record.

**Architecture:** A new `SosKeySource` (mirror of `PttKeySource`) drives a pure `SiteVoiceCore` FSM through `SiteVoiceManager` (sibling of `AskManager` inside `CoreService`). Sending reuses `AskRecorder`/`AudioRecorder` (WAV 16 kHz mono) → presigned S3 PUT → `sendVoice` over an OkHttp `WebSocket` held open by the foreground service. Receiving parses inbound WS frames, downloads the clip via a presigned GET, and plays it through `AskPlayer` + a new cue. All decision logic (FSM, reconnect backoff, WS message parsing, clip metadata) lives in pure JVM-tested units; the WebSocket/audio/Compose shells are device-verified.

**Tech Stack:** Kotlin 2.1 / Compose / AGP 8.7, minSdk=targetSdk 33, `com.benzn.grandtime`. OkHttp 4.12.0 (`WebSocket`/`newWebSocket` — already a dependency), Android framework `AudioRecord`/`MediaPlayer`/`SoundPool`/`ConnectivityManager`, Jetpack DataStore, coroutines. JUnit4 + coroutines-test + `org.json:json` for unit tests.

## Global Constraints
- All-Android-framework; **NO new Gradle deps** — OkHttp `WebSocket` (`okhttp3.WebSocket`/`WebSocketListener`/`OkHttpClient.newWebSocket`) is already available via `libs.okhttp` 4.12.0.
- armeabi 32-bit only; no native libs.
- No Google Play Services (MediaTek) — no FCM; the realtime channel is the self-hosted WS. Use `ConnectivityManager` (framework), never Fused anything.
- English-only dev artifacts (code comments, commit messages, technical docs); user-visible strings English.
- Flavor split: `dev` → fieldsight-test stack (`wdsgobb7b0`), `prod` → prod (`ys94qy2tk0`). Add a per-flavor `SITE_VOICE_WS_URL` + `SITE_VOICE_ENABLED` mirroring `ORG_API_BASE_URL`.
- idToken goes **raw** in the `Authorization` header (no `Bearer` prefix) — both REST (`RealHttp`) and the WS handshake.
- Build with Android Studio JBR: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` before `./gradlew`. Dropbox build lock: an occasional `Could not delete '...build...'` — just re-run.
- Unit tests: `./gradlew testProdDebugUnitTest` (currently 163 green — keep them green). Flavor builds: `assembleProdDebug` / `assembleDevDebug`.
- WebSocket / audio / Compose classes are JVM-untestable (real-device only). This plan isolates the pure logic (FSM, backoff, message parse, clip duration, DataStore) into unit-tested units and marks every on-device step explicitly. This is the **first on-device WebSocket in the repo** — real-device soak is mandatory.

---

## File Structure

### New files
| File | Responsibility |
|------|----------------|
| `app/src/main/java/com/benzn/grandtime/sitevoice/SosKeySource.kt` | Raw `lolaage.sos.down`/`.up` broadcast → `SosDirection` flow (hold-to-talk). Mirror of `PttKeySource`. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceCore.kt` | Pure FSM (`Idle/Recording/Sending/Playing`) + inbound queue + mic arbitration. No Android deps. Holds `SiteVoiceState`, `SiteVoiceCommand`, `VoiceClip`. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/WsBackoff.kt` | Pure exponential reconnect-delay calculator. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/WsMessages.kt` | Pure inbound-frame parse (`InboundVoice`) + `sendVoice` frame builder + WAV clip duration. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/VoiceApiClient.kt` | REST client: `POST /org/voice/upload-url`, presigned PUT, `GET /org/voice/asset-url`, `GET /org/sites/{id}/voice?since=`. Pure parse fns unit-tested. Reuses `HttpFns`/`SitesHttpFns`. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/LastSeenStore.kt` | DataStore persistence of `lastSeenTs` for offline backfill. Mirror of `SiteStore`. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/VoiceWsClient.kt` | OkHttp `WebSocket` shell: connect w/ idToken handshake header, backoff reconnect, ping, network-callback re-connect. Device-verified. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt` | Orchestrator (sibling to `AskManager`): SOS→record→upload→send, inbound→download→play, backfill, inbox. Device-verified. |
| `app/src/main/res/raw/voice_received.wav` | Distinct inbound cue tone (bundled asset). |
| `app/src/test/java/com/benzn/grandtime/sitevoice/SosKeySourceTest.kt` | Unit: SOS action parsing. |
| `app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt` | Unit: full FSM + queue + arbitration. |
| `app/src/test/java/com/benzn/grandtime/sitevoice/WsBackoffTest.kt` | Unit: backoff curve + cap. |
| `app/src/test/java/com/benzn/grandtime/sitevoice/WsMessagesTest.kt` | Unit: inbound parse / frame build / duration. |
| `app/src/test/java/com/benzn/grandtime/sitevoice/VoiceApiClientTest.kt` | Unit: parse + injected-http wiring. |
| `app/src/test/java/com/benzn/grandtime/sitevoice/LastSeenStoreTest.kt` | Unit: DataStore round-trip. |

### Modified files
| File | Change |
|------|--------|
| `app/src/main/java/com/benzn/grandtime/hardware/F2spKeyEventSource.kt` | Remove `"lolaage.sos" to HardKey.SOS` from `KEY_ACTION_PREFIXES` (de-PTT-ify SOS). |
| `app/src/test/java/com/benzn/grandtime/hardware/F2spActionParserTest.kt` | Move the `lolaage.sos.down` assertion from "parses to key" to "parses to null". |
| `app/src/main/java/com/benzn/grandtime/core/AppState.kt` | Add `siteVoiceConnected`, `siteVoiceActive`, `askActive`, `siteVoiceInbox`, `siteVoiceReplayRequests`. |
| `app/src/main/java/com/benzn/grandtime/ask/AskManager.kt` | Add one status-mirror line writing `AppState.askActive` (AskCore FSM untouched). |
| `app/src/main/java/com/benzn/grandtime/ask/AskSounds.kt` | Add `received()` loading `R.raw.voice_received`. |
| `app/build.gradle.kts` | Add `SITE_VOICE_WS_URL` + `SITE_VOICE_ENABLED` buildConfig fields to both flavors. |
| `app/src/main/AndroidManifest.xml` | Add `ACCESS_NETWORK_STATE` (for `ConnectivityManager` default-network callback). |
| `app/src/main/java/com/benzn/grandtime/service/CoreService.kt` | Wire `SosKeySource` + `SiteVoiceManager` inside `startPipeline()`; shutdown in `onDestroy()`. |
| `app/src/main/java/com/benzn/grandtime/ui/HomeScreen.kt` | Add a "Site voice" card: connection dot + recent inbox list with replay. |

---

## Tasks

### Task 1 — `SosKeySource` + de-PTT-ify the SOS key

**Files:**
- Create `app/src/main/java/com/benzn/grandtime/sitevoice/SosKeySource.kt`
- Create `app/src/test/java/com/benzn/grandtime/sitevoice/SosKeySourceTest.kt`
- Modify `app/src/main/java/com/benzn/grandtime/hardware/F2spKeyEventSource.kt` (line 35: remove the `lolaage.sos` entry)
- Modify `app/src/test/java/com/benzn/grandtime/hardware/F2spActionParserTest.kt` (line 15)

**Interfaces:**
- Produces: `enum class SosDirection { DOWN, UP }`; `class SosKeySource(context: Context)` with `val events: SharedFlow<SosDirection>`, `fun start()`, `fun stop()`, and `companion object { const val ACTION_DOWN = "lolaage.sos.down"; const val ACTION_UP = "lolaage.sos.up"; fun parse(action: String): SosDirection? }`.
- Consumes: `android.content.Context` (registers a `RECEIVER_EXPORTED` broadcast receiver).

Steps:
- [ ] Write failing test `SosKeySourceTest` (mirror `PttKeySourceTest`):
```kotlin
package com.benzn.grandtime.sitevoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SosKeySourceTest {
    @Test fun parses_down() {
        assertEquals(SosDirection.DOWN, SosKeySource.parse("lolaage.sos.down"))
    }

    @Test fun parses_up() {
        assertEquals(SosDirection.UP, SosKeySource.parse("lolaage.sos.up"))
    }

    @Test fun ignores_unrelated_actions() {
        assertNull(SosKeySource.parse("lolaage.ptt.down"))
        assertNull(SosKeySource.parse("lolaage.sos"))
        assertNull(SosKeySource.parse("lolaage.video1.down"))
    }
}
```
- [ ] Run (expected FAIL — `SosKeySource` unresolved): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.SosKeySourceTest"`
- [ ] Implement `SosKeySource.kt` (mirror `PttKeySource.kt` exactly, SOS actions):
```kotlin
package com.benzn.grandtime.sitevoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Raw direction of a Site-voice (SOS) key event. */
enum class SosDirection { DOWN, UP }

/**
 * Dedicated source for the F2SP SOS key, repurposed as the Site-voice hold-to-talk key.
 * The ROM emits `lolaage.sos.down` / `lolaage.sos.up`; [com.benzn.grandtime.hardware.F2spKeyEventSource]
 * deliberately does NOT register `lolaage.sos` (去对讲化), the same treatment PTT already gets, so
 * this source emits raw down/up directly for hold-until-release.
 *
 * Compliance: `lolaage.*` is the ROM's public broadcast interface; clean re-implementation.
 * Collectors must subscribe to [events] BEFORE start() — MutableSharedFlow(replay=0) drops
 * emissions with no subscribers.
 */
class SosKeySource(private val context: Context) {

    private val _events = MutableSharedFlow<SosDirection>(extraBufferCapacity = 16)
    val events: SharedFlow<SosDirection> = _events

    companion object {
        const val ACTION_DOWN = "lolaage.sos.down"
        const val ACTION_UP = "lolaage.sos.up"

        fun parse(action: String): SosDirection? = when (action) {
            ACTION_DOWN -> SosDirection.DOWN
            ACTION_UP -> SosDirection.UP
            else -> null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            parse(intent.action ?: return)?.let { _events.tryEmit(it) }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_DOWN)
            addAction(ACTION_UP)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
```
- [ ] Run (expected PASS): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.SosKeySourceTest"`
- [ ] Update `F2spActionParserTest.kt`: delete line `assertEquals(HardKey.SOS to RawDirection.DOWN, F2spKeyEventSource.parse("lolaage.sos.down"))` from `known key actions parse to key and direction`, and add `assertNull(F2spKeyEventSource.parse("lolaage.sos.down"))` to `ptt and probe-only and unknown actions parse to null`.
- [ ] Run (expected FAIL — SOS still maps): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.hardware.F2spActionParserTest"`
- [ ] In `F2spKeyEventSource.kt` remove the map line `"lolaage.sos" to HardKey.SOS,` from `KEY_ACTION_PREFIXES` (leave `HardKey.SOS` enum + `KeyMapping` defaults dormant, exactly as PTT is excluded but `KeyAction.ASK_AGENT` remains).
- [ ] Run (expected PASS): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.hardware.F2spActionParserTest"`
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/sitevoice/SosKeySource.kt app/src/test/java/com/benzn/grandtime/sitevoice/SosKeySourceTest.kt app/src/main/java/com/benzn/grandtime/hardware/F2spKeyEventSource.kt app/src/test/java/com/benzn/grandtime/hardware/F2spActionParserTest.kt` + `git commit -m "Add SosKeySource and exclude lolaage.sos from F2sp key prefixes"`

---

### Task 2 — `SiteVoiceCore` pure FSM (send + receive + arbitration)

**Files:**
- Create `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceCore.kt`
- Create `app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt`

**Interfaces:**
- Produces:
  - `enum class SiteVoiceState { Idle, Recording, Sending, Playing }`
  - `data class VoiceClip(val file: File, val s3Key: String, val senderUserId: String, val createdAt: String, val durationS: Int)`
  - `sealed interface SiteVoiceCommand` with objects `PlayTalkStartCue`, `StartRecording`, `StopRecording`, `ArmCapTimer`, `CancelCapTimer`, `UploadAndSend`, `PlayBusyCue`, `PlayErrorCue`, and `data class PlayClip(val clip: VoiceClip)`.
  - `class SiteVoiceCore` with `var state: SiteVoiceState (private set)`, `val queueSize: Int`, and pure methods: `fun onSosDown(videoRecording: Boolean, askActive: Boolean): List<SiteVoiceCommand>`, `fun onSosUp(): List<SiteVoiceCommand>`, `fun onCapReached(): List<SiteVoiceCommand>`, `fun onSendResult(ok: Boolean): List<SiteVoiceCommand>`, `fun onClipReady(clip: VoiceClip): List<SiteVoiceCommand>`, `fun onPlaybackDone(): List<SiteVoiceCommand>`, `fun onError(): List<SiteVoiceCommand>`.
- Consumes: nothing Android — `java.io.File` only (JVM type).

Steps:
- [ ] Write failing test `SiteVoiceCoreTest` (mirror `AskCoreTest` style):
```kotlin
package com.benzn.grandtime.sitevoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SiteVoiceCoreTest {
    private fun core() = SiteVoiceCore()
    private fun clip(k: String) = VoiceClip(File(k), k, "u9", "2026-07-18T00:00:00Z", 3)

    @Test fun down_when_idle_and_free_starts_recording() {
        val c = core()
        val cmds = c.onSosDown(videoRecording = false, askActive = false)
        assertEquals(SiteVoiceState.Recording, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.PlayTalkStartCue))
        assertTrue(cmds.contains(SiteVoiceCommand.StartRecording))
        assertTrue(cmds.contains(SiteVoiceCommand.ArmCapTimer))
    }

    @Test fun down_while_video_recording_is_busy_and_stays_idle() {
        val c = core()
        val cmds = c.onSosDown(videoRecording = true, askActive = false)
        assertEquals(SiteVoiceState.Idle, c.state)
        assertEquals(listOf(SiteVoiceCommand.PlayBusyCue), cmds)
    }

    @Test fun down_while_ask_active_is_busy_and_stays_idle() {
        val c = core()
        val cmds = c.onSosDown(videoRecording = false, askActive = true)
        assertEquals(SiteVoiceState.Idle, c.state)
        assertEquals(listOf(SiteVoiceCommand.PlayBusyCue), cmds)
    }

    @Test fun up_while_recording_uploads_and_sends() {
        val c = core().apply { onSosDown(false, false) }
        val cmds = c.onSosUp()
        assertEquals(SiteVoiceState.Sending, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.CancelCapTimer))
        assertTrue(cmds.contains(SiteVoiceCommand.StopRecording))
        assertTrue(cmds.contains(SiteVoiceCommand.UploadAndSend))
    }

    @Test fun cap_reached_while_recording_auto_sends() {
        val c = core().apply { onSosDown(false, false) }
        val cmds = c.onCapReached()
        assertEquals(SiteVoiceState.Sending, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.StopRecording))
        assertTrue(cmds.contains(SiteVoiceCommand.UploadAndSend))
    }

    @Test fun send_ok_with_empty_queue_returns_to_idle() {
        val c = core().apply { onSosDown(false, false); onSosUp() }
        val cmds = c.onSendResult(ok = true)
        assertEquals(SiteVoiceState.Idle, c.state)
        assertTrue(cmds.isEmpty())
    }

    @Test fun send_failure_plays_error_and_returns_to_idle() {
        val c = core().apply { onSosDown(false, false); onSosUp() }
        val cmds = c.onSendResult(ok = false)
        assertEquals(SiteVoiceState.Idle, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.PlayErrorCue))
    }

    @Test fun inbound_when_idle_plays_immediately() {
        val c = core()
        val cmds = c.onClipReady(clip("a"))
        assertEquals(SiteVoiceState.Playing, c.state)
        assertEquals(listOf(SiteVoiceCommand.PlayClip(clip("a"))), cmds)
    }

    @Test fun inbound_while_recording_is_queued() {
        val c = core().apply { onSosDown(false, false) }
        val cmds = c.onClipReady(clip("a"))
        assertTrue(cmds.isEmpty())
        assertEquals(1, c.queueSize)
        assertEquals(SiteVoiceState.Recording, c.state)
    }

    @Test fun queued_clip_plays_after_send_completes() {
        val c = core().apply { onSosDown(false, false); onClipReady(clip("a")); onSosUp() }
        val cmds = c.onSendResult(ok = true)
        assertEquals(SiteVoiceState.Playing, c.state)
        assertEquals(listOf(SiteVoiceCommand.PlayClip(clip("a"))), cmds)
        assertEquals(0, c.queueSize)
    }

    @Test fun playback_done_plays_next_queued_then_idles() {
        val c = core().apply { onClipReady(clip("a")); onClipReady(clip("b")) } // a playing, b queued
        val next = c.onPlaybackDone()
        assertEquals(listOf(SiteVoiceCommand.PlayClip(clip("b"))), next)
        assertEquals(SiteVoiceState.Playing, c.state)
        val done = c.onPlaybackDone()
        assertTrue(done.isEmpty())
        assertEquals(SiteVoiceState.Idle, c.state)
    }

    @Test fun down_while_playing_is_ignored() {
        val c = core().apply { onClipReady(clip("a")) } // Playing
        assertTrue(c.onSosDown(false, false).isEmpty())
        assertEquals(SiteVoiceState.Playing, c.state)
    }

    @Test fun cap_after_up_does_not_second_send() {
        val c = core().apply { onSosDown(false, false); onSosUp() } // Sending
        assertTrue(c.onCapReached().isEmpty())
        assertEquals(SiteVoiceState.Sending, c.state)
    }
}
```
- [ ] Run (expected FAIL — types unresolved): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.SiteVoiceCoreTest"`
- [ ] Implement `SiteVoiceCore.kt`:
```kotlin
package com.benzn.grandtime.sitevoice

import java.io.File

/** A downloaded inbound Site-voice clip ready to play (or replay from the inbox). */
data class VoiceClip(
    val file: File,
    val s3Key: String,
    val senderUserId: String,
    val createdAt: String,
    val durationS: Int,
)

/** Audio-only side effects (no screen UI). Mirrors AskCommand. */
sealed interface SiteVoiceCommand {
    data object PlayTalkStartCue : SiteVoiceCommand
    data object StartRecording : SiteVoiceCommand
    data object StopRecording : SiteVoiceCommand
    data object ArmCapTimer : SiteVoiceCommand
    data object CancelCapTimer : SiteVoiceCommand
    data object UploadAndSend : SiteVoiceCommand
    data object PlayBusyCue : SiteVoiceCommand
    data object PlayErrorCue : SiteVoiceCommand
    data class PlayClip(val clip: VoiceClip) : SiteVoiceCommand
}

enum class SiteVoiceState { Idle, Recording, Sending, Playing }

/**
 * Pure decision core for Site voice. No Android deps; the caller (SiteVoiceManager) serializes
 * calls on one dispatcher and executes the commands. Owns:
 *  - the hold-to-talk send path (Recording -> Sending), with a ~15s cap;
 *  - mic arbitration: refuse (busy cue) when a video recording OR an Ask talk is active, and
 *    ignore re-entrant SOS while already busy — one talk at a time;
 *  - the inbound queue: a clip arriving while the user is Recording/Sending/Playing is queued and
 *    played after (the sender never hears its own — excluded server-side at fanout).
 */
class SiteVoiceCore {
    var state: SiteVoiceState = SiteVoiceState.Idle
        private set

    private val queue = ArrayDeque<VoiceClip>()
    val queueSize: Int get() = queue.size

    fun onSosDown(videoRecording: Boolean, askActive: Boolean): List<SiteVoiceCommand> = when (state) {
        SiteVoiceState.Idle ->
            if (videoRecording || askActive) {
                listOf(SiteVoiceCommand.PlayBusyCue) // mic exclusivity: no-op talk
            } else {
                state = SiteVoiceState.Recording
                listOf(
                    SiteVoiceCommand.PlayTalkStartCue,
                    SiteVoiceCommand.StartRecording,
                    SiteVoiceCommand.ArmCapTimer,
                )
            }
        else -> emptyList() // ignore re-entrant down / down while sending or playing
    }

    fun onSosUp(): List<SiteVoiceCommand> = when (state) {
        SiteVoiceState.Recording -> {
            state = SiteVoiceState.Sending
            listOf(
                SiteVoiceCommand.CancelCapTimer,
                SiteVoiceCommand.StopRecording,
                SiteVoiceCommand.UploadAndSend,
            )
        }
        else -> emptyList()
    }

    fun onCapReached(): List<SiteVoiceCommand> = when (state) {
        SiteVoiceState.Recording -> {
            state = SiteVoiceState.Sending
            listOf(SiteVoiceCommand.StopRecording, SiteVoiceCommand.UploadAndSend)
        }
        else -> emptyList()
    }

    fun onSendResult(ok: Boolean): List<SiteVoiceCommand> {
        if (state != SiteVoiceState.Sending) return emptyList()
        val prefix = if (ok) emptyList() else listOf(SiteVoiceCommand.PlayErrorCue)
        return drainOrIdle(prefix)
    }

    fun onClipReady(clip: VoiceClip): List<SiteVoiceCommand> = when (state) {
        SiteVoiceState.Idle -> {
            state = SiteVoiceState.Playing
            listOf(SiteVoiceCommand.PlayClip(clip))
        }
        else -> { queue.addLast(clip); emptyList() }
    }

    fun onPlaybackDone(): List<SiteVoiceCommand> {
        if (state != SiteVoiceState.Playing) return emptyList()
        return drainOrIdle(emptyList())
    }

    fun onError(): List<SiteVoiceCommand> = drainOrIdle(listOf(SiteVoiceCommand.CancelCapTimer, SiteVoiceCommand.PlayErrorCue))

    /** Play the next queued inbound (Playing) if any, else settle Idle. Prefix cues emit first. */
    private fun drainOrIdle(prefix: List<SiteVoiceCommand>): List<SiteVoiceCommand> {
        val next = queue.removeFirstOrNull()
        return if (next != null) {
            state = SiteVoiceState.Playing
            prefix + SiteVoiceCommand.PlayClip(next)
        } else {
            state = SiteVoiceState.Idle
            prefix
        }
    }
}
```
- [ ] Run (expected PASS): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.SiteVoiceCoreTest"`
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceCore.kt app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt` + `git commit -m "Add SiteVoiceCore pure FSM with inbound queue and mic arbitration"`

---

### Task 3 — `WsBackoff` pure reconnect-delay calculator

**Files:**
- Create `app/src/main/java/com/benzn/grandtime/sitevoice/WsBackoff.kt`
- Create `app/src/test/java/com/benzn/grandtime/sitevoice/WsBackoffTest.kt`

**Interfaces:**
- Produces: `object WsBackoff { const val BASE_MILLIS = 1_000L; const val CAP_MILLIS = 30_000L; fun delayMillis(attempt: Int): Long }` — deterministic (no jitter) so it is unit-testable; `attempt` is 0-based.
- Consumes: nothing.

Steps:
- [ ] Write failing test `WsBackoffTest`:
```kotlin
package com.benzn.grandtime.sitevoice

import org.junit.Assert.assertEquals
import org.junit.Test

class WsBackoffTest {
    @Test fun first_attempt_is_base() = assertEquals(1_000L, WsBackoff.delayMillis(0))
    @Test fun doubles_each_attempt() {
        assertEquals(2_000L, WsBackoff.delayMillis(1))
        assertEquals(4_000L, WsBackoff.delayMillis(2))
        assertEquals(8_000L, WsBackoff.delayMillis(3))
    }
    @Test fun caps_at_thirty_seconds() {
        assertEquals(30_000L, WsBackoff.delayMillis(6))   // 64s uncapped -> capped
        assertEquals(30_000L, WsBackoff.delayMillis(100)) // no overflow
    }
    @Test fun negative_attempt_treated_as_zero() = assertEquals(1_000L, WsBackoff.delayMillis(-5))
}
```
- [ ] Run (expected FAIL): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.WsBackoffTest"`
- [ ] Implement `WsBackoff.kt`:
```kotlin
package com.benzn.grandtime.sitevoice

/**
 * Pure exponential reconnect-delay calculator for [VoiceWsClient]. Deterministic (no jitter) so it
 * is JVM-testable; the WS shell adds the wall-clock delay. attempt is 0-based (0 = first retry).
 */
object WsBackoff {
    const val BASE_MILLIS = 1_000L
    const val CAP_MILLIS = 30_000L

    fun delayMillis(attempt: Int): Long {
        val n = attempt.coerceIn(0, 30) // 2^30 * base already far exceeds CAP; guards overflow
        val raw = BASE_MILLIS shl n     // BASE * 2^n
        return raw.coerceAtMost(CAP_MILLIS)
    }
}
```
- [ ] Run (expected PASS): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.WsBackoffTest"`
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/sitevoice/WsBackoff.kt app/src/test/java/com/benzn/grandtime/sitevoice/WsBackoffTest.kt` + `git commit -m "Add WsBackoff exponential reconnect-delay calculator"`

---

### Task 4 — `WsMessages` inbound parse + `sendVoice` frame + WAV duration

**Files:**
- Create `app/src/main/java/com/benzn/grandtime/sitevoice/WsMessages.kt`
- Create `app/src/test/java/com/benzn/grandtime/sitevoice/WsMessagesTest.kt`

**Interfaces:**
- Produces:
  - `data class InboundVoice(val s3Key: String, val senderUserId: String, val siteId: String?, val createdAt: String, val durationS: Int?)`
  - `object WsMessages { fun parseInbound(text: String): InboundVoice?; fun sendVoiceFrame(siteId: String, s3Key: String, durationS: Int): String; fun wavDurationSeconds(sizeBytes: Long, sampleRate: Int = 16000): Int }`
- Consumes: `org.json` (real impl available in unit tests via the `org.json:json` test dep).

Contract encoded: inbound WS frame `{s3Key, senderUserId, siteId, createdAt}` (durationS optional); outbound `{action:"sendVoice", siteId, s3Key, durationS}`. WAV = 16 kHz mono 16-bit (32000 bytes/s), 44-byte header (matches `AudioRecorder`/`WavHeader.riffWav`).

Steps:
- [ ] Write failing test `WsMessagesTest`:
```kotlin
package com.benzn.grandtime.sitevoice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WsMessagesTest {
    @Test fun parses_inbound_frame() {
        val text = """{"s3Key":"voice/co/site1/abc.wav","senderUserId":"u9","siteId":"site1","createdAt":"2026-07-18T02:03:04Z"}"""
        val m = WsMessages.parseInbound(text)!!
        assertEquals("voice/co/site1/abc.wav", m.s3Key)
        assertEquals("u9", m.senderUserId)
        assertEquals("site1", m.siteId)
        assertEquals("2026-07-18T02:03:04Z", m.createdAt)
        assertNull(m.durationS)
    }

    @Test fun parses_inbound_with_optional_duration() {
        val text = """{"s3Key":"k","senderUserId":"u","createdAt":"t","durationS":7}"""
        assertEquals(7, WsMessages.parseInbound(text)!!.durationS)
    }

    @Test fun inbound_missing_s3key_is_null() {
        assertNull(WsMessages.parseInbound("""{"senderUserId":"u","createdAt":"t"}"""))
    }

    @Test fun malformed_inbound_is_null() {
        assertNull(WsMessages.parseInbound("not json"))
    }

    @Test fun builds_send_voice_frame() {
        val body = JSONObject(WsMessages.sendVoiceFrame("site1", "voice/x.wav", 4))
        assertEquals("sendVoice", body.getString("action"))
        assertEquals("site1", body.getString("siteId"))
        assertEquals("voice/x.wav", body.getString("s3Key"))
        assertEquals(4, body.getInt("durationS"))
    }

    @Test fun wav_duration_from_bytes() {
        // 44-byte header + 2s of 16kHz mono 16-bit = 44 + 64000
        assertEquals(2, WsMessages.wavDurationSeconds(44 + 64_000L))
        assertEquals(0, WsMessages.wavDurationSeconds(44))
        assertEquals(0, WsMessages.wavDurationSeconds(10)) // shorter than header -> 0, no negative
    }
}
```
- [ ] Run (expected FAIL): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.WsMessagesTest"`
- [ ] Implement `WsMessages.kt`:
```kotlin
package com.benzn.grandtime.sitevoice

import org.json.JSONObject

/** A parsed inbound Site-voice push (before the clip is downloaded). */
data class InboundVoice(
    val s3Key: String,
    val senderUserId: String,
    val siteId: String?,
    val createdAt: String,
    val durationS: Int?,
)

/**
 * Pure (de)serialization for the Site-voice WS channel and clip metadata. All I/O lives in
 * [VoiceWsClient] / [SiteVoiceManager]; this stays JVM-testable.
 *
 * Contract: inbound frame {s3Key, senderUserId, siteId, createdAt[, durationS]};
 * outbound {action:"sendVoice", siteId, s3Key, durationS}.
 */
object WsMessages {
    private const val WAV_HEADER_BYTES = 44L
    private const val BYTES_PER_SAMPLE = 2 // 16-bit mono

    fun parseInbound(text: String): InboundVoice? = runCatching {
        val o = JSONObject(text)
        val s3Key = o.optString("s3Key").takeIf { it.isNotBlank() } ?: return@runCatching null
        InboundVoice(
            s3Key = s3Key,
            senderUserId = o.optString("senderUserId"),
            siteId = o.optString("siteId").takeIf { it.isNotBlank() },
            createdAt = o.optString("createdAt"),
            durationS = if (o.has("durationS")) o.optInt("durationS") else null,
        )
    }.getOrNull()

    fun sendVoiceFrame(siteId: String, s3Key: String, durationS: Int): String =
        JSONObject()
            .put("action", "sendVoice")
            .put("siteId", siteId)
            .put("s3Key", s3Key)
            .put("durationS", durationS)
            .toString()

    /** Whole seconds of a 16 kHz mono 16-bit WAV from its file size (header excluded). */
    fun wavDurationSeconds(sizeBytes: Long, sampleRate: Int = 16000): Int {
        val pcm = (sizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0)
        return (pcm / (sampleRate.toLong() * BYTES_PER_SAMPLE)).toInt()
    }
}
```
- [ ] Run (expected PASS): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.WsMessagesTest"`
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/sitevoice/WsMessages.kt app/src/test/java/com/benzn/grandtime/sitevoice/WsMessagesTest.kt` + `git commit -m "Add WsMessages inbound parse, sendVoice frame builder, and WAV duration"`

---

### Task 5 — `VoiceApiClient` (upload-url + asset-url + backfill)

**Files:**
- Create `app/src/main/java/com/benzn/grandtime/sitevoice/VoiceApiClient.kt`
- Create `app/src/test/java/com/benzn/grandtime/sitevoice/VoiceApiClientTest.kt`

**Interfaces:**
- Produces:
  - `data class VoiceUploadReq(val siteId: String, val fileName: String, val contentType: String, val startedAt: String, val durationS: Int, val sizeBytes: Long)`
  - `class VoiceApiClient(baseUrl: String, http: HttpFns = RealHttp(), getHttp: SitesHttpFns = RealSitesHttp())` with:
    - `sealed interface UploadUrlResult { data class Ok(val uploadUrl: String, val s3Key: String); data object AuthExpired; data class Error(val message: String) }`
    - `data class BackfillItem(val s3Key: String, val senderUserId: String, val createdAt: String, val durationS: Int?)`
    - `fun uploadUrl(idToken: String, req: VoiceUploadReq): UploadUrlResult`
    - `fun putFile(uploadUrl: String, contentType: String, file: File): Boolean`
    - `fun downloadUrl(idToken: String, s3Key: String): String?`
    - `fun backfill(idToken: String, siteId: String, sinceIso: String?): List<BackfillItem>`
    - `companion object { fun parseUploadUrl(r: HttpResult): UploadUrlResult; fun parseDownloadUrl(r: HttpResult): String?; fun parseBackfill(r: HttpResult): List<BackfillItem> }`
- Consumes: `com.benzn.grandtime.net.HttpFns` (`postJson`/`putFile`), `com.benzn.grandtime.net.RealHttp`, `com.benzn.grandtime.net.SitesHttpFns`/`RealSitesHttp` (`getJson`), `com.benzn.grandtime.auth.HttpResult`.

Contract (base already ends `/prod/api`, mirror `RecordingsApiClient`): `POST $baseUrl/org/voice/upload-url` body `{siteId, fileName, contentType, startedAt, durationS, sizeBytes}` (backend reads only siteId/contentType/durationS; extra fields are ignored) → `{uploadUrl, s3Key}` (NO row is created here — `sendVoice` is the sole writer of `voice_messages`); `GET $baseUrl/org/voice/asset-url?key=<s3Key>` → `{url}`; `GET $baseUrl/org/sites/{siteId}/voice?since=<iso>` → `{items:[{s3Key, senderUserId, createdAt, durationS}]}`.

Steps:
- [ ] Write failing test `VoiceApiClientTest` (mirror `RecordingsApiClientTest` — pure parse + injected-http wiring):
```kotlin
package com.benzn.grandtime.sitevoice

import com.benzn.grandtime.auth.HttpResult
import com.benzn.grandtime.net.HttpFns
import com.benzn.grandtime.net.SitesHttpFns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakePost(val resp: HttpResult) : HttpFns {
    var lastUrl: String? = null; var lastBody: String? = null
    override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
        lastUrl = url; lastBody = jsonBody; return resp
    }
    override fun putFile(url: String, contentType: String, file: File): Int = 200
}
private class FakeGet(val resp: HttpResult) : SitesHttpFns {
    var lastUrl: String? = null
    override fun getJson(url: String, authToken: String): HttpResult { lastUrl = url; return resp }
}

class VoiceApiClientTest {
    @Test fun `parse upload url ok`() {
        val b = """{"uploadUrl":"https://s3/put","s3Key":"voice/co/s/x.wav"}"""
        val r = VoiceApiClient.parseUploadUrl(HttpResult(200, b)) as VoiceApiClient.UploadUrlResult.Ok
        assertEquals("https://s3/put", r.uploadUrl)
        assertEquals("voice/co/s/x.wav", r.s3Key)
    }

    @Test fun `upload url 401 is AuthExpired`() {
        assertEquals(
            VoiceApiClient.UploadUrlResult.AuthExpired,
            VoiceApiClient.parseUploadUrl(HttpResult(401, "")),
        )
    }

    @Test fun `upload url blank s3Key is Error`() {
        val b = """{"uploadUrl":"u","s3Key":""}"""
        assertTrue(VoiceApiClient.parseUploadUrl(HttpResult(200, b)) is VoiceApiClient.UploadUrlResult.Error)
    }

    @Test fun `upload url wires request path and body`() {
        val fake = FakePost(HttpResult(200, """{"uploadUrl":"u","s3Key":"k"}"""))
        val client = VoiceApiClient("https://h/prod/api", http = fake)
        val req = VoiceUploadReq("site1", "clip.wav", "audio/wav", "2026-07-18T00:00:00Z", 3, 96044L)
        client.uploadUrl("ID", req)
        assertEquals("https://h/prod/api/org/voice/upload-url", fake.lastUrl)
        val body = org.json.JSONObject(fake.lastBody!!)
        assertEquals("site1", body.getString("siteId"))
        assertEquals("clip.wav", body.getString("fileName"))
        assertEquals("audio/wav", body.getString("contentType"))
        assertEquals(3, body.getInt("durationS"))
        assertEquals(96044L, body.getLong("sizeBytes"))
    }

    @Test fun `parse download url`() {
        assertEquals("https://s3/get", VoiceApiClient.parseDownloadUrl(HttpResult(200, """{"url":"https://s3/get"}""")))
        assertNull(VoiceApiClient.parseDownloadUrl(HttpResult(200, """{}""")))
        assertNull(VoiceApiClient.parseDownloadUrl(HttpResult(404, "")))
    }

    @Test fun `download url wires query and returns url`() {
        val fake = FakeGet(HttpResult(200, """{"url":"https://s3/get"}"""))
        val client = VoiceApiClient("https://h/prod/api", getHttp = fake)
        assertEquals("https://s3/get", client.downloadUrl("ID", "voice/co/s/x.wav"))
        assertEquals("https://h/prod/api/org/voice/asset-url?key=voice%2Fco%2Fs%2Fx.wav", fake.lastUrl)
    }

    @Test fun `parse backfill items`() {
        val b = """{"items":[{"s3Key":"a","senderUserId":"u1","createdAt":"t1","durationS":3},{"s3Key":"b","senderUserId":"u2","createdAt":"t2"}]}"""
        val items = VoiceApiClient.parseBackfill(HttpResult(200, b))
        assertEquals(2, items.size)
        assertEquals("a", items[0].s3Key)
        assertEquals(3, items[0].durationS)
        assertNull(items[1].durationS)
    }

    @Test fun `backfill non-2xx is empty`() {
        assertTrue(VoiceApiClient.parseBackfill(HttpResult(500, "boom")).isEmpty())
    }

    @Test fun `backfill wires since query`() {
        val fake = FakeGet(HttpResult(200, """{"items":[]}"""))
        val client = VoiceApiClient("https://h/prod/api", getHttp = fake)
        client.backfill("ID", "site1", "2026-07-18T00:00:00Z")
        assertEquals("https://h/prod/api/org/sites/site1/voice?since=2026-07-18T00%3A00%3A00Z", fake.lastUrl)
    }

    @Test fun `backfill without since omits query`() {
        val fake = FakeGet(HttpResult(200, """{"items":[]}"""))
        VoiceApiClient("https://h/prod/api", getHttp = fake).backfill("ID", "site1", null)
        assertEquals("https://h/prod/api/org/sites/site1/voice", fake.lastUrl)
    }
}
```
- [ ] Run (expected FAIL): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.VoiceApiClientTest"`
- [ ] Implement `VoiceApiClient.kt`:
```kotlin
package com.benzn.grandtime.sitevoice

import com.benzn.grandtime.auth.HttpResult
import com.benzn.grandtime.net.HttpFns
import com.benzn.grandtime.net.RealHttp
import com.benzn.grandtime.net.RealSitesHttp
import com.benzn.grandtime.net.SitesHttpFns
import java.io.File
import java.net.URLEncoder
import org.json.JSONObject

data class VoiceUploadReq(
    val siteId: String,
    val fileName: String,
    val contentType: String,
    val startedAt: String,
    val durationS: Int,
    val sizeBytes: Long,
)

/**
 * REST client for the dedicated Site-voice endpoints. Deliberately NOT RecordingsApiClient — voice
 * is off-the-record (its own voice/ prefix + voice_messages table, never recordings). Mirrors the
 * RecordingsApiClient/SitesApiClient testability seam: POST/PUT via [HttpFns], GET via [SitesHttpFns];
 * pure parse fns are TDD-covered, the OkHttp path is device-verified. baseUrl already ends in
 * `/prod/api`, so these hit `/api/org/voice/*` and `/api/org/sites/{id}/voice`.
 */
class VoiceApiClient(
    private val baseUrl: String,
    private val http: HttpFns = RealHttp(),
    private val getHttp: SitesHttpFns = RealSitesHttp(),
) {
    sealed interface UploadUrlResult {
        data class Ok(val uploadUrl: String, val s3Key: String) : UploadUrlResult
        data object AuthExpired : UploadUrlResult
        data class Error(val message: String) : UploadUrlResult
    }

    data class BackfillItem(val s3Key: String, val senderUserId: String, val createdAt: String, val durationS: Int?)

    fun uploadUrl(idToken: String, req: VoiceUploadReq): UploadUrlResult {
        val body = JSONObject()
            .put("siteId", req.siteId)
            .put("fileName", req.fileName)
            .put("contentType", req.contentType)
            .put("startedAt", req.startedAt)
            .put("durationS", req.durationS)
            .put("sizeBytes", req.sizeBytes)
        val result = runCatching { http.postJson("$baseUrl/org/voice/upload-url", idToken, body.toString()) }
            .getOrElse { return UploadUrlResult.Error("network") }
        return parseUploadUrl(result)
    }

    fun putFile(uploadUrl: String, contentType: String, file: File): Boolean {
        val code = runCatching { http.putFile(uploadUrl, contentType, file) }.getOrElse { return false }
        return code in 200..299
    }

    fun downloadUrl(idToken: String, s3Key: String): String? {
        val q = URLEncoder.encode(s3Key, "UTF-8")
        val result = runCatching { getHttp.getJson("$baseUrl/org/voice/asset-url?key=$q", idToken) }
            .getOrElse { return null }
        return parseDownloadUrl(result)
    }

    fun backfill(idToken: String, siteId: String, sinceIso: String?): List<BackfillItem> {
        val url = if (sinceIso != null) {
            "$baseUrl/org/sites/$siteId/voice?since=${URLEncoder.encode(sinceIso, "UTF-8")}"
        } else {
            "$baseUrl/org/sites/$siteId/voice"
        }
        val result = runCatching { getHttp.getJson(url, idToken) }.getOrElse { return emptyList() }
        return parseBackfill(result)
    }

    companion object {
        fun parseUploadUrl(r: HttpResult): UploadUrlResult {
            if (r.code == 401) return UploadUrlResult.AuthExpired
            return runCatching {
                if (r.code !in 200..299) return@runCatching UploadUrlResult.Error("HTTP ${r.code}: ${r.body}")
                val json = JSONObject(r.body)
                val uploadUrl = json.optString("uploadUrl")
                val s3Key = json.optString("s3Key")
                if (uploadUrl.isBlank() || s3Key.isBlank())
                    return@runCatching UploadUrlResult.Error("missing uploadUrl/s3Key")
                UploadUrlResult.Ok(uploadUrl = uploadUrl, s3Key = s3Key)
            }.getOrElse { UploadUrlResult.Error("malformed response") }
        }

        fun parseDownloadUrl(r: HttpResult): String? {
            if (r.code !in 200..299) return null
            return runCatching { JSONObject(r.body).optString("url").takeIf { it.isNotBlank() } }.getOrNull()
        }

        fun parseBackfill(r: HttpResult): List<BackfillItem> {
            if (r.code !in 200..299) return emptyList()
            return runCatching {
                val arr = JSONObject(r.body).optJSONArray("items") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val key = o.optString("s3Key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    BackfillItem(
                        s3Key = key,
                        senderUserId = o.optString("senderUserId"),
                        createdAt = o.optString("createdAt"),
                        durationS = if (o.has("durationS")) o.optInt("durationS") else null,
                    )
                }
            }.getOrElse { emptyList() }
        }
    }
}
```
- [ ] Run (expected PASS): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.VoiceApiClientTest"`
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/sitevoice/VoiceApiClient.kt app/src/test/java/com/benzn/grandtime/sitevoice/VoiceApiClientTest.kt` + `git commit -m "Add VoiceApiClient (upload-url, asset-url, backfill) with pure parsers"`

---

### Task 6 — `LastSeenStore` (persist `lastSeenTs` for backfill)

**Files:**
- Create `app/src/main/java/com/benzn/grandtime/sitevoice/LastSeenStore.kt`
- Create `app/src/test/java/com/benzn/grandtime/sitevoice/LastSeenStoreTest.kt`

**Interfaces:**
- Produces: `val Context.voiceDataStore: DataStore<Preferences>` (name `"site_voice"`); `class LastSeenStore(dataStore: DataStore<Preferences>)` with `val lastSeen: Flow<String?>` and `suspend fun set(iso: String)`.
- Consumes: Jetpack DataStore Preferences (same pattern as `SiteStore`/`SettingsStore`).

Steps:
- [ ] Write failing test `LastSeenStoreTest` (mirror `SiteStoreTest` DataStore harness):
```kotlin
package com.benzn.grandtime.sitevoice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LastSeenStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.newStore(): LastSeenStore = LastSeenStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(coroutineContext + Job())) {
            File(tmp.root, "site_voice.preferences_pb")
        },
    )

    @Test fun `absent is null`() = runTest(UnconfinedTestDispatcher()) {
        assertNull(newStore().lastSeen.first())
    }

    @Test fun `set then read back`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.set("2026-07-18T02:03:04Z")
        assertEquals("2026-07-18T02:03:04Z", store.lastSeen.first())
    }

    @Test fun `set overwrites`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.set("2026-07-18T01:00:00Z")
        store.set("2026-07-18T05:00:00Z")
        assertEquals("2026-07-18T05:00:00Z", store.lastSeen.first())
    }
}
```
- [ ] Run (expected FAIL): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.LastSeenStoreTest"`
- [ ] Implement `LastSeenStore.kt`:
```kotlin
package com.benzn.grandtime.sitevoice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore(name = "site_voice")

/**
 * Persists the ISO timestamp of the most-recent Site-voice message this device has played, so a
 * (re)connect can backfill only what was missed while disconnected. Mirrors SiteStore.
 */
class LastSeenStore(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val KEY_LAST_SEEN = stringPreferencesKey("last_seen_ts")
    }

    val lastSeen: Flow<String?> = dataStore.data.map { it[KEY_LAST_SEEN] }

    suspend fun set(iso: String) {
        dataStore.edit { it[KEY_LAST_SEEN] = iso }
    }
}
```
- [ ] Run (expected PASS): `./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.LastSeenStoreTest"`
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/sitevoice/LastSeenStore.kt app/src/test/java/com/benzn/grandtime/sitevoice/LastSeenStoreTest.kt` + `git commit -m "Add LastSeenStore for Site-voice backfill watermark"`

---

### Task 7 — `AppState` signals + `AskManager` activity mirror

**Files:**
- Modify `app/src/main/java/com/benzn/grandtime/core/AppState.kt` (after the `availableSites` field, ~line 44)
- Modify `app/src/main/java/com/benzn/grandtime/ask/AskManager.kt` (`dispatch`, ~lines 60-62)

**Interfaces:**
- Produces (on `AppState`):
  - `val siteVoiceConnected: MutableStateFlow<Boolean>` — WS connected (UI dot).
  - `val siteVoiceActive: MutableStateFlow<Boolean>` — Site voice owns the mic/speaker.
  - `val askActive: MutableStateFlow<Boolean>` — Ask owns the mic/speaker (written by `AskManager`; read by `SiteVoiceCore` for mutual exclusion).
  - `val siteVoiceInbox: MutableStateFlow<List<VoiceClip>>` — recent inbound clips (last N, replayable).
  - `val siteVoiceReplayRequests: MutableSharedFlow<String>` — UI → service replay request by `s3Key`.
- Consumes: `com.benzn.grandtime.sitevoice.VoiceClip`.

Note: `AskCore` (the pure FSM) is **untouched**. The only Ask change is a single additive status-mirror line in `AskManager.dispatch` — it does not alter Ask's own behavior. Ask stays authoritative for the mic; Site voice yields to Ask by reading `askActive`.

Steps:
- [ ] This task has no new pure unit under test (fields are plumbing). Guard it by extending `SiteVoiceCoreTest` is already done via `askActive`. Add fields, then verify the whole suite still compiles+passes. Edit `AppState.kt` — add after `availableSites`:
```kotlin
    /** Site voice: WS connected (UI status dot). */
    val siteVoiceConnected = MutableStateFlow(false)

    /** Site voice owns the mic/speaker right now (recording/sending/playing). */
    val siteVoiceActive = MutableStateFlow(false)

    /** Ask owns the mic/speaker right now — status mirror written by AskManager, read by
     *  SiteVoiceCore for one-talk-at-a-time mutual exclusion. Ask's own behavior is unchanged. */
    val askActive = MutableStateFlow(false)

    /** Recent inbound Site-voice clips (last N, replayable). Written by SiteVoiceManager. */
    val siteVoiceInbox = MutableStateFlow<List<com.benzn.grandtime.sitevoice.VoiceClip>>(emptyList())

    /** UI → service: replay a recent inbox clip by its s3Key. */
    val siteVoiceReplayRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
```
- [ ] Edit `AskManager.dispatch` to mirror Ask activity (add the status write after execution):
```kotlin
    private fun dispatch(decide: () -> List<AskCommand>) {
        scope.launch {
            execute(decide())
            AppState.askActive.value = core.state != AskState.Idle
        }
    }
```
- [ ] Run the full suite (expected PASS — no test regressions; new fields compile against `SiteVoiceCoreTest`/`VoiceApiClientTest`): `./gradlew testProdDebugUnitTest`
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/core/AppState.kt app/src/main/java/com/benzn/grandtime/ask/AskManager.kt` + `git commit -m "Add Site-voice AppState signals and AskManager activity mirror"`

---

### Task 8 — Inbound cue asset + `AskSounds.received()`

**Files:**
- Create `app/src/main/res/raw/voice_received.wav` (short, distinct cue tone — bundled, not downloaded)
- Modify `app/src/main/java/com/benzn/grandtime/ask/AskSounds.kt`

**Interfaces:**
- Produces: `fun AskSounds.received()` — plays `R.raw.voice_received` via the existing `SoundPool`.
- Consumes: `R.raw.voice_received`.

This task is asset + SoundPool (JVM-untestable). No unit test; device-verified in Task 14.

Steps:
- [ ] Add `app/src/main/res/raw/voice_received.wav` — a short (~300 ms) tone distinct from `ask_listening`/`ask_thinking`/`ask_error` (e.g. a two-note rising chime). Keep it small; commit as a binary asset.
- [ ] Edit `AskSounds.kt` to load + play it:
```kotlin
    private val received = pool.load(context, R.raw.voice_received, 1)
    // ...
    fun received() { pool.play(received, 1f, 1f, 1, 0, 1f) }
```
- [ ] Build the flavor to confirm the resource resolves (expected: BUILD SUCCESSFUL): `./gradlew assembleDevDebug`
- [ ] Commit: `git add app/src/main/res/raw/voice_received.wav app/src/main/java/com/benzn/grandtime/ask/AskSounds.kt` + `git commit -m "Add voice_received cue tone and AskSounds.received()"`

---

### Task 9 — Build flavor config (WS URL + enable flag)

**Files:**
- Modify `app/build.gradle.kts` (flavors, ~lines 24-37)

**Interfaces:**
- Produces (BuildConfig): `SITE_VOICE_WS_URL: String` and `SITE_VOICE_ENABLED: Boolean`, per flavor.
- Consumes: nothing (config only).

Values: `dev` → test WS stage (`SITE_VOICE_ENABLED = true`); `prod` → prod WS stage but `SITE_VOICE_ENABLED = false` until the backend `PROD_ENABLE_SITE_VOICE` flip, then flip to `true` for the shipping build. WS URLs are `wss://<api-id>.execute-api.ap-southeast-2.amazonaws.com/<stage>` — fill the real ids after the backend deploys the WebSocket API (placeholders below).

Steps:
- [ ] Edit the `prod` flavor block to add:
```kotlin
            // Site voice WebSocket API (prod). Dark-launched: disabled until PROD_ENABLE_SITE_VOICE flip.
            buildConfigField("String", "SITE_VOICE_WS_URL", "\"wss://REPLACE_PROD_WS_ID.execute-api.ap-southeast-2.amazonaws.com/prod\"")
            buildConfigField("boolean", "SITE_VOICE_ENABLED", "false")
```
- [ ] Edit the `dev` flavor block to add:
```kotlin
            // Site voice WebSocket API (fieldsight-test). Enabled for soak testing.
            buildConfigField("String", "SITE_VOICE_WS_URL", "\"wss://REPLACE_TEST_WS_ID.execute-api.ap-southeast-2.amazonaws.com/prod\"")
            buildConfigField("boolean", "SITE_VOICE_ENABLED", "true")
```
- [ ] Build both flavors to confirm BuildConfig fields generate (expected SUCCESSFUL): `./gradlew assembleDevDebug assembleProdDebug`
- [ ] Commit: `git add app/build.gradle.kts` + `git commit -m "Add SITE_VOICE_WS_URL and SITE_VOICE_ENABLED build config per flavor"`

---

### Task 10 — `VoiceWsClient` (OkHttp WebSocket shell) — on-device

**Files:**
- Create `app/src/main/java/com/benzn/grandtime/sitevoice/VoiceWsClient.kt`
- Modify `app/src/main/AndroidManifest.xml` (add `ACCESS_NETWORK_STATE`)

**Interfaces:**
- Produces: `class VoiceWsClient(wsUrl: String, tokenProvider: suspend () -> String?, scope: CoroutineScope, connectivity: ConnectivityManager, onInbound: (InboundVoice) -> Unit, onConnected: () -> Unit, probe: (String) -> Unit)` with `fun start()`, `fun stop()`, `fun sendVoice(siteId: String, s3Key: String, durationS: Int): Boolean`, and it writes `AppState.siteVoiceConnected`.
- Consumes: `okhttp3.OkHttpClient` (`.pingInterval` + `.newWebSocket`), `okhttp3.WebSocketListener`, `WsBackoff`, `WsMessages`, `android.net.ConnectivityManager`.

**No unit test** (real socket) — pure logic already covered by `WsBackoffTest` + `WsMessagesTest`. Device-verified in Task 14. Show the real code:

Steps:
- [ ] Add to `AndroidManifest.xml` (next to `INTERNET`): `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`
- [ ] Implement `VoiceWsClient.kt`:
```kotlin
package com.benzn.grandtime.sitevoice

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import com.benzn.grandtime.core.AppState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Persistent Site-voice WebSocket, held open by CoreService (the FGS). FIRST on-device WS in this
 * repo — device soak required (Task 14). Auth: a fresh Cognito idToken in the handshake
 * `Authorization` header (raw, no Bearer). Reconnect: exponential backoff ([WsBackoff]) on drop,
 * plus an immediate reconnect when the default network comes back. Keepalive: OkHttp pingInterval.
 * Inbound text frames are parsed by [WsMessages] and handed to [onInbound]; [onConnected] fires each
 * time the socket opens (drives offline backfill).
 */
class VoiceWsClient(
    private val wsUrl: String,
    private val tokenProvider: suspend () -> String?,
    private val scope: CoroutineScope,
    private val connectivity: ConnectivityManager,
    private val onInbound: (InboundVoice) -> Unit,
    private val onConnected: () -> Unit,
    private val probe: (String) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // keepalive; also detects half-open sockets
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    private var attempt = 0
    private var connectJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (running && socket == null) { attempt = 0; scheduleConnect(immediate = true) }
        }
    }

    fun start() {
        if (running) return
        running = true
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().build(), networkCallback,
            )
        }
        scheduleConnect(immediate = true)
    }

    fun stop() {
        running = false
        connectJob?.cancel(); connectJob = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        socket?.close(1000, "stop"); socket = null
        AppState.siteVoiceConnected.value = false
    }

    /** Enqueue a sendVoice frame. Returns false if the socket isn't currently open. */
    fun sendVoice(siteId: String, s3Key: String, durationS: Int): Boolean {
        val s = socket ?: return false
        return s.send(WsMessages.sendVoiceFrame(siteId, s3Key, durationS))
    }

    private fun scheduleConnect(immediate: Boolean) {
        connectJob?.cancel()
        connectJob = scope.launch {
            if (!immediate) delay(WsBackoff.delayMillis(attempt))
            if (!running) return@launch
            val token = tokenProvider() ?: run {
                probe("site-voice: no idToken; retry")
                attempt++; scheduleConnect(immediate = false); return@launch
            }
            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", token) // raw idToken, no Bearer
                .build()
            socket = client.newWebSocket(request, listener)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            AppState.siteVoiceConnected.value = true
            probe("site-voice: connected")
            onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            WsMessages.parseInbound(text)?.let(onInbound)
                ?: probe("site-voice: unparsable frame")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDropped("closed $code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onDropped("failure ${t.message}")
        }

        private fun onDropped(why: String) {
            socket = null
            AppState.siteVoiceConnected.value = false
            probe("site-voice: dropped ($why)")
            if (running) { attempt++; scheduleConnect(immediate = false) }
        }
    }
}
```
- [ ] Build to confirm it compiles against OkHttp 4.12.0 (expected SUCCESSFUL): `./gradlew assembleDevDebug`
- [ ] On-device (dev flavor, test WS stage): install, connect, confirm probe `site-voice: connected`; kill the network → confirm `dropped` then reconnect with growing backoff; restore network → immediate reconnect. (Formal soak in Task 14.)
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/sitevoice/VoiceWsClient.kt app/src/main/AndroidManifest.xml` + `git commit -m "Add VoiceWsClient persistent WebSocket with backoff and network-aware reconnect"`

---

### Task 11 — `SiteVoiceManager` (orchestration) — on-device

**Files:**
- Create `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt`

**Interfaces:**
- Produces: `class SiteVoiceManager(context: Context, scope: CoroutineScope, auth: AuthManager, apiBaseUrl: String, wsUrl: String, connectivity: ConnectivityManager, probe: (String) -> Unit = {})` with `fun start()`, `fun onSosDown()`, `fun onSosUp()`, `fun shutdown()`. (Replay is driven by `AppState.siteVoiceReplayRequests`, collected in `CoreService`, calling an internal `replay(s3Key)`.)
- Consumes: `SiteVoiceCore`, `SosKeySource` (wired in `CoreService`), `AskRecorder`, `AskPlayer`, `AskSounds`, `VoiceApiClient`, `VoiceWsClient`, `LastSeenStore`, `AuthManager.freshIdToken()`, `AppState.selectedSite`/`captureState`/`askActive`/`siteVoiceActive`/`siteVoiceInbox`.

**No unit test** (Android audio + WS + I/O). All decisions delegate to `SiteVoiceCore` (tested). Mirrors `AskManager`'s single-dispatcher serialization (`scope` = `Dispatchers.Main.immediate`, `SiteVoiceCore.onXxx()` non-suspending → atomic). Device-verified in Task 14.

Steps:
- [ ] Implement `SiteVoiceManager.kt`:
```kotlin
package com.benzn.grandtime.sitevoice

import android.content.Context
import android.net.ConnectivityManager
import com.benzn.grandtime.ask.AskPlayer
import com.benzn.grandtime.ask.AskRecorder
import com.benzn.grandtime.ask.AskSounds
import com.benzn.grandtime.auth.AuthManager
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.voiceDataStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates Site voice: SosKeySource down/up -> SiteVoiceCore -> executors (AskRecorder,
 * VoiceApiClient, VoiceWsClient, AskPlayer, AskSounds). Sibling to AskManager in CoreService.
 * Reuses AskRecorder (-> AudioRecorder, WAV 16 kHz mono) and AskPlayer; adds the received cue.
 * Mic arbitration reads AppState.captureState (video) + AppState.askActive (Ask); publishes
 * AppState.siteVoiceActive. Offline backfill on (re)connect via LastSeenStore + VoiceApiClient.
 */
class SiteVoiceManager(
    context: Context,
    private val scope: CoroutineScope,
    private val auth: AuthManager,
    apiBaseUrl: String,
    wsUrl: String,
    connectivity: ConnectivityManager,
    private val probe: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val core = SiteVoiceCore()
    private val cacheDir = File(appContext.cacheDir, "sitevoice")
    private val recorder = AskRecorder(appContext, cacheDir)
    private val player = AskPlayer(cacheDir)
    private val sounds = AskSounds(appContext)
    private val api = VoiceApiClient(apiBaseUrl)
    private val lastSeenStore = LastSeenStore(appContext.voiceDataStore)
    private var capTimer: Job? = null

    private val ws = VoiceWsClient(
        wsUrl = wsUrl,
        tokenProvider = { auth.freshIdToken() },
        scope = scope,
        connectivity = connectivity,
        onInbound = { inbound -> scope.launch { handleInbound(inbound) } },
        onConnected = { scope.launch { backfill() } },
        probe = probe,
    )

    private val videoRecording: Boolean
        get() = AppState.captureState.value is CaptureState.RecordingVideo
    private val askActive: Boolean get() = AppState.askActive.value

    fun start() { cacheDir.mkdirs(); ws.start() }

    fun onSosDown() = dispatch { core.onSosDown(videoRecording, askActive) }
    fun onSosUp() = dispatch { core.onSosUp() }

    /** Serialize the SiteVoiceCore mutation AND its command execution on [scope]'s single
     *  dispatcher — mirrors AskManager.dispatch (one talk at a time, no double-send). */
    private fun dispatch(decide: () -> List<SiteVoiceCommand>) {
        scope.launch {
            execute(decide())
            AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
        }
    }

    private suspend fun execute(commands: List<SiteVoiceCommand>) {
        for (cmd in commands) when (cmd) {
            SiteVoiceCommand.PlayTalkStartCue -> { probe("site-voice: talk"); sounds.listening() }
            SiteVoiceCommand.PlayBusyCue -> { probe("site-voice: busy"); sounds.error() }
            SiteVoiceCommand.PlayErrorCue -> { probe("site-voice: error"); sounds.error() }
            SiteVoiceCommand.StartRecording -> if (!recorder.start()) { fail(); return }
            SiteVoiceCommand.StopRecording -> { /* clip read in UploadAndSend */ }
            SiteVoiceCommand.ArmCapTimer -> armCap()
            SiteVoiceCommand.CancelCapTimer -> { capTimer?.cancel(); capTimer = null }
            SiteVoiceCommand.UploadAndSend -> uploadAndSend()
            is SiteVoiceCommand.PlayClip -> playClip(cmd.clip)
        }
    }

    private fun armCap() {
        capTimer?.cancel()
        capTimer = scope.launch {
            delay(CAP_MILLIS)
            execute(core.onCapReached())
            AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
        }
    }

    private suspend fun uploadAndSend() {
        val clip = recorder.stop()
        if (clip == null) { fail(); return }
        val siteId = AppState.selectedSite.value?.id
        val token = auth.freshIdToken()
        if (siteId == null || token == null) { clip.delete(); sendResult(false); return }
        val size = clip.length()
        val durationS = WsMessages.wavDurationSeconds(size)
        val startedAt = Instant.now().toString()
        val result = withContext(Dispatchers.IO) {
            val up = api.uploadUrl(
                token,
                VoiceUploadReq(siteId, clip.name, "audio/wav", startedAt, durationS, size),
            )
            if (up !is VoiceApiClient.UploadUrlResult.Ok) return@withContext false
            if (!api.putFile(up.uploadUrl, "audio/wav", clip)) return@withContext false
            ws.sendVoice(siteId, up.s3Key, durationS)
        }
        clip.delete()
        sendResult(result)
    }

    private suspend fun sendResult(ok: Boolean) {
        execute(core.onSendResult(ok))
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }

    private suspend fun handleInbound(inbound: InboundVoice) {
        val clip = download(inbound.s3Key, inbound.senderUserId, inbound.createdAt, inbound.durationS ?: 0)
        if (clip == null) { probe("site-voice: download failed"); return }
        addToInbox(clip)
        inbound.createdAt.takeIf { it.isNotBlank() }?.let { lastSeenStore.set(it) }
        execute(core.onClipReady(clip))
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }

    private suspend fun backfill() {
        val siteId = AppState.selectedSite.value?.id ?: return
        val token = auth.freshIdToken() ?: return
        val since = lastSeenStore.lastSeen.let { flow -> kotlinx.coroutines.flow.firstOrNull(flow) }
        val items = withContext(Dispatchers.IO) { api.backfill(token, siteId, since) }
        for (it in items.sortedBy { it.createdAt }) {
            val clip = download(it.s3Key, it.senderUserId, it.createdAt, it.durationS ?: 0) ?: continue
            addToInbox(clip)
            it.createdAt.takeIf { c -> c.isNotBlank() }?.let { c -> lastSeenStore.set(c) }
            execute(core.onClipReady(clip))
            AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
        }
    }

    /** Presign a GET, download the clip bytes to a temp file, wrap in a VoiceClip. */
    private suspend fun download(s3Key: String, sender: String, createdAt: String, durationS: Int): VoiceClip? {
        val token = auth.freshIdToken() ?: return null
        return withContext(Dispatchers.IO) {
            val url = api.downloadUrl(token, s3Key) ?: return@withContext null
            val bytes = runCatching { com.benzn.grandtime.net.RealSitesHttp() } // placeholder note below
            // Download the raw object via the presigned URL (no auth header on S3 GET):
            val file = File(cacheDir, "in_${System.currentTimeMillis()}.wav")
            val ok = runCatching {
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).get().build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        file.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
                        true
                    }
            }.getOrDefault(false)
            if (ok && file.length() > 0) VoiceClip(file, s3Key, sender, createdAt, durationS) else { file.delete(); null }
        }
    }

    private suspend fun playClip(clip: VoiceClip) {
        val bytes = runCatching { clip.file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) { onPlaybackDone(); return }
        probe("site-voice: playing from ${clip.senderUserId}")
        sounds.received()
        player.play(bytes) { scope.launch { onPlaybackDone() } }
    }

    private suspend fun onPlaybackDone() {
        execute(core.onPlaybackDone())
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }

    /** Replay a clip already in the inbox (routed through the core so it queues if busy). */
    fun replay(s3Key: String) = dispatch {
        val clip = AppState.siteVoiceInbox.value.firstOrNull { it.s3Key == s3Key }
        if (clip != null) core.onClipReady(clip) else emptyList()
    }

    private fun addToInbox(clip: VoiceClip) {
        AppState.siteVoiceInbox.value = (listOf(clip) + AppState.siteVoiceInbox.value).take(INBOX_LIMIT)
    }

    private suspend fun fail() {
        recorder.discard()
        capTimer?.cancel(); capTimer = null
        execute(core.onError())
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }

    fun shutdown() {
        capTimer?.cancel()
        ws.stop()
        recorder.discard()
        player.release()
        sounds.release()
    }

    companion object {
        const val CAP_MILLIS = 15_000L
        const val INBOX_LIMIT = 20
    }
}
```
> Implementation note for the executor: the `download` body above sketches the presigned-GET download; during implementation, extract the raw OkHttp GET into a tiny private helper (S3 GET carries NO `Authorization` header — the URL is already signed). The `runCatching { RealSitesHttp() }` placeholder line is illustrative only and MUST be removed — replace with the direct `OkHttpClient().newCall(...)` shown. Reuse a single `OkHttpClient` field rather than constructing one per download.
- [ ] Build to confirm it compiles (expected SUCCESSFUL): `./gradlew assembleDevDebug` (fix the `download` helper per the note; ensure `kotlinx.coroutines.flow.firstOrNull` import is correct).
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt` + `git commit -m "Add SiteVoiceManager orchestrator (record/upload/send, receive/play, backfill, inbox)"`

---

### Task 12 — Wire `SosKeySource` + `SiteVoiceManager` into `CoreService`

**Files:**
- Modify `app/src/main/java/com/benzn/grandtime/service/CoreService.kt` — fields (~lines 64-73), `startPipeline()` (add beside the Ask wiring ~lines 281-314), `onDestroy()` (~lines 374-383).

**Interfaces:**
- Consumes: `SosKeySource`, `SiteVoiceManager`, `BuildConfig.SITE_VOICE_WS_URL`, `BuildConfig.SITE_VOICE_ENABLED`, `AppState.siteVoiceReplayRequests`, `getSystemService(ConnectivityManager::class.java)`.
- Produces: a live Site-voice channel inside the FGS lifecycle (gated by `SITE_VOICE_ENABLED`).

**No unit test** (service). Device-verified in Task 14.

Steps:
- [ ] Add fields beside `askManager`/`pttSource`:
```kotlin
    private var siteVoiceManager: SiteVoiceManager? = null
    private var sosSource: com.benzn.grandtime.sitevoice.SosKeySource? = null
```
- [ ] In `startPipeline()`, after the Ask/PTT wiring block (after `pttSource = ptt` + its collector), add (guarded by the flavor flag):
```kotlin
        if (BuildConfig.SITE_VOICE_ENABLED) {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val siteVoice = SiteVoiceManager(
                context = this,
                scope = lifecycleScope,
                auth = auth,
                apiBaseUrl = BuildConfig.ORG_API_BASE_URL,
                wsUrl = BuildConfig.SITE_VOICE_WS_URL,
                connectivity = connectivity,
                probe = ::probe,
            )
            siteVoiceManager = siteVoice
            val sos = com.benzn.grandtime.sitevoice.SosKeySource(this)
            sosSource = sos
            lifecycleScope.launch {
                sos.events.collect { dir ->
                    probe("sos ${dir.name}")
                    when (dir) {
                        com.benzn.grandtime.sitevoice.SosDirection.DOWN -> siteVoice.onSosDown()
                        com.benzn.grandtime.sitevoice.SosDirection.UP -> siteVoice.onSosUp()
                    }
                }
            }
            lifecycleScope.launch {
                AppState.siteVoiceReplayRequests.collect { siteVoice.replay(it) }
            }
            siteVoice.start()  // opens the persistent WS
        }
```
- [ ] Add `sos.start()` alongside the existing `f2sp.start()` / `ptt.start()` (only if `SITE_VOICE_ENABLED`); simplest is to move `sosSource?.start()` right after `ptt.start()`. (Keep the "subscribe before start" ordering — the `sos.events.collect` above is registered before `start()`.)
- [ ] In `onDestroy()`, add beside `pttSource?.stop()` / `askManager?.shutdown()`:
```kotlin
        sosSource?.stop()
        siteVoiceManager?.shutdown()
```
- [ ] Add the import `import android.net.ConnectivityManager` and `import com.benzn.grandtime.sitevoice.SiteVoiceManager`.
- [ ] Build (expected SUCCESSFUL) + run the full unit suite to ensure no regressions: `./gradlew assembleDevDebug testProdDebugUnitTest`
- [ ] On-device (dev flavor): hold SOS → hear talk cue + record; release → clip uploads + `sendVoice`; a second device on the same site hears it with the received cue. (Formal soak in Task 14.)
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/service/CoreService.kt` + `git commit -m "Wire SosKeySource and SiteVoiceManager into CoreService (flag-gated)"`

---

### Task 13 — Site-voice UI (status dot + inbox + replay)

**Files:**
- Modify `app/src/main/java/com/benzn/grandtime/ui/HomeScreen.kt` (add a "Site voice" `FsCard`)

**Interfaces:**
- Consumes: `AppState.siteVoiceConnected`, `AppState.siteVoiceInbox`, `AppState.siteVoiceReplayRequests`, `AppState.loginState`.
- Produces: a minimal status card — a connection dot (green connected / grey disconnected) + the last N inbox clips with a replay button each that emits `AppState.siteVoiceReplayRequests.tryEmit(clip.s3Key)`.

**No unit test** (Compose). Device-verified in Task 14. Mirror the existing `FsCard` + status-dot pattern (`HomeScreen` "Device" card, lines 125-140) and the `collectAsStateWithLifecycle` usage.

Steps:
- [ ] Add a composable under the "Today's uploads" card, shown only when `login is LoginState.LoggedIn`:
```kotlin
        if (login is LoginState.LoggedIn) {
            val connected by AppState.siteVoiceConnected.collectAsStateWithLifecycle()
            val inbox by AppState.siteVoiceInbox.collectAsStateWithLifecycle()
            Spacer(Modifier.height(12.dp))
            FsCard {
                FsCardTitle("Site voice")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (connected) fs.successDot else MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (connected) "Connected" else "Offline",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (inbox.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No recent messages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    inbox.take(5).forEach { clip ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "From ${clip.senderUserId.take(8)} · ${clip.durationS}s",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { AppState.siteVoiceReplayRequests.tryEmit(clip.s3Key) }) {
                                Text("Replay")
                            }
                        }
                    }
                }
            }
        }
```
- [ ] Build (expected SUCCESSFUL): `./gradlew assembleDevDebug`
- [ ] On-device: verify the dot tracks WS connect/disconnect, the inbox lists recent clips, and Replay re-plays through the speaker.
- [ ] Commit: `git add app/src/main/java/com/benzn/grandtime/ui/HomeScreen.kt` + `git commit -m "Add Site-voice status card with connection dot and replayable inbox"`

---

### Task 14 — On-device multi-device soak + backfill + battery verification

**Files:** none (verification only).

**Depends on:** all prior tasks; requires the backend WS API live on fieldsight-test (dev flavor) with two Cognito users, both members of one test site, that site selected on both devices.

Steps (all on real F2SP hardware — camera/GL/GPS/WS are JVM-untestable):
- [ ] Build + install dev flavor on two devices: `./gradlew assembleDevDebug` then `adb install -r` each.
- [ ] **Send/receive:** Device A holds SOS → talk cue + LED unaffected; release → clip uploads + `sendVoice`. Device B hears it within ~2-5 s with the received cue. Confirm A does NOT hear its own message (fanout exclusion).
- [ ] **Mic arbitration:** while Device A is recording video, SOS → busy cue, no send. While Ask (PTT) is active, SOS → busy cue. While Site voice is talking, PTT → Ask refuses/contends safely. An inbound clip during a talk is queued and plays right after.
- [ ] **Cap:** hold SOS > 15 s → auto-stops and sends at the cap.
- [ ] **WS liveness (the #1 risk):** background the app, screen off, wait through Doze; confirm the FGS keeps the socket open (probe `connected`, dot green). Toggle airplane mode → confirm `dropped` then exponential-backoff reconnect; restore → immediate reconnect via the network callback.
- [ ] **Offline backfill:** kill the app on Device B while A sends 2-3 clips; relaunch B → on reconnect it backfills the missed clips (plays/queues) and advances `lastSeenTs` (no re-play of already-seen clips on a subsequent reconnect).
- [ ] **Battery:** overnight soak with the connection held; confirm drain is acceptable given daily charging; tune `pingInterval` if needed.
- [ ] **Isolation smoke:** confirm a Site-voice clip never appears in the recordings/reports pipeline (it lands under `voice/`, not `users/*/audio/*`) — spot-check S3 + that no transcript/report is produced. (Backend guarantees this; this is an app-side sanity check.)
- [ ] Record results in the SDD ledger; when green, proceed to `finishing-a-development-branch` (merge `main` + tag) and coordinate the backend `PROD_ENABLE_SITE_VOICE` flip + prod flavor `SITE_VOICE_ENABLED=true` ship.

---

## Notes on decisions already made (do not re-open)
- SOS key repurposed as hold-to-talk via a raw `SosKeySource`; PTT/Ask untouched; `lolaage.sos` excluded from `KEY_ACTION_PREFIXES`.
- Voice is off-the-record: dedicated `voice/` prefix + `voice_messages` (backend); the app never routes voice through `RecordingsApiClient`/the recordings flow.
- Backend is built separately and dark-launched behind `PROD_ENABLE_SITE_VOICE`; the app mirrors it with `SITE_VOICE_ENABLED` per flavor.
