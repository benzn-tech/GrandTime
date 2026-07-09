package com.benzn.grandtime.hardware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * down 起 [longPressMillis] 定时器:期间收到 up = SHORT;
 * 定时器到点立即发 LONG(不等松手),其后的 up 只清状态。
 * 所有调用必须来自同一调度器(生产 = 主线程),内部不加锁。
 */
class PressTypeDetector(
    private val scope: CoroutineScope,
    private val longPressMillis: Long = 1000L,
) {
    private val _keyPresses = MutableSharedFlow<KeyPress>(extraBufferCapacity = 16)
    val keyPresses: SharedFlow<KeyPress> = _keyPresses

    private class Pending(val timer: Job) {
        var longFired = false
    }

    private val pending = mutableMapOf<HardKey, Pending>()

    fun onRawEvent(key: HardKey, direction: RawDirection) {
        when (direction) {
            RawDirection.DOWN -> onDown(key)
            RawDirection.UP -> onUp(key)
        }
    }

    private fun onDown(key: HardKey) {
        pending.remove(key)?.timer?.cancel()
        lateinit var entry: Pending
        entry = Pending(scope.launch {
            delay(longPressMillis)
            if (pending[key] === entry) {
                entry.longFired = true
                _keyPresses.tryEmit(KeyPress(key, PressType.LONG))
            }
        })
        pending[key] = entry
    }

    private fun onUp(key: HardKey) {
        val entry = pending.remove(key) ?: return
        entry.timer.cancel()
        if (!entry.longFired) {
            _keyPresses.tryEmit(KeyPress(key, PressType.SHORT))
        }
    }
}
