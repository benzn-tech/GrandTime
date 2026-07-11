package com.benzn.grandtime.capture

sealed interface CaptureState {
    data object Idle : CaptureState
    data class RecordingVideo(val sessionId: String, val segmentIndex: Int, val startedAtMillis: Long) : CaptureState
    data class RecordingAudio(val sessionId: String, val startedAtMillis: Long) : CaptureState
}
