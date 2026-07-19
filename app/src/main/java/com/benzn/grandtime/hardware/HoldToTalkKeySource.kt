package com.benzn.grandtime.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Thin `BroadcastReceiver` wrapper around a [HoldToTalkGate]: registers for the ROM's
 * `lolaage.*` [downAction]/[upAction] broadcast pair and maps them onto the gate's
 * pure timer state machine. All hold-to-activate debounce logic lives in
 * [HoldToTalkGate] — this class only owns receiver lifecycle (device-verified, no
 * JVM test).
 *
 * Compliance: `lolaage.*` is the ROM's public broadcast interface; clean
 * re-implementation, no com.corget decompiled code.
 *
 * Collectors MUST subscribe to [events] BEFORE [start] — MutableSharedFlow(replay=0)
 * drops emissions with no subscribers.
 */
class HoldToTalkKeySource(
    private val context: Context,
    private val downAction: String,
    private val upAction: String,
    scope: CoroutineScope,
    holdMillis: Long = 1000L,
) {
    private val gate = HoldToTalkGate(scope, holdMillis)
    val events: SharedFlow<HoldDirection> get() = gate.events

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                downAction -> gate.onDown()
                upAction -> gate.onUp()
                // else: not ours — ignore
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(downAction)
            addAction(upAction)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun stop() {
        gate.reset()
        runCatching { context.unregisterReceiver(receiver) }
    }
}
