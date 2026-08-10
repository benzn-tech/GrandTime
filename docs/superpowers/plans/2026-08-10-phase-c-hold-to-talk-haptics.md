# Phase C — Hold-to-talk haptics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give SP-Ask and Site-voice the vibration feedback they have never had, using the vibration vocabulary the device already speaks.

**Architecture:** One shared pattern table (`hardware/Haptics.kt`) becomes the single definition of what a buzz means; `CaptureManager` is repointed at it so the new refusal buzz is byte-identical to the existing one. `AskCore` and `SiteVoiceCore` gain a `Vibrate` command variant and emit it from their existing decision points — all pure, all JVM-testable. The two managers grow one executor branch each.

**Tech Stack:** Kotlin, Android (`VibrationEffect.createWaveform`), JUnit + kotlinx-coroutines-test, Gradle.

## Global Constraints

- Worktree: `C:/Users/camil/Dropbox/worktrees/gt-mic-feedback`, branch `feat/mic-health-and-haptics`, based on `origin/main` (303be71). **Never touch `C:/Users/camil/Dropbox/GrandTime`** — it is parked on another session's branch.
- Test command: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest`
- Baseline before any change: **499 tests, 0 failures.** Every task must leave it green and growing.
- Vibration vocabulary, fixed by what `CaptureCore` already emits and must not be contradicted:
  - `SHORT` (`longArrayOf(0, 80)`) — a normal action was accepted
  - `DOUBLE_SHORT` (`longArrayOf(0, 60, 80, 60)`) — **refused or failed**
  - `LONG` (`longArrayOf(0, 350)`) — new: an operation the operator started has finished
- The cores stay free of Android imports. `VibePattern` is a bare enum for exactly this reason.
- Do not refactor anything not named in a task.

---

### Task 1: The pattern table

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/hardware/Haptics.kt`
- Create: `app/src/test/java/com/benzn/grandtime/hardware/HapticsPatternTest.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt` (the `vibrate` function near line 803)

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class VibePattern { SHORT, DOUBLE_SHORT, LONG }`, `fun waveformFor(pattern: VibePattern): LongArray`, and `class Haptics(context: Context) { fun play(pattern: VibePattern) }` — all in package `com.benzn.grandtime.hardware`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/benzn/grandtime/hardware/HapticsPatternTest.kt`:

```kotlin
package com.benzn.grandtime.hardware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsPatternTest {

    // These two must stay byte-identical to what CaptureManager has always emitted:
    // an operator already reads one buzz as "accepted" and two as "refused", and a
    // refusal that feels different from every other refusal teaches nothing.
    @Test
    fun `short is the existing single-buzz waveform`() {
        assertArrayEquals(longArrayOf(0, 80), waveformFor(VibePattern.SHORT))
    }

    @Test
    fun `double short is the existing refusal waveform`() {
        assertArrayEquals(longArrayOf(0, 60, 80, 60), waveformFor(VibePattern.DOUBLE_SHORT))
    }

    @Test
    fun `long is one continuous buzz, not a count`() {
        val w = waveformFor(VibePattern.LONG)
        assertArrayEquals(longArrayOf(0, 350), w)
    }

    // Countable patterns are unreliable through a closed pocket, so LONG must be
    // told apart by duration rather than by counting pulses.
    @Test
    fun `long is at least three times the short buzz`() {
        val short = waveformFor(VibePattern.SHORT).last()
        val long = waveformFor(VibePattern.LONG).last()
        assertTrue("long=$long short=$short", long >= short * 3)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:/Users/camil/Dropbox/worktrees/gt-mic-feedback
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest --tests "com.benzn.grandtime.hardware.HapticsPatternTest"
```
Expected: FAIL — compilation error, `waveformFor` and `VibePattern` unresolved.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/benzn/grandtime/hardware/Haptics.kt`:

```kotlin
package com.benzn.grandtime.hardware

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * What a buzz means on this device. The vocabulary is not new — [SHORT] and
 * [DOUBLE_SHORT] are what CaptureCore has always emitted, and an operator already
 * reads two buzzes as "refused". [LONG] is the only addition, for an operation that
 * has finished on its own some seconds after the key was released.
 *
 * A bare enum with no Android imports, so the pure decision cores can name a pattern
 * without taking a platform dependency.
 */
enum class VibePattern { SHORT, DOUBLE_SHORT, LONG }

/** The waveform for a pattern. Pure, so the table itself is unit-testable. */
fun waveformFor(pattern: VibePattern): LongArray = when (pattern) {
    VibePattern.SHORT -> longArrayOf(0, 80)
    VibePattern.DOUBLE_SHORT -> longArrayOf(0, 60, 80, 60)
    // Duration, not a pulse count: three buzzes and two buzzes are not reliably
    // distinguishable through a jacket, but long and short are.
    VibePattern.LONG -> longArrayOf(0, 350)
}

/** Plays a [VibePattern]. Silently does nothing on a device with no vibrator. */
class Haptics(private val context: Context) {
    fun play(pattern: VibePattern) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(waveformFor(pattern), -1))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest --tests "com.benzn.grandtime.hardware.HapticsPatternTest"
```
Expected: PASS, 4 tests.

- [ ] **Step 5: Repoint CaptureManager at the shared table**

In `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt`, replace the body of `vibrate` (near line 803):

```kotlin
    private fun vibrate(times: Int) =
        haptics.play(if (times == 1) VibePattern.SHORT else VibePattern.DOUBLE_SHORT)
```

Add the field near the other private members (beside `handoverActive` at line 85):

```kotlin
    private val haptics = com.benzn.grandtime.hardware.Haptics(context)
```

Add the import beside the other `com.benzn.grandtime` imports:

```kotlin
import com.benzn.grandtime.hardware.VibePattern
```

Then delete the now-unused `android.os.VibrationEffect` and `android.os.Vibrator` imports (lines 12–13).

There is one definition of a buzz after this, which is what makes "the refusal feels like every other refusal" true rather than aspirational.

- [ ] **Step 6: Run the whole suite**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest
```
Expected: PASS, 503 tests (499 baseline + 4), 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/hardware/Haptics.kt \
        app/src/test/java/com/benzn/grandtime/hardware/HapticsPatternTest.kt \
        app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt
git commit -m "feat(haptics): one definition of what a buzz means

SHORT and DOUBLE_SHORT are lifted verbatim from CaptureManager so the
vocabulary an operator already knows is preserved exactly; LONG is new and is
distinguished by duration rather than a pulse count, which is not countable
through a pocket."
```

---

### Task 2: Ask emits vibrations

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ask/AskCore.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/ask/AskManager.kt` (executor `when`, around lines 66–80)
- Test: `app/src/test/java/com/benzn/grandtime/ask/AskCoreTest.kt`

**Interfaces:**
- Consumes: `VibePattern`, `Haptics` from Task 1.
- Produces: `AskCommand.Vibrate(val pattern: VibePattern)`. `AskCore.onPlaybackDone()` now returns a non-empty list — previously always `emptyList()`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/benzn/grandtime/ask/AskCoreTest.kt`:

```kotlin
    @Test
    fun `activation buzzes once`() {
        val core = AskCore()
        val cmds = core.onPttDown(videoRecording = false, siteVoiceActive = false)
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.SHORT)))
    }

    // Two buzzes already means "refused" everywhere else on this device.
    @Test
    fun `refusal buzzes twice`() {
        val core = AskCore()
        val cmds = core.onPttDown(videoRecording = true, siteVoiceActive = false)
        assertTrue(cmds.contains(AskCommand.PlayBusyCue))
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)))
    }

    @Test
    fun `finishing buzzes long`() {
        val core = AskCore()
        core.onPttDown(videoRecording = false, siteVoiceActive = false)
        core.onPttUp()
        core.onAnswer("aGk=")
        val cmds = core.onPlaybackDone()
        assertEquals(listOf(AskCommand.Vibrate(VibePattern.LONG)), cmds)
    }

    // The long buzz says "this is over". A playback-done that arrives when nothing
    // was playing must not fire one.
    @Test
    fun `playback done while idle buzzes nothing`() {
        val core = AskCore()
        assertEquals(emptyList<AskCommand>(), core.onPlaybackDone())
    }

    @Test
    fun `error buzzes twice, like every other failure`() {
        val core = AskCore()
        core.onPttDown(videoRecording = false, siteVoiceActive = false)
        val cmds = core.onError()
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.DOUBLE_SHORT)))
    }

    // The keymap-routed tap has no HoldToTalkGate in front of it, so it is a
    // separate path into the same decision — it must feel the same.
    @Test
    fun `discrete tap buzzes once on activation`() {
        val core = AskCore()
        val cmds = core.onDiscreteAsk(videoRecording = false, siteVoiceActive = false)
        assertTrue(cmds.contains(AskCommand.Vibrate(VibePattern.SHORT)))
    }
```

Add to that file's imports:

```kotlin
import com.benzn.grandtime.hardware.VibePattern
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest --tests "com.benzn.grandtime.ask.AskCoreTest"
```
Expected: FAIL — compilation error, `AskCommand.Vibrate` unresolved.

- [ ] **Step 3: Add the command variant and the emissions**

In `AskCore.kt`, add the import:

```kotlin
import com.benzn.grandtime.hardware.VibePattern
```

Add to the `AskCommand` interface, after `PlayAnswer`:

```kotlin
    data class Vibrate(val pattern: VibePattern) : AskCommand
```

Replace `onPttDown`:

```kotlin
    fun onPttDown(videoRecording: Boolean, siteVoiceActive: Boolean = false): List<AskCommand> = when (state) {
        AskState.Idle ->
            if (videoRecording || siteVoiceActive) {
                // Refusal was audible-only, which is the one channel a chest-worn
                // device cannot rely on. Two buzzes is what refusal already means here.
                listOf(AskCommand.PlayBusyCue, AskCommand.Vibrate(VibePattern.DOUBLE_SHORT))
            } else {
                state = AskState.Listening
                listOf(AskCommand.PlayListeningCue, AskCommand.Vibrate(VibePattern.SHORT),
                       AskCommand.StartRecording, AskCommand.ArmCapTimer)
            }
        else -> emptyList()  // ignore re-entrant down mid-ask
    }
```

Replace `onError`:

```kotlin
    fun onError(): List<AskCommand> {
        state = AskState.Idle
        return listOf(AskCommand.CancelCapTimer, AskCommand.PlayErrorCue,
                      AskCommand.Vibrate(VibePattern.DOUBLE_SHORT))
    }
```

Replace `onPlaybackDone`:

```kotlin
    /** The answer has played and the microphone is back. This arrives unprompted,
     *  seconds after the key was released, so it is the one moment the operator has
     *  no other way to learn about. */
    fun onPlaybackDone(): List<AskCommand> {
        if (state != AskState.Playing) return emptyList()
        state = AskState.Idle
        return listOf(AskCommand.Vibrate(VibePattern.LONG))
    }
```

- [ ] **Step 4: Handle the command in AskManager**

In `AskManager.kt`, add the import:

```kotlin
import com.benzn.grandtime.hardware.Haptics
```

Add the field beside the other private members:

```kotlin
    private val haptics = Haptics(appContext)
```

Add a branch to the executor `when` (beside `AskCommand.PlayBusyCue ->` around line 70):

```kotlin
            is AskCommand.Vibrate -> haptics.play(cmd.pattern)
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest --tests "com.benzn.grandtime.ask.AskCoreTest"
```
Expected: PASS. If an existing test asserted `onPlaybackDone()` returns `emptyList()` for the Playing state, it is now wrong on purpose — update it to expect the `LONG` buzz.

- [ ] **Step 6: Run the whole suite**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest
```
Expected: PASS, 509 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/ask/AskCore.kt \
        app/src/main/java/com/benzn/grandtime/ask/AskManager.kt \
        app/src/test/java/com/benzn/grandtime/ask/AskCoreTest.kt
git commit -m "feat(ask): buzz on activation, completion and refusal

The refusal path signalled by tone alone, which is why a long-press that was
correctly refused during video recording read as a dead button."
```

---

### Task 3: Site-voice emits vibrations

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceCore.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt` (executor `when`, around lines 107–120)
- Test: `app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt`

**Interfaces:**
- Consumes: `VibePattern`, `Haptics` from Task 1.
- Produces: `SiteVoiceCommand.Vibrate(val pattern: VibePattern)`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt`:

```kotlin
    @Test
    fun `talk start buzzes once`() {
        val core = SiteVoiceCore()
        val cmds = core.onSosDown(videoRecording = false, askActive = false)
        assertTrue(cmds.contains(SiteVoiceCommand.Vibrate(VibePattern.SHORT)))
    }

    @Test
    fun `refusal while ask holds the mic buzzes twice`() {
        val core = SiteVoiceCore()
        val cmds = core.onSosDown(videoRecording = false, askActive = true)
        assertTrue(cmds.contains(SiteVoiceCommand.PlayBusyCue))
        assertTrue(cmds.contains(SiteVoiceCommand.Vibrate(VibePattern.DOUBLE_SHORT)))
    }

    // "Sent" is the outcome the operator is waiting on, and it lands seconds after
    // they let go of the key.
    @Test
    fun `a successful send buzzes long`() {
        val core = SiteVoiceCore()
        core.onSosDown(videoRecording = false, askActive = false)
        core.onSosUp()
        val cmds = core.onSendResult(ok = true)
        assertTrue(cmds.contains(SiteVoiceCommand.Vibrate(VibePattern.LONG)))
    }

    @Test
    fun `a failed send buzzes twice and keeps its error cue`() {
        val core = SiteVoiceCore()
        core.onSosDown(videoRecording = false, askActive = false)
        core.onSosUp()
        val cmds = core.onSendResult(ok = false)
        assertTrue(cmds.contains(SiteVoiceCommand.PlayErrorCue))
        assertTrue(cmds.contains(SiteVoiceCommand.Vibrate(VibePattern.DOUBLE_SHORT)))
        assertFalse(cmds.contains(SiteVoiceCommand.Vibrate(VibePattern.LONG)))
    }
```

Add to that file's imports — `assertFalse` is not among the three it currently has:

```kotlin
import com.benzn.grandtime.hardware.VibePattern
import org.junit.Assert.assertFalse
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest --tests "com.benzn.grandtime.sitevoice.SiteVoiceCoreTest"
```
Expected: FAIL — compilation error, `SiteVoiceCommand.Vibrate` unresolved.

- [ ] **Step 3: Add the command variant and the emissions**

In `SiteVoiceCore.kt`, add the import:

```kotlin
import com.benzn.grandtime.hardware.VibePattern
```

Add to the `SiteVoiceCommand` interface, after `PlayClip`:

```kotlin
    data class Vibrate(val pattern: VibePattern) : SiteVoiceCommand
```

In `onSosDown`, change the refusal branch to:

```kotlin
            if (askActive) {
                // Ask holds the mic: mutually exclusive, no-op talk
                listOf(SiteVoiceCommand.PlayBusyCue,
                       SiteVoiceCommand.Vibrate(VibePattern.DOUBLE_SHORT))
            } else {
```

and inside the accepted branch's `buildList`, add the buzz immediately after the start cue:

```kotlin
                    add(SiteVoiceCommand.PlayTalkStartCue)
                    add(SiteVoiceCommand.Vibrate(VibePattern.SHORT))
```

In `onSendResult`, change the `prefix` line to:

```kotlin
        val prefix =
            if (ok) listOf(SiteVoiceCommand.Vibrate(VibePattern.LONG))
            else listOf(SiteVoiceCommand.PlayErrorCue,
                        SiteVoiceCommand.Vibrate(VibePattern.DOUBLE_SHORT))
```

- [ ] **Step 4: Handle the command in SiteVoiceManager**

In `SiteVoiceManager.kt`, add the import:

```kotlin
import com.benzn.grandtime.hardware.Haptics
```

Add the field beside the other private members:

```kotlin
    private val haptics = Haptics(appContext)
```

Add a branch to the executor `when` (beside `SiteVoiceCommand.PlayBusyCue ->` at line 108):

```kotlin
            is SiteVoiceCommand.Vibrate -> haptics.play(cmd.pattern)
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest --tests "com.benzn.grandtime.sitevoice.SiteVoiceCoreTest"
```
Expected: PASS. Existing tests that assert on the exact command list from `onSosDown` or `onSendResult` will now see one extra element — update those expectations rather than weakening the new assertions.

- [ ] **Step 6: Run the whole suite**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest
```
Expected: PASS, 513 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceCore.kt \
        app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt \
        app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt
git commit -m "feat(site-voice): buzz on talk start, successful send and refusal"
```

---

### Task 4: Ship it and feel it

A vibration that passes a unit test and cannot be felt through a jacket has not
been delivered. This task is the actual acceptance criterion.

**Files:**
- Modify: `app/build.gradle.kts` (`versionCode`, `versionName`)
- Modify: `C:/Users/camil/Dropbox/fieldsight-dev-apk/README.txt`

- [ ] **Step 1: Bump both version fields**

In `app/build.gradle.kts`: `versionCode = 15`, `versionName = "0.6.1"`. Both, every build that leaves this machine — `0.5.9`/`13` shipped three times with different code in it and made "which build is on that device?" unanswerable.

- [ ] **Step 2: Build the prod release**

```bash
cd C:/Users/camil/Dropbox/worktrees/gt-mic-feedback
cp C:/Users/camil/Dropbox/GrandTime/local.properties local.properties   # gitignored, needed by Gradle
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleProdRelease
```

- [ ] **Step 3: Verify it is the prod flavour before it reaches the device**

The gateway is compiled in, not chosen at runtime, and a dev build silently lands
in the test bucket. Check the dex, not the filename:

```bash
unzip -p app/build/outputs/apk/prod/release/app-prod-release.apk classes.dex | grep -c ys94qy2tk0
```
Expected: at least 1. If it is 0, this is not a prod build — stop.

- [ ] **Step 4: Publish where the next person will look**

```bash
SHA=$(git rev-parse --short HEAD)
cp app/build/outputs/apk/prod/release/app-prod-release.apk \
   "C:/Users/camil/Dropbox/fieldsight-dev-apk/fieldsight-PROD-$SHA.apk"
```

Add a line to `C:/Users/camil/Dropbox/fieldsight-dev-apk/README.txt` naming the
build, what changed (Ask and Site-voice haptics), and what was verified.

- [ ] **Step 5: Install over the top**

```bash
export MSYS_NO_PATHCONV=1   # MSYS rewrites /sdcard-style args and every adb path call fails
ADB="C:/Users/camil/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" uninstall com.benzn.grandtime.dev    # one hardware key press starts BOTH flavours recording
"$ADB" install -r "C:/Users/camil/Dropbox/fieldsight-dev-apk/fieldsight-PROD-$SHA.apk"
```

`-r` overwrites and keeps the login and the recordings; both flavours share the
release certificate, so a signature mismatch is not expected. Confirm
`firstInstallTime` is unchanged:

```bash
"$ADB" shell dumpsys package com.benzn.grandtime | grep -E 'versionName|firstInstallTime|lastUpdateTime'
```
Expected: `versionName=0.6.1`, `firstInstallTime` unchanged from before.

- [ ] **Step 6: Feel each of the five signals, with the device in a pocket**

Run these with the device worn or pocketed, not in hand — the whole point is the
channel that reaches an operator who cannot look at the screen.

| Do this | Expect |
|---|---|
| Long-press the SOS key (Ask) while idle | one short buzz at activation |
| Let go, wait for the spoken answer | one long buzz when it finishes |
| Start a video recording, long-press SOS | two short buzzes, refused |
| Long-press the PTT key (Site voice) | one short buzz |
| Let the transmission send | one long buzz |

Record which of the five were unmistakable. If `LONG` is not clearly distinct from
`SHORT` through clothing, raise its duration in `waveformFor` and repeat — that
number is a guess until it has been felt.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore(release): 0.6.1 (versionCode 15) — hold-to-talk haptics"
```

---

## Verification for the whole phase

- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testProdReleaseUnitTest` → 513 tests, 0 failures
- Every one of the five signals in Task 4 Step 6 confirmed felt through clothing
- `adb shell dumpsys package com.benzn.grandtime` shows `versionName=0.6.1` and an unchanged `firstInstallTime`
- `com.benzn.grandtime.dev` is not installed

## Not in this phase

Phase A (silence detection) and Phase B (Ask borrowing the microphone during
video) get their own plans. B in particular reuses the `Vibrate` command variants
and the `micHandover` seam that this phase and `SiteVoiceCore` establish, so its
plan is written after C has landed and the vibration durations have been felt.
