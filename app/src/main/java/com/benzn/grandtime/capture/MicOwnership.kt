package com.benzn.grandtime.capture

import java.util.concurrent.atomic.AtomicInteger

/**
 * Who is physically holding the microphone right now, for the benefit of [MicSilenceMonitor].
 *
 * Deliberately NOT `AppState.askActive`. That flag stays true until the answer finishes
 * playing, while the microphone is released at the top of `sendClip()` — reading it here would
 * blind the detector for the whole thinking-plus-playback window, tens of seconds, which is
 * exactly the stretch where a real fault would be written off as "Ask had the mic".
 *
 * This is scoped to the physical hold: raised when the clip recorder actually starts, lowered
 * the moment it stops or discards.
 *
 * A counter rather than a boolean so an unbalanced release cannot leave it stuck low while
 * another feature still holds the mic. Ask and Site-voice are mutually exclusive today; that
 * is enforced elsewhere and this does not depend on it staying true.
 */
interface MicHold {
    fun acquire()
    fun release()
    val heldByVoiceFeature: Boolean
}

object MicOwnership : MicHold {
    private val holders = AtomicInteger(0)

    override fun acquire() { holders.incrementAndGet() }

    /** Floored at zero: a stray release must not push the count negative, which would then
     *  swallow the next genuine acquire and silently mis-annotate a real fault. */
    override fun release() { holders.updateAndGet { if (it > 0) it - 1 else 0 } }

    override val heldByVoiceFeature: Boolean get() = holders.get() > 0

    /** Tests only — the process-wide counter would otherwise leak between test methods. */
    internal fun resetForTest() { holders.set(0) }
}
