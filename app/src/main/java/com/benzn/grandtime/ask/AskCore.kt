package com.benzn.grandtime.ask

import com.benzn.grandtime.hardware.VibePattern

/** Audio-only side effects (no screen UI — spec §2.4/§2.5). */
sealed interface AskCommand {
    data object PlayListeningCue : AskCommand
    data object PlayThinkingCue : AskCommand
    data object PlayBusyCue : AskCommand
    data object PlayErrorCue : AskCommand
    data object StartRecording : AskCommand
    data object StopRecording : AskCommand
    data object SendClip : AskCommand
    data object ArmCapTimer : AskCommand
    data object CancelCapTimer : AskCommand
    data class PlayAnswer(val audioBase64: String) : AskCommand
    data class Vibrate(val pattern: VibePattern) : AskCommand
}

enum class AskState { Idle, Listening, Thinking, Playing }

/**
 * Pure decision core for one hands-free ask. No Android deps; the caller
 * (AskManager) serializes calls on one dispatcher and executes the commands.
 * Mic-exclusion (refuse when a video recording is active), the ~15s cap
 * transition, and error→cue transitions all live here (spec §8).
 */
class AskCore {
    var state: AskState = AskState.Idle
        private set

    fun onPttDown(videoRecording: Boolean, siteVoiceActive: Boolean = false): List<AskCommand> = when (state) {
        AskState.Idle ->
            if (videoRecording || siteVoiceActive) {
                // Refusal was audible-only, which is the one channel a chest-worn
                // device cannot rely on. Two buzzes is what refusal already means here.
                listOf(AskCommand.PlayBusyCue, AskCommand.Vibrate(VibePattern.DOUBLE_SHORT))
            } else {
                state = AskState.Listening
                // The buzz comes AFTER StartRecording, not before: the executor
                // short-circuits when the recorder cannot open the mic, so a buzz
                // ordered first would claim "accepted" for a talk that never began.
                listOf(AskCommand.PlayListeningCue, AskCommand.StartRecording,
                       AskCommand.Vibrate(VibePattern.SHORT), AskCommand.ArmCapTimer)
            }
        else -> emptyList()  // ignore re-entrant down mid-ask
    }

    fun onPttUp(): List<AskCommand> = when (state) {
        AskState.Listening -> {
            state = AskState.Thinking
            listOf(AskCommand.CancelCapTimer, AskCommand.StopRecording,
                   AskCommand.PlayThinkingCue, AskCommand.SendClip)
        }
        else -> emptyList()
    }

    fun onCapReached(): List<AskCommand> = when (state) {
        AskState.Listening -> {
            state = AskState.Thinking
            listOf(AskCommand.StopRecording, AskCommand.PlayThinkingCue, AskCommand.SendClip)
        }
        else -> emptyList()
    }

    fun onAnswer(audioBase64: String): List<AskCommand> = when (state) {
        AskState.Thinking -> {
            state = AskState.Playing
            listOf(AskCommand.PlayAnswer(audioBase64))
        }
        else -> emptyList()
    }

    fun onError(): List<AskCommand> {
        state = AskState.Idle
        return listOf(AskCommand.CancelCapTimer, AskCommand.PlayErrorCue,
                      AskCommand.Vibrate(VibePattern.DOUBLE_SHORT))
    }

    /** The answer has played and the microphone is back. This arrives unprompted,
     *  seconds after the key was released, so it is the one moment the operator has
     *  no other way to learn about. */
    fun onPlaybackDone(): List<AskCommand> {
        if (state != AskState.Playing) return emptyList()
        state = AskState.Idle
        return listOf(AskCommand.Vibrate(VibePattern.LONG))
    }

    /**
     * The player never called back.
     *
     * [AskPlayer] has three completion paths — normal, async error, setup throw — and a
     * MediaPlayer that stalls without erroring hits none of them. Nothing else in this feature
     * is bounded (only the API call has a timeout), so without this the FSM stays in Playing
     * forever: `AppState.askActive` sticks true, Site-voice refuses every press and Ask ignores
     * every press, until the service is restarted.
     *
     * Guarded on Playing so a timer that loses the cancel race after a normal finish is a
     * no-op — otherwise a good answer would be chased by a refusal buzz.
     */
    fun onPlaybackTimeout(): List<AskCommand> {
        if (state != AskState.Playing) return emptyList()
        return onError()  // a stuck playback IS a failure: same cues, same buzz, same Idle
    }

    /** Discrete (tap) trigger for a keymap-routed hard key (Task 13): toggles
     * start-listening / stop-and-send, so a rebound key works without hold. */
    fun onDiscreteAsk(videoRecording: Boolean, siteVoiceActive: Boolean = false): List<AskCommand> = when (state) {
        AskState.Idle -> onPttDown(videoRecording, siteVoiceActive)
        AskState.Listening -> onPttUp()
        else -> emptyList()
    }
}
