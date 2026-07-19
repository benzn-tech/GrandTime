package com.benzn.grandtime.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * F2SP ROM 私有广播源。合规说明:lolaage.* 是 ROM 对外广播接口,
 * 此处为干净重写,不含任何 com.corget 反编译代码。
 * 不注册 lolaage.ptt.*(去对讲化)。
 *
 * Collectors must subscribe to keyPresses/rawEvents BEFORE start() is called — MutableSharedFlow(replay=0) drops emissions with no subscribers.
 */
class F2spKeyEventSource(
    private val context: Context,
    scope: CoroutineScope,
) : KeyEventSource {

    private val detector = PressTypeDetector(scope)
    override val keyPresses: SharedFlow<KeyPress> = detector.keyPresses

    private val _rawEvents = MutableSharedFlow<RawBroadcast>(extraBufferCapacity = 64)
    override val rawEvents: SharedFlow<RawBroadcast> = _rawEvents

    companion object {
        /** action 前缀(.down/.up 之前的部分)→ 键。真机若有出入只改这张表。 */
        val KEY_ACTION_PREFIXES: Map<String, HardKey> = mapOf(
            "lolaage.video1" to HardKey.VIDEO,
            "lolaage.take.picture" to HardKey.PHOTO,
            "lolaage.audio" to HardKey.AUDIO,
        )

        /** 只进探针、不进状态机的广播(逆向记录名 + 可能的带前缀变体都注册,收到多少算多少)。 */
        val PROBE_ONLY_ACTIONS: List<String> = listOf(
            "lolaage.light",
            "lolaage.switch.group", "switch.group",
            "lolaage.volume", "volume",
            "lolaage.ir.led", "ir.led",
            "lolaage.ircut.switch", "ircut.switch",
            "lolaage.rayled.set", "rayled.set",
        )

        fun parse(action: String): Pair<HardKey, RawDirection>? {
            val (prefix, direction) = when {
                action.endsWith(".down") -> action.removeSuffix(".down") to RawDirection.DOWN
                action.endsWith(".up") -> action.removeSuffix(".up") to RawDirection.UP
                else -> return null
            }
            val key = KEY_ACTION_PREFIXES[prefix] ?: return null
            return key to direction
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            _rawEvents.tryEmit(RawBroadcast(action, System.currentTimeMillis()))
            parse(action)?.let { (key, direction) -> detector.onRawEvent(key, direction) }
        }
    }

    override fun start() {
        val filter = IntentFilter().apply {
            KEY_ACTION_PREFIXES.keys.forEach {
                addAction("$it.down")
                addAction("$it.up")
            }
            PROBE_ONLY_ACTIONS.forEach {
                addAction(it)
                addAction("$it.down")
                addAction("$it.up")
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
