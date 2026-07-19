package com.benzn.grandtime.sitevoice

/**
 * Lets Site-voice temporarily borrow the microphone from the capture pipeline while a video
 * recording is active. Implemented by CaptureManager; keeps SiteVoiceManager decoupled from
 * capture/. Both methods are idempotent and safe to call when no video recording is active (no-op).
 */
interface MicHandover {
    /** Pause the active video segment's mic (its audio goes silent) so Site-voice can record.
     *  Returns true when the handover engaged OR safely no-op'd — i.e. the mic is available to
     *  Site-voice either way. */
    suspend fun begin(): Boolean

    /** Return the mic to the video segment (real audio resumes). Idempotent no-op when no handover
     *  is active. Must never crash the video recording (a failed mic reopen leaves it silent). */
    suspend fun end()
}
