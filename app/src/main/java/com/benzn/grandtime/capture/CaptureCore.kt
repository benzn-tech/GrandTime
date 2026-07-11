package com.benzn.grandtime.capture

import com.benzn.grandtime.keymap.KeyAction

sealed interface CaptureCommand {
    data class StartVideoSegment(val sessionId: String, val segmentIndex: Int) : CaptureCommand
    data class StopVideo(val rollToNext: Boolean) : CaptureCommand
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
        KeyAction.START_STOP_VIDEO -> when (state) {
            is CaptureState.Idle -> {
                val session = newId()
                state = CaptureState.RecordingVideo(session, 1, clock())
                listOf(
                    CaptureCommand.StartVideoSegment(session, 1),
                    CaptureCommand.Vibrate(1),
                    CaptureCommand.Notify("Recording video"),
                )
            }
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(rollToNext = false))
            is CaptureState.RecordingAudio -> listOf(
                CaptureCommand.Vibrate(2),
                CaptureCommand.Notify("Stop audio recording first"),
            )
        }
        KeyAction.TAKE_PHOTO -> when (val s = state) {
            is CaptureState.Idle -> listOf(CaptureCommand.TakePhoto(newId()), CaptureCommand.Vibrate(1))
            is CaptureState.RecordingVideo -> listOf(CaptureCommand.TakePhoto(s.sessionId), CaptureCommand.Vibrate(1))
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
        }
        KeyAction.TOGGLE_TORCH -> listOf(CaptureCommand.ToggleTorch)
        KeyAction.ADJUST_VOLUME -> listOf(CaptureCommand.CycleVolume)
        else -> emptyList()
    }

    fun onSegmentTimerFired(): List<CaptureCommand> = when (state) {
        is CaptureState.RecordingVideo -> listOf(CaptureCommand.StopVideo(rollToNext = true))
        else -> emptyList()
    }

    fun onVideoFinalized(rollToNext: Boolean): List<CaptureCommand> = when (val s = state) {
        is CaptureState.RecordingVideo ->
            if (rollToNext) {
                val next = s.segmentIndex + 1
                state = CaptureState.RecordingVideo(s.sessionId, next, clock())
                listOf(CaptureCommand.StartVideoSegment(s.sessionId, next))
            } else {
                state = CaptureState.Idle
                listOf(CaptureCommand.Vibrate(1), CaptureCommand.Notify("Standing by"))
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
