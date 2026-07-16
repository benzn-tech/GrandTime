package com.benzn.grandtime.ask

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Raw direction of a push-to-talk key event. */
enum class PttDirection { DOWN, UP }

/**
 * Dedicated source for the F2SP push-to-talk key. The ROM emits
 * `lolaage.ptt.down` / `lolaage.ptt.up` broadcasts, which [F2spKeyEventSource]
 * deliberately does NOT register (去对讲化). SP-Ask needs hold-until-release,
 * which [PressTypeDetector] (SHORT/LONG only) cannot express, so this source
 * emits raw down/up directly.
 *
 * Compliance: `lolaage.*` is the ROM's public broadcast interface; this is a
 * clean re-implementation, no com.corget decompiled code.
 *
 * Collectors must subscribe to [events] BEFORE start() — MutableSharedFlow
 * (replay=0) drops emissions with no subscribers.
 */
class PttKeySource(private val context: Context) {

    private val _events = MutableSharedFlow<PttDirection>(extraBufferCapacity = 16)
    val events: SharedFlow<PttDirection> = _events

    companion object {
        const val ACTION_DOWN = "lolaage.ptt.down"
        const val ACTION_UP = "lolaage.ptt.up"

        fun parse(action: String): PttDirection? = when (action) {
            ACTION_DOWN -> PttDirection.DOWN
            ACTION_UP -> PttDirection.UP
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
