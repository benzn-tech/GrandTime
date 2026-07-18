package com.benzn.grandtime.sitevoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Raw direction of a Site-voice (SOS) key event. */
enum class SosDirection { DOWN, UP }

/**
 * Dedicated source for the F2SP SOS key, repurposed as the Site-voice hold-to-talk key.
 * The ROM emits `lolaage.sos.down` / `lolaage.sos.up`; [com.benzn.grandtime.hardware.F2spKeyEventSource]
 * deliberately does NOT register `lolaage.sos` (去对讲化), the same treatment PTT already gets, so
 * this source emits raw down/up directly for hold-until-release.
 *
 * Compliance: `lolaage.*` is the ROM's public broadcast interface; clean re-implementation.
 * Collectors must subscribe to [events] BEFORE start() — MutableSharedFlow(replay=0) drops
 * emissions with no subscribers.
 */
class SosKeySource(private val context: Context) {

    private val _events = MutableSharedFlow<SosDirection>(extraBufferCapacity = 16)
    val events: SharedFlow<SosDirection> = _events

    companion object {
        const val ACTION_DOWN = "lolaage.sos.down"
        const val ACTION_UP = "lolaage.sos.up"

        fun parse(action: String): SosDirection? = when (action) {
            ACTION_DOWN -> SosDirection.DOWN
            ACTION_UP -> SosDirection.UP
            else -> null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            parse(intent.action ?: return)?.let { _events.tryEmit(it) }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_DOWN)
            addAction(ACTION_UP)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
