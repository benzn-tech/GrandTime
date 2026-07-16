package com.benzn.grandtime.ask

import android.content.Context
import android.util.Base64
import com.benzn.grandtime.auth.AuthManager
import com.benzn.grandtime.capture.CaptureState
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.keymap.KeyAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates one hands-free voice ask: PttKeySource down/up -> AskCore ->
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
    private val probe: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val core = AskCore()
    private val recorder = AskRecorder(appContext, File(appContext.cacheDir, "ask"))
    private val api = AskApiClient(apiBaseUrl)
    private val sounds = AskSounds(appContext)
    private val player = AskPlayer(File(appContext.cacheDir, "ask"))
    private var capTimer: Job? = null

    private val videoRecording: Boolean
        get() = AppState.captureState.value is CaptureState.RecordingVideo

    val handledActions: Set<KeyAction> = setOf(KeyAction.ASK_AGENT)

    fun onPttDown() = run { core.onPttDown(videoRecording) }
    fun onPttUp() = run { core.onPttUp() }
    /** Keymap-routed discrete tap (Task 13). */
    fun onDiscreteAsk() = run { core.onDiscreteAsk(videoRecording) }

    private fun run(commands: List<AskCommand>) {
        scope.launch { execute(commands) }
    }

    private suspend fun execute(commands: List<AskCommand>) {
        for (cmd in commands) when (cmd) {
            AskCommand.PlayListeningCue -> { probe("ask: listening"); sounds.listening() }
            AskCommand.PlayThinkingCue -> sounds.thinking()
            AskCommand.PlayBusyCue -> { probe("ask: busy (video active)"); sounds.error() }
            AskCommand.PlayErrorCue -> { probe("ask: error"); sounds.error() }
            AskCommand.StartRecording -> if (!recorder.start()) fail()
            AskCommand.StopRecording -> { /* clip read in SendClip */ }
            AskCommand.ArmCapTimer -> armCap()
            AskCommand.CancelCapTimer -> { capTimer?.cancel(); capTimer = null }
            AskCommand.SendClip -> sendClip()
            is AskCommand.PlayAnswer -> playAnswer(cmd.audioBase64)
        }
    }

    private fun armCap() {
        capTimer?.cancel()
        capTimer = scope.launch {
            delay(CAP_MILLIS)
            execute(core.onCapReached())
        }
    }

    private suspend fun sendClip() {
        val clip = recorder.stop()
        if (clip == null) { fail(); return }
        val bytes = runCatching { clip.readBytes() }.getOrNull()
        clip.delete()
        if (bytes == null || bytes.isEmpty()) { fail(); return }
        val token = auth.freshIdToken()
        if (token == null) { fail(); return }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val result = withContext(Dispatchers.IO) { api.ask(token, b64, "m4a") }
        when (result) {
            is AskApiClient.AskResult.Ok -> execute(core.onAnswer(result.audioBase64))
            else -> { probe("ask: send failed ($result)"); fail() }
        }
    }

    private suspend fun playAnswer(audioBase64: String) {
        val bytes = runCatching { Base64.decode(audioBase64, Base64.DEFAULT) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) { fail(); return }
        probe("ask: playing answer")
        player.play(bytes) { scope.launch { execute(core.onPlaybackDone()) } }
    }

    private suspend fun fail() {
        recorder.discard()
        capTimer?.cancel(); capTimer = null
        execute(core.onError())
    }

    fun shutdown() {
        capTimer?.cancel()
        recorder.discard()
        player.release()
        sounds.release()
    }

    companion object {
        const val CAP_MILLIS = 15_000L  // recording cap (spec §9)
    }
}
