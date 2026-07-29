package com.benzn.grandtime.capture

import com.benzn.grandtime.keymap.KeyAction

enum class StopReason { ROLLOVER, PAUSE, END }

sealed interface CaptureCommand {
    data class StartVideoSegment(val sessionId: String, val segmentIndex: Int) : CaptureCommand
    data class StopVideo(val reason: StopReason) : CaptureCommand
    data class EndPausedSession(val sessionId: String) : CaptureCommand
    data class TakePhoto(val sessionId: String) : CaptureCommand
    data class StartAudio(val sessionId: String) : CaptureCommand
    data object StopAudio : CaptureCommand
    data object ToggleTorch : CaptureCommand
    data object CycleVolume : CaptureCommand
    data class Vibrate(val times: Int) : CaptureCommand
    data class Notify(val text: String) : CaptureCommand
}

/**
 * 纯决策核:spec §2 互斥表的唯一实现。无 Android 依赖;
 * 调用方(CaptureManager)负责单线程串行调用。
 */
class CaptureCore(
    private val clock: () -> Long,
    private val newId: () -> String,
) {
    var state: CaptureState = CaptureState.Idle
        private set

    fun onAction(action: KeyAction): List<CaptureCommand> = when (action) {
        KeyAction.START_STOP_VIDEO -> when (val s = state) {
            is CaptureState.Idle -> {
                val session = newId()
                state = CaptureState.RecordingVideo(session, 1, clock())
                listOf(
                    CaptureCommand.StartVideoSegment(session, 1),
                    CaptureCommand.Vibrate(1),
                    CaptureCommand.Notify("Recording video"),
                )
            }
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(StopReason.PAUSE))
            is CaptureState.PausedVideo -> {
                val next = s.segmentIndex + 1
                state = CaptureState.RecordingVideo(s.sessionId, next, s.startedAtMillis)
                listOf(CaptureCommand.StartVideoSegment(s.sessionId, next), CaptureCommand.Vibrate(1), CaptureCommand.Notify("Recording video"))
            }
            is CaptureState.RecordingAudio -> listOf(
                CaptureCommand.Vibrate(2),
                CaptureCommand.Notify("Stop audio recording first"),
            )
        }
        KeyAction.END_VIDEO -> when (val s = state) {
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(StopReason.END))
            is CaptureState.PausedVideo -> {
                state = CaptureState.Idle
                listOf(CaptureCommand.EndPausedSession(s.sessionId), CaptureCommand.Vibrate(1), CaptureCommand.Notify("Standing by"))
            }
            else -> emptyList()
        }
        KeyAction.TAKE_PHOTO -> when (val s = state) {
            is CaptureState.Idle -> listOf(CaptureCommand.TakePhoto(newId()), CaptureCommand.Vibrate(1))
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.TakePhoto(s.sessionId), CaptureCommand.Vibrate(1))
            is CaptureState.PausedVideo -> listOf(CaptureCommand.TakePhoto(s.sessionId), CaptureCommand.Vibrate(1))
            is CaptureState.RecordingAudio -> listOf(CaptureCommand.TakePhoto(s.sessionId), CaptureCommand.Vibrate(1))
        }
        KeyAction.START_STOP_AUDIO -> when (state) {
            is CaptureState.Idle -> {
                val session = newId()
                state = CaptureState.RecordingAudio(session, clock())
                listOf(
                    CaptureCommand.StartAudio(session),
                    CaptureCommand.Vibrate(1),
                    CaptureCommand.Notify("Recording audio"),
                )
            }
            is CaptureState.RecordingAudio -> listOf(CaptureCommand.StopAudio)
            is CaptureState.RecordingVideo -> listOf(
                CaptureCommand.Vibrate(2),
                CaptureCommand.Notify("Stop video recording first"),
            )
            is CaptureState.PausedVideo -> listOf(
                CaptureCommand.Vibrate(2),
                CaptureCommand.Notify("Stop video recording first"),
            )
        }
        KeyAction.TOGGLE_TORCH -> listOf(CaptureCommand.ToggleTorch)
        KeyAction.ADJUST_VOLUME -> listOf(CaptureCommand.CycleVolume)
        else -> emptyList()
    }

    fun onSegmentTimerFired(): List<CaptureCommand> = when (state) {
        is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(StopReason.ROLLOVER))
        else -> emptyList()
    }

    fun onVideoFinalized(reason: StopReason): List<CaptureCommand> = when (val s = state) {
        is CaptureState.RecordingVideo -> when (reason) {
            StopReason.ROLLOVER -> {
                val next = s.segmentIndex + 1
                // Preserve the ORIGINAL session start across ~1-min segment rollovers (do NOT reset to
                // clock()): startedAtMillis drives the on-screen REC timer, which must show continuous
                // session time, not restart from 00:00 every minute at each segment boundary.
                state = CaptureState.RecordingVideo(s.sessionId, next, s.startedAtMillis)
                listOf(CaptureCommand.StartVideoSegment(s.sessionId, next))
            }
            StopReason.PAUSE -> {
                state = CaptureState.PausedVideo(s.sessionId, s.segmentIndex, s.startedAtMillis)
                listOf(CaptureCommand.Vibrate(1), CaptureCommand.Notify("Paused"))
            }
            StopReason.END -> {
                state = CaptureState.Idle
                listOf(CaptureCommand.Vibrate(1), CaptureCommand.Notify("Standing by"))
            }
        }
        else -> emptyList()
    }

    fun onAudioFinalized(): List<CaptureCommand> {
        state = CaptureState.Idle
        return listOf(CaptureCommand.Vibrate(1), CaptureCommand.Notify("Standing by"))
    }

    fun onFailure(message: String): List<CaptureCommand> {
        state = CaptureState.Idle
        return listOf(CaptureCommand.Vibrate(2), CaptureCommand.Notify(message))
    }
}
