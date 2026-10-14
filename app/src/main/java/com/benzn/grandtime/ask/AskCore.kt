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
    /** Borrow the mic from an active video segment (its audio goes silent). Emitted before
     *  StartRecording, and only when a video recording is active at key-down. */
    data object AcquireMicFromCapture : AskCommand
    /** Return the mic to the video segment. Emitted on every terminal transition that could
     *  follow a borrow -- NOT at StopRecording, see [borrowedMic]. */
    data object ReleaseMicToCapture : AskCommand
    data class Vibrate(val pattern: VibePattern) : AskCommand
    /** Abandon the in-flight turn: cancel its job and HTTP call, and tell the server (best effort). */
    data object CancelTurn : AskCommand
    /** Silence the filler, the cues and any answer playback. Emitted by an interrupt. */
    data object StopAllAudio : AskCommand
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

    /**
     * True while this ask borrowed the mic from a running video segment.
     *
     * Held from key-down until the answer has finished playing, which is longer than
     * Site-voice holds it. Site-voice returns the mic at StopRecording; Ask cannot, because the
     * mic is released at the top of `sendClip()` -- before the API call -- so returning it there
     * lets the video re-acquire and record **the agent's spoken answer into the evidence video**.
     * A longer gap in the video's audio is the better trade, and it is the same reasoning that
     * moved the "recording started" announcement out of the recording in #16.
     *
     * The cost of that choice is that the borrow now spans two async boundaries, so EVERY
     * terminal transition has to return it. A missed release leaves the video's audio silent for
     * the rest of the recording -- worse than the refusal this replaced. Cleared at the same
     * moment the release is emitted, so no second exchange can re-emit it.
     */
    private var borrowedMic = false

    /**
     * Identity of the current ask. Bumped by every new ask and every interrupt, so anything that
     * was started under an older value is stale. Whoever starts asynchronous work reads this at
     * that moment and hands it back with the result ([onAnswer]); a mismatch means the user has
     * moved on, and the result must change nothing.
     */
    var generation: Int = 0
        private set

    /** Emit the release exactly once, if this ask is holding a borrow. */
    private fun releaseIfBorrowed(): List<AskCommand> =
        if (borrowedMic) {
            borrowedMic = false
            listOf(AskCommand.ReleaseMicToCapture)
        } else {
            emptyList()
        }

    /** What a fresh press does once the mic situation is settled. Order is load-bearing, see below. */
    private fun listeningCommands(): List<AskCommand> = listOf(
        AskCommand.PlayListeningCue,
        // The buzz comes AFTER StartRecording, not before: the executor
        // short-circuits when the recorder cannot open the mic, so a buzz
        // ordered first would claim "accepted" for a talk that never began.
        AskCommand.StartRecording,
        AskCommand.Vibrate(VibePattern.SHORT),
        AskCommand.ArmCapTimer,
    )

    private fun busyRefusal(): List<AskCommand> =
        // The one refusal left. Site-voice is another voice feature holding the mic for
        // a person, not a resource to borrow -- taking it would cut someone off
        // mid-sentence. Refusal was audible-only, which is the one channel a chest-worn
        // device cannot rely on. Two buzzes is what refusal already means here.
        listOf(AskCommand.PlayBusyCue, AskCommand.Vibrate(VibePattern.DOUBLE_SHORT))

    fun onPttDown(videoRecording: Boolean, siteVoiceActive: Boolean = false): List<AskCommand> = when (state) {
        AskState.Idle ->
            if (siteVoiceActive) {
                busyRefusal()
            } else {
                state = AskState.Listening
                generation++
                borrowedMic = videoRecording // borrow only when a video segment is running
                buildList {
                    // Acquire first: the capture mic must be freed before Ask opens its own.
                    if (videoRecording) add(AskCommand.AcquireMicFromCapture)
                    addAll(listeningCommands())
                }
            }
        // Interrupt: the key pressed while the agent is working or talking. The turn is abandoned
        // and a new question starts. A borrowed mic stays borrowed -- no Release/Acquire pair --
        // because on this single-capture ROM the video segment reopens its mic asynchronously, so
        // an end() followed at once by begin() can leave the new StartRecording facing a held
        // mic (the trap fail() in AskManager documents). Listening itself is NOT interruptible:
        // a second press there is the same talk and stays ignored.
        AskState.Thinking, AskState.Playing ->
            if (siteVoiceActive) {
                busyRefusal()
            } else {
                state = AskState.Listening
                generation++
                listOf(AskCommand.CancelTurn, AskCommand.StopAllAudio) + listeningCommands()
            }
        AskState.Listening -> emptyList()  // ignore re-entrant down mid-talk
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

    /**
     * [generation] is the value read when the request that produced this answer was started.
     * Required: a caller that cannot say which ask it belongs to cannot be trusted not to be stale.
     */
    fun onAnswer(audioBase64: String, generation: Int): List<AskCommand> = when {
        generation != this.generation -> emptyList()  // an older turn's answer: the user moved on
        state == AskState.Thinking -> {
            state = AskState.Playing
            listOf(AskCommand.PlayAnswer(audioBase64))
        }
        else -> emptyList()
    }

    fun onError(): List<AskCommand> {
        state = AskState.Idle
        return listOf(AskCommand.CancelCapTimer) + releaseIfBorrowed() +
            listOf(AskCommand.PlayErrorCue, AskCommand.Vibrate(VibePattern.DOUBLE_SHORT))
    }

    /** The answer has played and the microphone is back. This arrives unprompted,
     *  seconds after the key was released, so it is the one moment the operator has
     *  no other way to learn about. */
    fun onPlaybackDone(): List<AskCommand> {
        if (state != AskState.Playing) return emptyList()
        state = AskState.Idle
        // Release BEFORE the buzz: one long buzz means "finished, microphone back", so buzzing
        // first states something that is not true yet.
        return releaseIfBorrowed() + listOf(AskCommand.Vibrate(VibePattern.LONG))
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
        AskState.Idle, AskState.Thinking, AskState.Playing -> onPttDown(videoRecording, siteVoiceActive)
        AskState.Listening -> onPttUp()
    }
}
