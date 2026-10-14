package com.benzn.grandtime.ask

import android.content.Context
import android.util.Base64
import com.benzn.grandtime.auth.AuthManager
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.hardware.Haptics
import com.benzn.grandtime.keymap.KeyAction
import com.benzn.grandtime.sitevoice.MicHandover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates one hands-free voice ask: physical SOS key (HoldToTalkKeySource) down/up -> AskCore ->
 * executors (AskRecorder, AskApiClient, AskSounds, AskPlayer). Sibling to
 * CaptureManager in CoreService. Refuses while a video recording is active
 * (reads AppState.captureState). Direct synchronous API call (Dispatchers.IO),
 * not the WorkManager upload queue. ~15s recording cap via a timer.
 */
class AskManager(
    context: Context,
    private val scope: CoroutineScope,
    private val auth: AuthManager,
    private val apiBaseUrl: String,
    /** Lets an ask borrow the mic from a running video segment instead of refusing, exactly as
     *  Site-voice already does. No-op when no video is recording. */
    private val micHandover: MicHandover = object : MicHandover {
        override fun begin() = true
        override fun end() {}
    },
    private val probe: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val core = AskCore()
    private val recorder = AskRecorder(appContext, File(appContext.cacheDir, "ask"))
    private val api = AskApiClient(apiBaseUrl)
    private val sounds = AskSounds(appContext)
    private val player = AskPlayer(File(appContext.cacheDir, "ask"))
    private val haptics = Haptics(appContext)
    private var capTimer: Job? = null
    /** Watchdog for a playback that never calls back. See [AskCore.onPlaybackTimeout]. */
    private var playTimer: Job? = null
    /** The in-flight send. Tracked so an interrupt ([AskCommand.CancelTurn]) can abandon it. */
    private var turnJob: Job? = null
    /** Bumped by each listening cue, so a cue that outlived its own ask cannot open the mic. */
    private var listeningCueGeneration = 0
    /** "Got it. Checking your records now." -- see [PendingCue] for why it is not a bare play. */
    private val filler = PendingCue(scope, FILLER_DELAY_MS) { sounds.thinking() }

    /**
     * Silence the filler whether it is still pending or already speaking. Every sound that follows
     * the release -- the answer, an error cue -- goes through here first.
     */
    private fun stopFiller() {
        filler.cancel()
        sounds.stopThinking()
    }

    private val videoRecording: Boolean
        get() = AppState.captureState.value is CaptureState.RecordingVideo
    private val siteVoiceActive: Boolean
        get() = AppState.siteVoiceActive.value

    val handledActions: Set<KeyAction> = setOf(KeyAction.ASK_AGENT)

    fun onPttDown() = dispatch { core.onPttDown(videoRecording, siteVoiceActive) }
    fun onPttUp() = dispatch { core.onPttUp() }
    /** Keymap-routed discrete tap (Task 13). */
    fun onDiscreteAsk() = dispatch { core.onDiscreteAsk(videoRecording, siteVoiceActive) }

    /**
     * Serialize the AskCore mutation AND its command execution on [scope]'s
     * single dispatcher — [decide] (which reads [videoRecording] and mutates
     * [core].state) runs INSIDE the launch, not on the caller's thread. Mirrors
     * [com.benzn.grandtime.capture.CaptureManager.handle]. The cap-timer fire in
     * [armCap] mutates core from the same dispatcher, so a PTT event and the
     * cap fire can never both observe Listening and double-send (SendClip).
     * REQUIRES [scope] be confined to one thread (CoreService passes
     * lifecycleScope = Dispatchers.Main.immediate); AskCore.onXxx() are
     * non-suspending, so on a single-thread dispatcher they run atomically.
     */
    private fun dispatch(decide: () -> List<AskCommand>) {
        scope.launch { execute(decide()) }
    }

    private suspend fun execute(commands: List<AskCommand>) {
        for (cmd in commands) when (cmd) {
            AskCommand.PlayListeningCue -> {
                probe("ask: listening")
                // Awaited: the cue is 1.3 s, and played over an open microphone it went to speech
                // recognition as the start of the question. Re-checked afterwards because this
                // suspends: a press released during the cue has already moved the ask on (to
                // Thinking, then to an error with no clip), and a new press may have started
                // another ask. Either way THIS ask must not go on to open the microphone.
                //
                // The pause before the cue is the owner's call (2026-10-13): "Ready. Go ahead."
                // landing the instant the key went down felt rushed. It sits BEFORE the cue and
                // OUTSIDE askStartAndAwait, so the cue's own 3 s stuck-player guard keeps meaning
                // what it says. A press released during the pause bails here, before the cue is
                // spoken -- otherwise the device would say "go ahead" to someone who already let go.
                val generation = ++listeningCueGeneration
                delay(LISTENING_CUE_DELAY_MS)
                if (generation != listeningCueGeneration || core.state != AskState.Listening) return
                sounds.askStartAndAwait()
                if (generation != listeningCueGeneration || core.state != AskState.Listening) return
            }
            // Scheduled, never awaited: SendClip is the next command in this same list, and
            // waiting here would add the pause to the wait the line exists to cover.
            AskCommand.PlayThinkingCue -> filler.schedule()
            AskCommand.PlayBusyCue -> { probe("ask: busy (mic busy)"); sounds.error() }
            // stopFiller first: the spoken "checking your records" line may be pending or playing,
            // and an error cue on top of it says two contradictory things at once.
            AskCommand.PlayErrorCue -> { probe("ask: error"); stopFiller(); sounds.error() }
            AskCommand.StartRecording -> if (!recorder.start()) { fail(); return }  // short-circuit: skip stray ArmCapTimer
            AskCommand.StopRecording -> { /* clip read in SendClip */ }
            AskCommand.ArmCapTimer -> armCap()
            AskCommand.CancelCapTimer -> { capTimer?.cancel(); capTimer = null }
            // Own job, so an interrupt can cancel it; SendClip is always last in its list.
            AskCommand.SendClip -> turnJob = scope.launch { sendClip() }
            AskCommand.CancelTurn -> { probe("ask: interrupt"); turnJob?.cancel(); turnJob = null }
            AskCommand.StopAllAudio -> {
                playTimer?.cancel(); playTimer = null
                stopFiller()
                // Drops the pending completion callback, so the abandoned answer cannot report
                // "done" into the new ask.
                player.release()
            }
            AskCommand.AcquireMicFromCapture -> { probe("ask: borrow mic"); micHandover.begin() }
            AskCommand.ReleaseMicToCapture -> { probe("ask: return mic"); micHandover.end() }
            is AskCommand.PlayAnswer -> playAnswer(cmd.audioBase64)
            is AskCommand.Vibrate -> haptics.play(cmd.pattern)
        }
        // Status mirror (additive, does not alter Ask's own command flow): every core.onXxx()
        // result is executed through this function (dispatch, cap-timer fire, sendClip's
        // nested onAnswer, playback-done callback, fail's onError), so this single point
        // keeps AppState.askActive in sync with core.state after each FSM transition,
        // including the async continuations a dispatch-only mirror would miss.
        AppState.askActive.value = core.state != AskState.Idle
    }

    private fun armCap() {
        capTimer?.cancel()
        capTimer = scope.launch {
            delay(CAP_MILLIS)
            execute(core.onCapReached())
        }
    }

    private suspend fun sendClip() {
        // The ask this send belongs to. Everything below that suspends re-checks it: an interrupt
        // in the meantime has started a new ask, and this one must neither answer nor fail it.
        val generation = core.generation
        val clip = recorder.stop()
        if (clip == null) { fail(); return }
        val bytes = runCatching { clip.readBytes() }.getOrNull()
        clip.delete()
        if (bytes == null || bytes.isEmpty()) { fail(); return }
        val token = auth.freshIdToken()
        if (generation != core.generation) return
        if (token == null) { fail(); return }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val result = withContext(Dispatchers.IO) { api.ask(token, b64, "wav") }
        if (generation != core.generation) return  // interrupted while the request was out
        when (result) {
            is AskApiClient.AskResult.Ok -> execute(core.onAnswer(result.audioBase64, generation))
            else -> { probe("ask: send failed ($result)"); fail() }
        }
    }

    private suspend fun playAnswer(audioBase64: String) {
        val bytes = runCatching { Base64.decode(audioBase64, Base64.DEFAULT) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) { fail(); return }
        // The answer wins. The filler plays on its own player, so an answer that comes back faster
        // than the spoken line finishes would play across it -- the same voice talking over itself.
        // And since the line now waits half a second before starting, an answer inside that window
        // would otherwise be FOLLOWED by "checking your records now". Measured reason to expect a
        // fast answer: ElevenLabs STT took the clip stage from 3.7-7.7 s down to 0.9-1.4 s.
        stopFiller()
        probe("ask: playing answer")
        val generation = core.generation
        armPlayWatchdog(generation)
        player.play(bytes) { ok ->
            scope.launch {
                // A callback from an interrupted ask's player must not end the NEW ask.
                if (generation != core.generation) return@launch
                playTimer?.cancel(); playTimer = null
                execute(if (ok) core.onPlaybackDone() else core.onError())
            }
        }
    }

    /**
     * Nothing else bounds playback. [AskPlayer] reports normal completion, an async error and a
     * setup throw — a MediaPlayer that stalls without erroring reports none of them, and the ask
     * would sit in Playing forever, wedging Site-voice too.
     *
     * Generous on purpose: this is a stuck-process guard, not a latency budget. An answer clip is
     * seconds long, so two minutes cannot fire on a healthy playback. [AskPlayer.release] drops
     * the pending callback, so the player is released first and the FSM is moved by the timeout
     * itself. Mirrors [armCap]; [core] is only ever mutated from this single-threaded scope.
     */
    private fun armPlayWatchdog(generation: Int) {
        playTimer?.cancel()
        playTimer = scope.launch {
            delay(PLAY_CAP_MILLIS)
            if (generation != core.generation) return@launch
            probe("ask: playback watchdog fired — the player never called back")
            player.release()
            execute(core.onPlaybackTimeout())
        }
    }

    /**
     * Ordering here is load-bearing, not stylistic.
     *
     * `discard()` frees Ask's microphone; `end()` reopens the capture one. On this
     * single-capture ROM the reopen must come SECOND -- run it while Ask still holds the mic and
     * `buildMic()` fails, which leaves `audioHandover` true inside SegmentRecorder while
     * `handoverActive` is already false, and the video segment is then stuck silent with nothing
     * left to recover it. `SiteVoiceManager.fail()` encodes the same order.
     *
     * `core.onError()` also emits ReleaseMicToCapture, so `end()` runs twice on this path. It is
     * idempotent, and a double release is free where a missed one is not.
     */
    private suspend fun fail() {
        recorder.discard()
        capTimer?.cancel(); capTimer = null
        playTimer?.cancel(); playTimer = null
        micHandover.end()
        execute(core.onError())
    }

    fun shutdown() {
        turnJob?.cancel()
        capTimer?.cancel()
        playTimer?.cancel()
        filler.cancel()
        // The ONLY release on this path: player.release() below drops the pending onDone, so no
        // command list is ever produced to carry a ReleaseMicToCapture.
        micHandover.end()
        recorder.discard()
        player.release()
        sounds.release()
    }

    companion object {
        const val CAP_MILLIS = 15_000L  // recording cap (spec §9)
        /** Playback watchdog. Not a latency budget — see [armPlayWatchdog]. */
        const val PLAY_CAP_MILLIS = 120_000L
        /** Pause between the key going down and "Ready. Go ahead." Owner, 2026-10-13: too rushed. */
        const val LISTENING_CUE_DELAY_MS = 500L
        /** Pause between the key coming up and "Got it. Checking…". Owner, 2026-10-13: same reason. */
        const val FILLER_DELAY_MS = 500L
    }
}
