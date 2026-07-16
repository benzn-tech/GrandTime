package com.benzn.grandtime.ask

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

    fun onPttDown(videoRecording: Boolean): List<AskCommand> = when (state) {
        AskState.Idle ->
            if (videoRecording) {
                listOf(AskCommand.PlayBusyCue)  // mic exclusivity: no-op ask
            } else {
                state = AskState.Listening
                listOf(AskCommand.PlayListeningCue, AskCommand.StartRecording, AskCommand.ArmCapTimer)
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
        return listOf(AskCommand.CancelCapTimer, AskCommand.PlayErrorCue)
    }

    fun onPlaybackDone(): List<AskCommand> {
        if (state == AskState.Playing) state = AskState.Idle
        return emptyList()
    }

    /** Discrete (tap) trigger for a keymap-routed hard key (Task 13): toggles
     * start-listening / stop-and-send, so a rebound key works without hold. */
    fun onDiscreteAsk(videoRecording: Boolean): List<AskCommand> = when (state) {
        AskState.Idle -> onPttDown(videoRecording)
        AskState.Listening -> onPttUp()
        else -> emptyList()
    }
}
