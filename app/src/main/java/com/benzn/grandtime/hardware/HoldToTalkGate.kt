package com.benzn.grandtime.hardware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Direction of a hold-to-talk key after the hold threshold has been applied. */
enum class HoldDirection { DOWN, UP }

/**
 * Pure timer state machine for a hold-to-talk physical key (SOS or PTT), with NO
 * `Context` dependency so it is JVM-unit-testable with a `TestScope`/virtual time.
 *
 * [onDown] starts a [holdMillis] (default 1000ms) timer; a shorter tap is ignored
 * (debounce against accidental presses) — only once the key has been held past the
 * threshold does the timer fire and emit [HoldDirection.DOWN]. [onUp] cancels any
 * pending timer and, only if the timer already fired (activated), emits
 * [HoldDirection.UP]. A repeat [onDown] WITHIN an active hold cycle (timer pending or
 * already activated) is IGNORED — some ROMs auto-repeat the `.down` broadcast while a
 * key is held, and restarting the timer would delay activation, while resetting
 * `activated` after DOWN already fired would drop the eventual UP and leave the
 * consumer's recording stuck until its cap. A fresh cycle begins only after
 * [onUp]/[reset].
 *
 * Collectors MUST subscribe to [events] before the key source starts producing —
 * MutableSharedFlow(replay=0) drops emissions with no subscribers. All state
 * ([pendingTimer], [activated]) is touched only from [onDown]/[onUp] (broadcast/Main
 * thread) and the timer coroutine on [scope] — single-threaded, no lock needed.
 */
class HoldToTalkGate(
    private val scope: CoroutineScope,
    private val holdMillis: Long = 1000L,
) {
    private val _events = MutableSharedFlow<HoldDirection>(extraBufferCapacity = 16)
    val events: SharedFlow<HoldDirection> = _events

    private var pendingTimer: Job? = null
    private var activated = false

    fun onDown() {
        // Ignore a repeat DOWN within an active hold cycle (ROM `.down` auto-repeat while held):
        // pendingTimer stays non-null from onDown until onUp/reset, so this also covers the
        // post-activation case where dropping through would reset `activated` and lose the UP.
        if (pendingTimer != null || activated) return
        pendingTimer = scope.launch {
            delay(holdMillis)
            activated = true
            _events.tryEmit(HoldDirection.DOWN)
        }
    }

    fun onUp() {
        pendingTimer?.cancel()
        pendingTimer = null
        if (activated) _events.tryEmit(HoldDirection.UP)
        activated = false
    }

    /**
     * Cancels any pending timer and clears [activated] WITHOUT emitting an event —
     * used by [HoldToTalkKeySource.stop] on teardown, where a real key release did
     * not necessarily occur and a synthetic [HoldDirection.UP] would be misleading.
     */
    fun reset() {
        pendingTimer?.cancel()
        pendingTimer = null
        activated = false
    }
}
