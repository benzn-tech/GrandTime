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
    /** Borrow the mic from an active video segment (video audio goes silent). Emitted before
     *  StartRecording only when a video recording is active at SOS-down. */
    data object AcquireMicFromCapture : SiteVoiceCommand
    /** Return the mic to the video segment (real audio resumes). Emitted after StopRecording. */
    data object ReleaseMicToCapture : SiteVoiceCommand
    data class PlayClip(val clip: VoiceClip) : SiteVoiceCommand
}

enum class SiteVoiceState { Idle, Recording, Sending, Playing }

/**
 * Pure decision core for Site voice. No Android deps; the caller (SiteVoiceManager) serializes
 * calls on one dispatcher and executes the commands. Owns:
 *  - the hold-to-talk send path (Recording -> Sending), with a ~15s cap;
 *  - mic arbitration: yield (busy cue) to an active Ask talk; during a video recording, BORROW the
 *    mic from the capture pipeline (AcquireMicFromCapture) rather than refusing; ignore re-entrant
 *    SOS while already busy — one talk at a time;
 *  - the inbound queue: a clip arriving while the user is Recording/Sending/Playing is queued and
 *    played after (the sender never hears its own — excluded server-side at fanout).
 */
class SiteVoiceCore {
    var state: SiteVoiceState = SiteVoiceState.Idle
        private set

    private val queue = ArrayDeque<VoiceClip>()
    val queueSize: Int get() = queue.size

    /** True while the current talk borrowed the mic from an active video segment; drives the
     *  ReleaseMicToCapture emission on stop. Recomputed on every onSosDown. */
    private var borrowedMic = false

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

    fun onError(): List<SiteVoiceCommand> {
        borrowedMic = false
        return drainOrIdle(listOf(SiteVoiceCommand.CancelCapTimer, SiteVoiceCommand.PlayErrorCue))
    }

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
