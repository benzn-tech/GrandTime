package com.benzn.grandtime.capture

sealed interface CaptureState {
    data object Idle : CaptureState
    data class RecordingVideo(val sessionId: String, val segmentIndex: Int, val startedAtMillis: Long) : CaptureState
    /** startedAtMillis = the REC-timer origin (excludes prior pauses, see resume shift); pausedAtMillis
     *  = when this pause began, used on resume to shift the origin forward by the pause duration so the
     *  timer counts actual recording time, not wall-clock. */
    data class PausedVideo(val sessionId: String, val segmentIndex: Int, val startedAtMillis: Long, val pausedAtMillis: Long) : CaptureState
    data class RecordingAudio(val sessionId: String, val startedAtMillis: Long) : CaptureState
    /** Mirrors PausedVideo's semantics for audio: startedAtMillis = the REC-timer origin (excludes
     *  prior pauses, see resume shift); pausedAtMillis = when this pause began, used on resume to
     *  shift the origin forward by the pause duration so the timer counts actual recording time. */
    data class PausedAudio(val sessionId: String, val startedAtMillis: Long, val pausedAtMillis: Long) : CaptureState
}
