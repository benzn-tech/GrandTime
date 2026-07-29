# Site-voice Mic Handover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While a video recording is active, pressing SOS lets the worker talk on Site-voice by temporarily borrowing the mic from the capture pipeline; the video keeps recording frames and its audio track stays continuous but silent for the talk window, then real audio resumes.

**Architecture:** `SiteVoiceCore` (pure FSM) stops refusing during video and instead borrows the mic via two new commands. `SiteVoiceManager` drives a new `MicHandover` interface implemented by `CaptureManager`, which delegates through `Camera2Pipeline` to the live `SegmentRecorder`. The recorder gains a non-terminal `audioHandover` flag: its audio loop zero-fills (paced silence) while the real `AudioRecord` is released/reopened, keeping the AAC codec + muxer audio track alive across the handover. Ask (PTT) additionally yields to an active Site-voice talk (I-1).

**Tech Stack:** Kotlin 2.1, Android framework only (Camera2 / MediaCodec / MediaMuxer / AudioRecord), JUnit4 for the pure-core unit tests. Coroutines on `Dispatchers.Main.immediate` (CoreService `lifecycleScope`).

## Global Constraints

- No new Gradle dependencies / native libraries (device ABI armeabi 32-bit). Android framework only.
- The AAC `audioCodec` + muxer `audioTrack` MUST stay alive across a handover — only the `AudioRecord` is stopped/released/reopened. Never re-`addTrack` after `muxer.start()`.
- The zero-fill (silence) branch MUST pace itself (sleep ~one buffer-duration) so PTS advances naturally and the encoder isn't flooded.
- `resumeAudio()` / `endMicHandover()` must NEVER crash the recording: a failed mic reopen leaves the audio track silent for the rest of the segment (logged), not a crash.
- `audioHandover` is a NEW, non-terminal `@Volatile` flag, distinct from the terminal `audioStopRequested`.
- All new CoreService wiring gated behind `BuildConfig.SITE_VOICE_ENABLED`.
- English-only dev artifacts (comments/commits/docs). Build: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug testProdDebugUnitTest`. Dropbox build-lock: rerun once on "Could not delete ...build...".

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceCore.kt` | Modify | Arbitration inversion; `borrowedMic`; two new commands. Pure logic. |
| `app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt` | Modify | Extend/replace tests for the inversion + acquire/release sequences. |
| `app/src/main/java/com/benzn/grandtime/capture/camera2/SegmentRecorder.kt` | Modify | `audioHandover` flag, silence injection + pacing, `pauseAudioForHandover()`, `resumeAudio()`, `start(startAudioPaused)`. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/MicHandover.kt` | Create | Decoupling interface `begin()/end()`. |
| `app/src/main/java/com/benzn/grandtime/capture/camera2/Camera2Pipeline.kt` | Modify | `pauseSegmentAudio()/resumeSegmentAudio()`; `startAudioPaused` param on `startSegment`. |
| `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt` | Modify | Implements `MicHandover`; `handoverActive`; new segments start paused during handover. |
| `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt` | Modify | Inject `MicHandover`; map the two commands; failure path returns the mic. |
| `app/src/main/java/com/benzn/grandtime/service/CoreService.kt` | Modify | Wire `captureManager` as the `SiteVoiceManager`'s `MicHandover` (gated). |
| `app/src/main/java/com/benzn/grandtime/ask/AskCore.kt` | Modify | I-1: `onPttDown`/`onDiscreteAsk` yield when `siteVoiceActive`. |
| `app/src/test/java/com/benzn/grandtime/ask/AskCoreTest.kt` | Modify | Test the I-1 yield. |
| `app/src/main/java/com/benzn/grandtime/ask/AskManager.kt` | Modify | Pass `AppState.siteVoiceActive` into the core. |

---

## Task 1: `SiteVoiceCore` arbitration inversion + borrowed-mic commands

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceCore.kt:14-25` (command interface), `:45-78` (onSosDown/onSosUp/onCapReached), `:99` (onError)
- Test: `app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks (pure logic, do first).
- Produces:
  - `SiteVoiceCommand.AcquireMicFromCapture : SiteVoiceCommand` (data object)
  - `SiteVoiceCommand.ReleaseMicToCapture : SiteVoiceCommand` (data object)
  - `SiteVoiceCore.onSosDown(videoRecording: Boolean, askActive: Boolean): List<SiteVoiceCommand>` — when idle & not `askActive`: goes `Recording` even if `videoRecording`; if `videoRecording`, emits `AcquireMicFromCapture` first and sets internal `borrowedMic=true`.
  - `onSosUp()` / `onCapReached()` — emit `ReleaseMicToCapture` after `StopRecording` iff `borrowedMic`, then clear it.

- [ ] **Step 1: Write the failing tests** (edit `SiteVoiceCoreTest.kt`)

Replace the existing `down_while_video_recording_is_busy_and_stays_idle` test (lines 21-26) with the inverted-behaviour test, and add four new tests. Leave every other test unchanged.

```kotlin
    @Test fun down_during_video_acquires_mic_then_records() {
        val c = core()
        val cmds = c.onSosDown(videoRecording = true, askActive = false)
        assertEquals(SiteVoiceState.Recording, c.state)
        assertEquals(
            listOf(
                SiteVoiceCommand.AcquireMicFromCapture,
                SiteVoiceCommand.PlayTalkStartCue,
                SiteVoiceCommand.StartRecording,
                SiteVoiceCommand.ArmCapTimer,
            ),
            cmds,
        )
        // Acquire must precede StartRecording so the mic is free before Site-voice opens it.
        assertTrue(cmds.indexOf(SiteVoiceCommand.AcquireMicFromCapture)
            < cmds.indexOf(SiteVoiceCommand.StartRecording))
    }

    @Test fun down_when_free_does_not_acquire_mic() {
        val c = core()
        val cmds = c.onSosDown(videoRecording = false, askActive = false)
        assertTrue(!cmds.contains(SiteVoiceCommand.AcquireMicFromCapture))
    }

    @Test fun up_after_borrowed_mic_releases_after_stop_and_before_upload() {
        val c = core().apply { onSosDown(videoRecording = true, askActive = false) }
        val cmds = c.onSosUp()
        assertEquals(SiteVoiceState.Sending, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.ReleaseMicToCapture))
        assertTrue(cmds.indexOf(SiteVoiceCommand.StopRecording)
            < cmds.indexOf(SiteVoiceCommand.ReleaseMicToCapture))
        assertTrue(cmds.indexOf(SiteVoiceCommand.ReleaseMicToCapture)
            < cmds.indexOf(SiteVoiceCommand.UploadAndSend))
    }

    @Test fun up_without_borrow_has_no_release() {
        val c = core().apply { onSosDown(videoRecording = false, askActive = false) }
        assertTrue(!c.onSosUp().contains(SiteVoiceCommand.ReleaseMicToCapture))
    }

    @Test fun cap_after_borrowed_mic_releases() {
        val c = core().apply { onSosDown(videoRecording = true, askActive = false) }
        val cmds = c.onCapReached()
        assertEquals(SiteVoiceState.Sending, c.state)
        assertTrue(cmds.contains(SiteVoiceCommand.ReleaseMicToCapture))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.SiteVoiceCoreTest"`
Expected: FAIL — compile error `Unresolved reference: AcquireMicFromCapture` (and `ReleaseMicToCapture`).

- [ ] **Step 3: Add the two commands** (edit `SiteVoiceCore.kt`, inside `sealed interface SiteVoiceCommand`, after `PlayBusyCue`)

```kotlin
    data object PlayBusyCue : SiteVoiceCommand
    data object PlayErrorCue : SiteVoiceCommand
    /** Borrow the mic from an active video segment (video audio goes silent). Emitted before
     *  StartRecording only when a video recording is active at SOS-down. */
    data object AcquireMicFromCapture : SiteVoiceCommand
    /** Return the mic to the video segment (real audio resumes). Emitted after StopRecording. */
    data object ReleaseMicToCapture : SiteVoiceCommand
    data class PlayClip(val clip: VoiceClip) : SiteVoiceCommand
```

- [ ] **Step 4: Add `borrowedMic` and invert the arbitration** (edit `SiteVoiceCore.kt`)

Add the field just below `queueSize` (after line 43):

```kotlin
    private val queue = ArrayDeque<VoiceClip>()
    val queueSize: Int get() = queue.size

    /** True while the current talk borrowed the mic from an active video segment; drives the
     *  ReleaseMicToCapture emission on stop. Recomputed on every onSosDown. */
    private var borrowedMic = false
```

Replace `onSosDown` (lines 45-58) with:

```kotlin
    fun onSosDown(videoRecording: Boolean, askActive: Boolean): List<SiteVoiceCommand> = when (state) {
        SiteVoiceState.Idle ->
            if (askActive) {
                listOf(SiteVoiceCommand.PlayBusyCue) // Ask holds the mic: mutually exclusive, no-op talk
            } else {
                state = SiteVoiceState.Recording
                borrowedMic = videoRecording // borrow only when a video segment is running
                buildList {
                    if (videoRecording) add(SiteVoiceCommand.AcquireMicFromCapture)
                    add(SiteVoiceCommand.PlayTalkStartCue)
                    add(SiteVoiceCommand.StartRecording)
                    add(SiteVoiceCommand.ArmCapTimer)
                }
            }
        else -> emptyList() // ignore re-entrant down / down while sending or playing
    }
```

Replace `onSosUp` (lines 60-70) with:

```kotlin
    fun onSosUp(): List<SiteVoiceCommand> = when (state) {
        SiteVoiceState.Recording -> {
            state = SiteVoiceState.Sending
            buildList {
                add(SiteVoiceCommand.CancelCapTimer)
                add(SiteVoiceCommand.StopRecording)
                if (borrowedMic) add(SiteVoiceCommand.ReleaseMicToCapture)
                add(SiteVoiceCommand.UploadAndSend)
            }.also { borrowedMic = false }
        }
        else -> emptyList()
    }
```

Replace `onCapReached` (lines 72-78) with:

```kotlin
    fun onCapReached(): List<SiteVoiceCommand> = when (state) {
        SiteVoiceState.Recording -> {
            state = SiteVoiceState.Sending
            buildList {
                add(SiteVoiceCommand.StopRecording)
                if (borrowedMic) add(SiteVoiceCommand.ReleaseMicToCapture)
                add(SiteVoiceCommand.UploadAndSend)
            }.also { borrowedMic = false }
        }
        else -> emptyList()
    }
```

Replace `onError` (line 99) with a version that clears the flag (the manager returns the mic out-of-band on error, so the core must not leave `borrowedMic` set):

```kotlin
    fun onError(): List<SiteVoiceCommand> {
        borrowedMic = false
        return drainOrIdle(listOf(SiteVoiceCommand.CancelCapTimer, SiteVoiceCommand.PlayErrorCue))
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.sitevoice.SiteVoiceCoreTest"`
Expected: PASS (all SiteVoiceCoreTest tests green, including the unchanged queue/error tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceCore.kt \
        app/src/test/java/com/benzn/grandtime/sitevoice/SiteVoiceCoreTest.kt
git commit -m "feat(site-voice): borrow the mic from video instead of refusing (core FSM)"
```

---

## Task 2: `SegmentRecorder` pause/resume + paced silence injection

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/capture/camera2/SegmentRecorder.kt:28-42` (fields), `:70-93` (setupAudio → extract buildMic), `:123-145` (start), `:184-223` (audioLoop), plus three new methods.
- Test: none (device-verified; no JVM test for camera/codec classes — CLAUDE.md).

**Interfaces:**
- Consumes: nothing (delegated-to leaf).
- Produces (called by Task 3 via Camera2Pipeline):
  - `SegmentRecorder.start(startAudioPaused: Boolean = false)` — default `false` preserves current behaviour; `true` starts the segment with `audioHandover=true` and does NOT open the mic.
  - `SegmentRecorder.pauseAudioForHandover()` — set `audioHandover=true`, stop+release+null the `AudioRecord`. Idempotent; no-op if audio not enabled.
  - `SegmentRecorder.resumeAudio(): Boolean` — rebuild+start a fresh mic, clear `audioHandover`; on failure leave `audioHandover=true` (silent) and return `false`, never crash.

- [ ] **Step 1: Add the handover fields** (edit `SegmentRecorder.kt`, in the `// 音频` block after `audioTrack` on line 34)

```kotlin
    private var audioTrack = -1
    // Non-terminal handover flag: when true the audio loop zero-fills (paced silence) instead of
    // reading the mic. DISTINCT from the terminal audioStopRequested (which queues EOS + ends the
    // track). Only the AudioRecord is released/reopened; the AAC codec + muxer track stay alive.
    @Volatile private var audioHandover = false
    // Mic buffer size + one-buffer duration, set by buildMic(); used to pace the silence branch.
    private var audioBufBytes = 4096 * 2
    private var silenceMillis = 92L
```

- [ ] **Step 2: Extract `buildMic()` and use it from `setupAudio()`** (replace lines 70-93)

```kotlin
    /** 建 AAC 编码器 + AudioRecord。任一步失败抛异常(prepare catch → 降纯视频)。 */
    private fun setupAudio(): Boolean {
        val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            ac.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            runCatching { ac.release() } // 释放半配置的 AAC 编码器,避免泄漏
            throw e
        }
        audioCodec = ac
        audioRecord = try {
            buildMic()
        } catch (e: Exception) {
            runCatching { audioCodec?.release() }; audioCodec = null // 释放已建 AAC,避免泄漏
            throw e
        }
        return true
    }

    /** Build a MIC AudioRecord with the fixed capture params, recording the buffer size and the
     *  derived one-buffer duration (fillSilence paces on these). Throws if not INITIALIZED. */
    @SuppressLint("MissingPermission") // 调用方(prepare/resumeAudio)已确保 RECORD_AUDIO
    private fun buildMic(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, 4096 * 2)
        audioBufBytes = bufSize
        silenceMillis = (bufSize / 2) * 1000L / AUDIO_SAMPLE_RATE // frames ÷ 44100, as ms
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); throw IllegalStateException("AudioRecord 未初始化")
        }
        return ar
    }
```

- [ ] **Step 3: Add the `startAudioPaused` gate to `start()`** (replace lines 123-145)

```kotlin
    fun start(startAudioPaused: Boolean = false) {
        val c = codec ?: error("prepare() first")
        c.start()
        draining = true
        videoThread = Thread { videoLoop() }.apply { name = "seg-video"; start() }
        if (!audioEnabled) return
        audioStopRequested = false
        if (startAudioPaused) {
            // Segment rollover happened mid-handover: keep the AAC codec + muxer audio track alive
            // but do NOT open the mic. The loop zero-fills (silent) until resumeAudio() opens one.
            audioHandover = true
            val started = runCatching {
                audioCodec?.start()
                runCatching { audioRecord?.release() } // free the mic setupAudio() built; loop is silent
                audioRecord = null
                true
            }.getOrDefault(false)
            if (started) {
                audioThread = Thread { audioLoop() }.apply { name = "seg-audio"; start() }
            } else {
                probe("音频启动失败(paused),本段降为纯视频")
                audioEnabled = false
                runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
                audioRecord = null; audioCodec = null
            }
            return
        }
        val audioStarted = runCatching {
            audioCodec?.start()
            audioRecord?.startRecording()
            audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)
        if (audioStarted) {
            audioThread = Thread { audioLoop() }.apply { name = "seg-audio"; start() }
        } else {
            probe("音频启动失败,本段降为纯视频")
            audioEnabled = false   // maybeStartMuxer 改为仅凭视频轨启动,避免整段无输出
            runCatching { audioRecord?.stop() }; runCatching { audioRecord?.release() }
            runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
            audioRecord = null; audioCodec = null
        }
    }
```

- [ ] **Step 4: Rewrite `audioLoop()` for field-fresh mic reads + non-terminal silence** (replace lines 184-223)

Key changes vs. the original: the mic handle is read from the **field** each iteration (not captured once as a local, so a reopened mic is picked up); EOS is queued **only** for the terminal `audioStopRequested`; a transient `<=0` read (e.g. a mic stopped mid-handover) becomes silence, never EOS.

```kotlin
    /** 一个循环里既喂 PCM 到 AAC 输入,又把 AAC 输出写 muxer。仅 audioStopRequested 发 input EOS;
     *  audioHandover / 麦克风缺失 时喂静音(非终态)。麦克风句柄每轮从字段现取,故 resumeAudio 重开后被拾起。 */
    private fun audioLoop() {
        val ac = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        while (true) {
            if (!sawInputEos) {
                val inIdx = try { ac.dequeueInputBuffer(10_000) } catch (e: Exception) { break }
                if (inIdx >= 0) {
                    val inBuf = ac.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val ptsUs = System.nanoTime() / 1000  // 墙钟微秒,与视频输入面帧时戳同基准
                        if (audioStopRequested) {             // terminal only: end the AAC input stream
                            ac.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val ar = audioRecord              // read field fresh: may be null mid-handover
                            val n = if (audioHandover || ar == null) {
                                fillSilence(inBuf)            // paced silence, non-terminal
                            } else {
                                val r = runCatching { ar.read(inBuf, inBuf.capacity()) }.getOrDefault(0)
                                if (r > 0) r else fillSilence(inBuf) // transient <=0 → silence, NOT EOS
                            }
                            ac.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                        }
                    }
                }
            }
            val outIdx = try { ac.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    if (audioTrack < 0) { audioTrack = muxer!!.addTrack(ac.outputFormat); audioFormatReady = true }
                }
                maybeStartMuxer()
            } else if (outIdx >= 0) {
                val outBuf = ac.getOutputBuffer(outIdx)
                if (outBuf != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                    synchronized(muxerLock) { if (muxerStarted) runCatching { muxer!!.writeSampleData(audioTrack, outBuf, info) } }
                }
                runCatching { ac.releaseOutputBuffer(outIdx, false) }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    /** Write a buffer of PCM silence into [buf] (reused codec buffers may hold stale audio) and pace
     *  the loop ~one buffer-duration so PTS advances at the real-time cadence and the encoder is not
     *  flooded with near-identical timestamps. Returns the byte count written. */
    private fun fillSilence(buf: java.nio.ByteBuffer): Int {
        val n = minOf(audioBufBytes, buf.capacity()).coerceAtLeast(2)
        buf.position(0)
        repeat(n) { buf.put(0.toByte()) }
        runCatching { Thread.sleep(silenceMillis) }
        return n
    }
```

- [ ] **Step 5: Add `pauseAudioForHandover()` and `resumeAudio()`** (insert after `audioLoop()`/`fillSilence`, before `stop()` on line 226)

```kotlin
    /** Site-voice is borrowing the mic: go silent (non-terminal) then free the mic hardware. The
     *  AAC codec + muxer audio track stay alive; the loop zero-fills until resumeAudio(). The flag
     *  is set BEFORE the release so the loop takes the silence branch and never touches a released
     *  handle. Idempotent; no-op if audio was never enabled. */
    fun pauseAudioForHandover() {
        if (!audioEnabled) return
        audioHandover = true
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    /** End the handover: open a fresh mic (same params) and resume real audio. Publishes the new
     *  handle to the field BEFORE clearing the flag, so the loop never sees handover=false with a
     *  null mic. If the rebuild fails, leave audioHandover=true (track stays silent for the rest of
     *  the segment), log, and return false — NEVER crash the recording. No-op/true if not enabled. */
    fun resumeAudio(): Boolean {
        if (!audioEnabled) return true
        return runCatching {
            val ar = buildMic()
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                ar.release(); throw IllegalStateException("AudioRecord 未进入录制")
            }
            audioRecord = ar
            audioHandover = false
            true
        }.getOrElse {
            probe("resumeAudio 失败,本段音频保持静音: ${it.message}")
            audioHandover = true
            false
        }
    }
```

- [ ] **Step 6: Build the dev flavor**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug`
Expected: BUILD SUCCESSFUL. (On "Could not delete ...build..." Dropbox lock, rerun once.)

- [ ] **Step 7: Device-verify note**

No JVM test for this class (camera/codec — CLAUDE.md). Deferred to the combined on-device acceptance at the end of the plan (record video, trigger a handover via Task 3-5 wiring, confirm a silent-but-continuous audio span + real audio resumes + no crash). At this task's boundary, only confirm the build compiles and the AAC/muxer path is untouched (no second `addTrack`, EOS still only on `audioStopRequested`).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/capture/camera2/SegmentRecorder.kt
git commit -m "feat(capture): SegmentRecorder mic pause/resume with paced silence injection"
```

---

## Task 3: `MicHandover` interface + `CaptureManager` implements it + pipeline delegation

**Files:**
- Create: `app/src/main/java/com/benzn/grandtime/sitevoice/MicHandover.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/capture/camera2/Camera2Pipeline.kt:165-197` (startSegment gains `startAudioPaused`; add `pauseSegmentAudio()/resumeSegmentAudio()`)
- Modify: `app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt:46-56` (class header → implement `MicHandover`), `:181-248` (pass `startAudioPaused`), add `handoverActive` + `begin()/end()`.
- Test: none (device-verified).

**Interfaces:**
- Consumes: `SegmentRecorder.start(startAudioPaused)`, `pauseAudioForHandover()`, `resumeAudio()` (Task 2).
- Produces (for Task 4/5):
  - `interface MicHandover { suspend fun begin(): Boolean; suspend fun end() }`
  - `CaptureManager : MicHandover` — `begin()` no-ops (returns true) when no video is recording; else sets `handoverActive=true` and pauses the segment's mic. `end()` idempotent; clears `handoverActive` and resumes.
  - `Camera2Pipeline.pauseSegmentAudio()`, `Camera2Pipeline.resumeSegmentAudio(): Boolean`, `Camera2Pipeline.startSegment(..., startAudioPaused: Boolean = false, onFinalized)`.

- [ ] **Step 1: Create the interface** (`MicHandover.kt`)

```kotlin
package com.benzn.grandtime.sitevoice

/**
 * Lets Site-voice temporarily borrow the microphone from the capture pipeline while a video
 * recording is active. Implemented by CaptureManager; keeps SiteVoiceManager decoupled from
 * capture/. Both methods are idempotent and safe to call when no video recording is active (no-op).
 */
interface MicHandover {
    /** Pause the active video segment's mic (its audio goes silent) so Site-voice can record.
     *  Returns true when the handover engaged OR safely no-op'd — i.e. the mic is available to
     *  Site-voice either way. */
    suspend fun begin(): Boolean

    /** Return the mic to the video segment (real audio resumes). Idempotent no-op when no handover
     *  is active. Must never crash the video recording (a failed mic reopen leaves it silent). */
    suspend fun end()
}
```

- [ ] **Step 2: Add pipeline delegation + `startAudioPaused`** (edit `Camera2Pipeline.kt`)

In `startSegment` (line 165), add the parameter before the trailing `onFinalized` lambda:

```kotlin
    suspend fun startSegment(
        file: File,
        aspect: AspectRatio,
        quality: VideoQuality,
        hevcPreferred: Boolean,
        location: Pair<Float, Float>?,
        startAudioPaused: Boolean = false,
        onFinalized: (error: Boolean, message: String?) -> Unit,
    ): SegmentResult? {
```

Inside `startSegment`, change the `recorder.start()` call (line 186) to:

```kotlin
            recorder.start(startAudioPaused)
```

Add two delegating methods next to `setPreviewSurface` (after line 274):

```kotlin
    /** Handover: pause the live segment's mic (video audio goes silent). No-op if no live segment. */
    fun pauseSegmentAudio() { segment?.pauseAudioForHandover() }

    /** Handover end: reopen the live segment's mic. Returns true on success OR when there is no
     *  live segment to resume (nothing to do). A failed reopen returns false (segment stays silent). */
    fun resumeSegmentAudio(): Boolean = segment?.resumeAudio() ?: true
```

- [ ] **Step 3: Make `CaptureManager` implement `MicHandover`** (edit `CaptureManager.kt`)

Change the class header (line 46) to implement the interface and add the import:

```kotlin
import com.benzn.grandtime.sitevoice.MicHandover
```

```kotlin
class CaptureManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val dao: CaptureRecordDao,
    private val notify: (String) -> Unit,
    private val probe: (String) -> Unit,
    private val uploadEnqueuer: UploadEnqueuer = object : UploadEnqueuer {
        override fun enqueue(recordId: String, initialDelaySeconds: Long, requireUnmetered: Boolean) {}
    },
) : MicHandover {
```

Add the `handoverActive` field next to the other private vars (after line 68 `pendingRoll`):

```kotlin
    private var pendingRoll = false
    /** True while Site-voice is borrowing the mic. Read by startVideoSegment so a segment rollover
     *  during a handover starts the next segment with its audio paused (silent) until end(). */
    @Volatile private var handoverActive = false
```

- [ ] **Step 4: Implement `begin()`/`end()`** (add methods to `CaptureManager.kt`, e.g. after `shutdown()` on line 129)

```kotlin
    override suspend fun begin(): Boolean {
        // No video segment running → nothing to borrow; Site-voice records normally.
        if (core.state !is CaptureState.RecordingVideo) return true
        handoverActive = true
        pipeline.pauseSegmentAudio()
        return true
    }

    override suspend fun end() {
        if (!handoverActive) return
        handoverActive = false
        val ok = pipeline.resumeSegmentAudio()
        if (!ok) probe("mic handover: resume failed, segment audio stays silent")
    }
```

- [ ] **Step 5: Start rolled-over segments paused during a handover** (edit `startVideoSegment`, the `pipeline.startSegment(...)` call at line 191)

```kotlin
        val result = pipeline.startSegment(
            file = file,
            aspect = settings.aspectRatio,
            quality = settings.videoQuality,
            hevcPreferred = true,
            location = gps.freshFix()?.let { it.first.toFloat() to it.second.toFloat() },
            startAudioPaused = handoverActive, // rollover mid-handover → new segment records silent
        ) { error, message ->
```

- [ ] **Step 6: Build the dev flavor**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug`
Expected: BUILD SUCCESSFUL. (Rerun once on the Dropbox delete-lock.)

- [ ] **Step 7: Device-verify note**

Compile-only gate here; behaviour is exercised in the final acceptance (the segment-boundary-straddle case specifically checks `startAudioPaused`). Confirm no import cycle warning (capture → sitevoice.MicHandover is a one-way dependency, allowed within the module).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/sitevoice/MicHandover.kt \
        app/src/main/java/com/benzn/grandtime/capture/camera2/Camera2Pipeline.kt \
        app/src/main/java/com/benzn/grandtime/capture/CaptureManager.kt
git commit -m "feat(capture): MicHandover interface + CaptureManager/pipeline delegation"
```

---

## Task 4: `SiteVoiceManager` maps the handover commands + returns mic on failure

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt:31-39` (constructor), `:95-107` (execute), `:226-231` (fail)
- Test: none (device-verified; the FSM sequencing is covered by Task 1's unit tests).

**Interfaces:**
- Consumes: `MicHandover.begin()/end()` (Task 3); `SiteVoiceCommand.AcquireMicFromCapture / ReleaseMicToCapture` (Task 1).
- Produces: `SiteVoiceManager(context, scope, auth, apiBaseUrl, wsUrl, connectivity, micHandover, probe)` — a new required `micHandover: MicHandover` constructor parameter (consumed by Task 5).

- [ ] **Step 1: Inject `MicHandover`** (edit the constructor, add a parameter before `probe`)

```kotlin
class SiteVoiceManager(
    context: Context,
    private val scope: CoroutineScope,
    private val auth: AuthManager,
    apiBaseUrl: String,
    wsUrl: String,
    connectivity: ConnectivityManager,
    private val micHandover: MicHandover,
    private val probe: (String) -> Unit = {},
) {
```

(`MicHandover` is in the same package `com.benzn.grandtime.sitevoice`, so no import is needed.)

- [ ] **Step 2: Map the two commands in `execute`** (edit the `when` in `execute`, add two branches after `PlayErrorCue` on line 99)

```kotlin
            SiteVoiceCommand.PlayErrorCue -> { probe("site-voice: error"); sounds.error() }
            SiteVoiceCommand.AcquireMicFromCapture -> { probe("site-voice: borrow mic"); micHandover.begin() }
            SiteVoiceCommand.ReleaseMicToCapture -> { probe("site-voice: return mic"); micHandover.end() }
            SiteVoiceCommand.StartRecording -> if (!recorder.start()) { fail(); return }
```

- [ ] **Step 3: Return the mic on the failure path** (edit `fail`, line 226)

```kotlin
    private suspend fun fail() {
        recorder.discard()
        capTimer?.cancel(); capTimer = null
        micHandover.end() // restore video audio if we had borrowed the mic (idempotent no-op otherwise)
        execute(core.onError())
        AppState.siteVoiceActive.value = core.state != SiteVoiceState.Idle
    }
```

This covers the spec edge case "Site-voice `recorder.start()` fails after borrowing" — the StartRecording branch calls `fail()`, which returns the mic before `core.onError()` plays the error cue. `end()` is idempotent, so the normal path (where `ReleaseMicToCapture` already ran) and the no-video path (never borrowed) are unaffected.

- [ ] **Step 4: Build the dev flavor**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug`
Expected: FAIL — CoreService still constructs `SiteVoiceManager` without the new `micHandover` argument. This is expected; Task 5 fixes the single call site. If you want a green intermediate build, do Steps 1-3 of Task 5 before rebuilding.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/sitevoice/SiteVoiceManager.kt
git commit -m "feat(site-voice): map acquire/release commands to MicHandover + return mic on failure"
```

---

## Task 5: Wire `CaptureManager` as the handover in `CoreService`

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/service/CoreService.kt:275-317` (capture a local ref to `captureManager`; pass it as `micHandover`)
- Test: none (device-verified).

**Interfaces:**
- Consumes: `CaptureManager : MicHandover` (Task 3); `SiteVoiceManager(..., micHandover, ...)` (Task 4).
- Produces: fully wired handover, gated by `BuildConfig.SITE_VOICE_ENABLED`.

- [ ] **Step 1: Keep a local reference to the constructed `CaptureManager`** (edit line 275)

```kotlin
        val capture = CaptureManager(
            context = this,
            scope = lifecycleScope,
            settingsStore = SettingsStore(applicationContext.settingsDataStore),
            dao = CaptureDb.get(applicationContext).captureRecords(),
            notify = ::notifyStatus,
            probe = ::probe,
            uploadEnqueuer = WorkManagerUploadEnqueuer(applicationContext),
        )
        captureManager = capture
```

- [ ] **Step 2: Pass it into `SiteVoiceManager`** (edit the `SiteVoiceManager(...)` construction at line 308, inside the existing `if (BuildConfig.SITE_VOICE_ENABLED)` block)

```kotlin
                val siteVoice = SiteVoiceManager(
                    context = this,
                    scope = lifecycleScope,
                    auth = auth,
                    apiBaseUrl = BuildConfig.ORG_API_BASE_URL,
                    wsUrl = BuildConfig.SITE_VOICE_WS_URL,
                    connectivity = connectivity,
                    micHandover = capture,
                    probe = ::probe,
                )
```

Both managers already share `lifecycleScope` (`Dispatchers.Main.immediate`), so `begin()/end()`, `startVideoSegment`, and `handoverActive` all run confined to the main thread — no cross-thread race on the flag. No changes are needed outside the `SITE_VOICE_ENABLED` gate: `CaptureManager` implements `MicHandover` unconditionally, but the reference is only handed to Site-voice when the flag is on.

- [ ] **Step 3: Build the dev flavor + run the unit suite**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug testProdDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests green (163 existing + the new SiteVoiceCoreTest cases). (Rerun once on the Dropbox delete-lock.)

- [ ] **Step 4: Device-verify note**

Install dev flavor and smoke-test that the app boots and Site-voice still connects (no handover yet exercised): `adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk`, confirm the WS status dot and that a plain SOS talk (no video) still records/sends. Full handover behaviour is verified in the final acceptance below.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/service/CoreService.kt
git commit -m "feat(service): wire CaptureManager as SiteVoiceManager MicHandover (gated)"
```

---

## Task 6: I-1 — Ask yields to an active Site-voice talk

**Files:**
- Modify: `app/src/main/java/com/benzn/grandtime/ask/AskCore.kt:29-38` (onPttDown), `:77-81` (onDiscreteAsk)
- Test: `app/src/test/java/com/benzn/grandtime/ask/AskCoreTest.kt`
- Modify: `app/src/main/java/com/benzn/grandtime/ask/AskManager.kt:39-47` (pass `siteVoiceActive`)

**Interfaces:**
- Consumes: `AppState.siteVoiceActive` (already exists, written by SiteVoiceManager).
- Produces: `AskCore.onPttDown(videoRecording: Boolean, siteVoiceActive: Boolean = false)` — busy cue when `videoRecording || siteVoiceActive`; the `videoRecording` yield is unchanged. `onDiscreteAsk(videoRecording, siteVoiceActive = false)` forwards both.

- [ ] **Step 1: Write the failing test** (add to `AskCoreTest.kt`)

```kotlin
    @Test fun down_during_site_voice_is_busy_and_stays_idle() {
        val c = core()
        val cmds = c.onPttDown(videoRecording = false, siteVoiceActive = true)
        assertEquals(AskState.Idle, c.state)
        assertEquals(listOf(AskCommand.PlayBusyCue), cmds)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.ask.AskCoreTest"`
Expected: FAIL — `onPttDown` accepts only one argument (too many arguments) OR the test compiles against the new default but the busy branch is not yet implemented.

- [ ] **Step 3: Add the `siteVoiceActive` yield** (edit `AskCore.kt`)

Replace `onPttDown` (lines 29-38). The `siteVoiceActive` parameter defaults to `false`, so the existing 10+ single-argument call sites in `AskCoreTest` keep compiling unchanged:

```kotlin
    fun onPttDown(videoRecording: Boolean, siteVoiceActive: Boolean = false): List<AskCommand> = when (state) {
        AskState.Idle ->
            if (videoRecording || siteVoiceActive) {
                listOf(AskCommand.PlayBusyCue)  // mic exclusivity: yield to video OR active Site-voice
            } else {
                state = AskState.Listening
                listOf(AskCommand.PlayListeningCue, AskCommand.StartRecording, AskCommand.ArmCapTimer)
            }
        else -> emptyList()  // ignore re-entrant down mid-ask
    }
```

Replace `onDiscreteAsk` (lines 77-81) to forward the new flag:

```kotlin
    /** Discrete (tap) trigger for a keymap-routed hard key (Task 13): toggles
     * start-listening / stop-and-send, so a rebound key works without hold. */
    fun onDiscreteAsk(videoRecording: Boolean, siteVoiceActive: Boolean = false): List<AskCommand> = when (state) {
        AskState.Idle -> onPttDown(videoRecording, siteVoiceActive)
        AskState.Listening -> onPttUp()
        else -> emptyList()
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew testProdDebugUnitTest --tests "com.benzn.grandtime.ask.AskCoreTest"`
Expected: PASS (new test + all existing AskCoreTest tests green).

- [ ] **Step 5: Feed `siteVoiceActive` from `AskManager`** (edit `AskManager.kt`)

Add the getter next to `videoRecording` (after line 41):

```kotlin
    private val videoRecording: Boolean
        get() = AppState.captureState.value is CaptureState.RecordingVideo
    private val siteVoiceActive: Boolean
        get() = AppState.siteVoiceActive.value
```

Update the two dispatch entry points (lines 44 and 47):

```kotlin
    fun onPttDown() = dispatch { core.onPttDown(videoRecording, siteVoiceActive) }
    fun onPttUp() = dispatch { core.onPttUp() }
    /** Keymap-routed discrete tap (Task 13). */
    fun onDiscreteAsk() = dispatch { core.onDiscreteAsk(videoRecording, siteVoiceActive) }
```

(Optional, keeps the log honest: the `PlayBusyCue` executor at line 68 currently logs `"ask: busy (video active)"`; you may change it to `"ask: busy (mic busy)"` since the cause is now video OR Site-voice. Not load-bearing.)

- [ ] **Step 6: Build + full unit suite**

Run: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug testProdDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/benzn/grandtime/ask/AskCore.kt \
        app/src/test/java/com/benzn/grandtime/ask/AskCoreTest.kt \
        app/src/main/java/com/benzn/grandtime/ask/AskManager.kt
git commit -m "feat(ask): yield to an active Site-voice talk (I-1)"
```

---

## Final verification: combined two-device + on-device ffprobe acceptance

This is the spec's Testing-strategy device acceptance — the real proof the handover works end to end. Run after all six tasks are committed. Use the dev flavor (`SITE_VOICE_ENABLED=true`) on both devices; both must be logged in and have the **same** site selected.

- [ ] **Step 1: Install the dev build on both devices**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDevDebug
# Device A (recorder) and Device B (listener)
adb -s <A> install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb -s <B> install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

- [ ] **Step 2: Record + talk (the core case)**
  1. On **Device A**, start a video recording (START_STOP_VIDEO).
  2. Mid-recording, press-and-hold **SOS** on Device A and speak for ~3-5 s, then release.
  3. Confirm **(a)** Device B plays the clip (audible), and Device A played the talk-start cue (not the busy cue).
  4. Stop the video recording on Device A.

- [ ] **Step 3: Pull the MP4 and inspect the audio track with on-device tools**

```bash
# newest video on device A
MSYS_NO_PATHCONV=1 adb -s <A> shell ls -t //sdcard/Android/data/com.benzn.grandtime/files/... | head
adb -s <A> pull "<path-to-newest.mp4>" ./handover-check.mp4
# audio track present + continuous; then extract a waveform / silence map over the talk window
ffprobe -hide_banner -show_streams -select_streams a ./handover-check.mp4
ffprobe -hide_banner -f lavfi -i "amovie=handover-check.mp4,silencedetect=n=-40dB:d=1" -show_entries tag=lavfi.silence_start,lavfi.silence_end -of default=nw=1 2>&1 | grep silence
```

Confirm **(b)**: the audio stream exists and its duration matches the video (no track gap); `silencedetect` reports a silent span whose start/duration line up with the SOS talk window, with real audio before and after.

- [ ] **Step 4: Resume + no-crash checks**
  - **(c)** Real audio resumes: the region after the talk in the waveform is non-silent (speak near Device A after releasing SOS to make this obvious).
  - **(d)** No capture crash: `adb -s <A> logcat` shows no `FATAL EXCEPTION`; the recording continued to a normal stop; the DB row finalized (file has non-zero size, plays back).

- [ ] **Step 5: Segment-boundary straddle (the rollover case)**
  1. Temporarily set the segment length short (Settings → segment minutes = 1, or the smallest available) on Device A.
  2. Start recording; near a segment boundary, hold SOS so the talk straddles the rollover, then release after the new segment has started.
  3. **(e)** Confirm the talk still restores: Device B hears the clip; **both** MP4 segments are valid; the first shows a silent tail over the talk and the second (which started with `startAudioPaused=true`) shows a silent head that transitions to real audio after `end()` — verify each with the `silencedetect` command from Step 3. No crash across the boundary.

- [ ] **Step 6: Regressions (guard the unchanged paths)**
  - SOS with **no** video recording → normal Site-voice record/send, no silence artifacts (borrowedMic=false path).
  - SOS while an **Ask** talk is active → busy cue, no handover (askActive branch unchanged).
  - PTT (Ask) while a **Site-voice** talk is active → Ask plays the busy cue and does not record (I-1).
  - PTT (Ask) while a **video** recording is active → still busy cue (Ask's yield-to-video unchanged).

---

## Self-Review

**Spec coverage:**
- Silence-injection seam + non-terminal `audioHandover` + PTS continuity → Task 2 (audioLoop rewrite, `fillSilence`). ✔
- Hard invariant (AAC codec + audioTrack alive; only AudioRecord reopened) → Task 2 (`pauseAudioForHandover`/`resumeAudio` touch only the mic; EOS only on `audioStopRequested`). ✔
- Pacing (~one buffer-duration sleep) → Task 2 (`fillSilence` uses `silenceMillis`). ✔
- `pauseAudioForHandover()` / `resumeAudio()` (idempotent, failure-safe) → Task 2. ✔
- `start(startAudioPaused)` + segment-rollover-during-handover → Task 2 (param) + Task 3 (`handoverActive` → `startSegment(startAudioPaused=...)`). ✔
- Control surface `SiteVoiceManager → MicHandover → CaptureManager → Camera2Pipeline → SegmentRecorder` → Tasks 3-5. ✔
- Arbitration inversion + `borrowedMic` + two commands → Task 1. ✔
- Timing sequence (Acquire first; Release after Stop, before Upload) → Task 1 (ordering asserted in tests). ✔
- Edge cases: recorder.start fail → return mic (Task 4 `fail()`); resume fail → silent, no crash (Task 2); rollover (Task 2/3); cap-timer = SOS up (Task 1 `onCapReached`); SOS while askActive = busy (Task 1); no video = no-op handover (Task 1 `borrowedMic=false` + Task 3 `begin()` returns true early); shutdown during handover (Task 2 `stop()` sees `audioStopRequested`, mic already null). ✔
- I-1 (Ask yields to Site-voice; keeps yield-to-video) → Task 6. ✔
- `SITE_VOICE_ENABLED` gate → Task 5 (wiring stays inside the existing gate). ✔
- Evidence semantics (continuous video + silent-gapped audio) → verified in the final acceptance ffprobe check. ✔

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N" — every code step carries full code. ✔

**Type consistency:** `AcquireMicFromCapture`/`ReleaseMicToCapture` (Task 1) match the `execute` branches (Task 4). `MicHandover.begin(): Boolean`/`end()` (Task 3) match `CaptureManager` overrides (Task 3) and `SiteVoiceManager` calls (Task 4). `SegmentRecorder.start(startAudioPaused)`/`pauseAudioForHandover()`/`resumeAudio(): Boolean` (Task 2) match `Camera2Pipeline.startSegment(startAudioPaused)`/`pauseSegmentAudio()`/`resumeSegmentAudio()` (Task 3). `onPttDown(videoRecording, siteVoiceActive=false)` (Task 6 core) matches `AskManager` call (Task 6). ✔

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-19-site-voice-mic-handover.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — execute tasks in this session with checkpoints for review.

Note task order: **Task 1 and Task 6 are the unit-tested pure-logic tasks (safe to do first and in either order); Tasks 2 → 3 → 4 → 5 are a strict device-verified chain** (Task 4 leaves the tree non-compiling until Task 5 fixes the CoreService call site — do them as a pair before rebuilding, or accept the expected FAIL noted in Task 4 Step 4).
