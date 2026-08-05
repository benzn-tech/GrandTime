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

/**
 * The session this state belongs to, or null when idle.
 *
 * Exists so callers stop re-deriving it with a `when` over five branches — one
 * of which is always the branch someone forgets. The meeting code shown to
 * another device must be the CURRENT session's id, and a paused recording is
 * still that session.
 */
fun CaptureState.sessionIdOrNull(): String? = when (this) {
    is CaptureState.RecordingVideo -> sessionId
    is CaptureState.PausedVideo -> sessionId
    is CaptureState.RecordingAudio -> sessionId
    is CaptureState.PausedAudio -> sessionId
    CaptureState.Idle -> null
}
