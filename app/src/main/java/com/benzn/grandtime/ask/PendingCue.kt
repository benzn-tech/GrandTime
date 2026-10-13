package com.benzn.grandtime.ask

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A sound that starts [delayMs] after [schedule] unless [cancel] runs first.
 *
 * Exists for one race. The spoken "Got it. Checking your records now." waits half a second after
 * the key is released, so it no longer lands on top of the release itself. During that half second
 * the line has not started yet, so stopping the PLAYER is not enough to silence it: an answer or an
 * error cue that arrives inside the window would play, and the filler would then start AFTER it --
 * the device announcing it is about to look something up once it has already answered. [cancel]
 * covers both halves, the pending start and anything already playing, through the one call.
 *
 * Not awaited by anything. The filler covers a wait; making the wait wait for it would add its own
 * length to the thing it exists to hide.
 */
internal class PendingCue(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val play: () -> Unit,
) {
    private var job: Job? = null

    /** Start [play] after the delay. A second call replaces the first rather than queueing it. */
    fun schedule() {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            play()
        }
    }

    /** Drop a start that has not happened yet. A no-op when nothing is pending. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
