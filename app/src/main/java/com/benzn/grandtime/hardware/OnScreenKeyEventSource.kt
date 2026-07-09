package com.benzn.grandtime.hardware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 屏幕按钮源:普通手机/模拟器退化方案,复用同一套短/长按判定。 */
class OnScreenKeyEventSource(scope: CoroutineScope) : KeyEventSource {

    private val detector = PressTypeDetector(scope)
    override val keyPresses: SharedFlow<KeyPress> = detector.keyPresses

    private val _rawEvents = MutableSharedFlow<RawBroadcast>(extraBufferCapacity = 64)
    override val rawEvents: SharedFlow<RawBroadcast> = _rawEvents

    override fun start() {}
    override fun stop() {}

    fun onScreenKey(key: HardKey, direction: RawDirection) {
        _rawEvents.tryEmit(
            RawBroadcast("screen.${key.name.lowercase()}.${direction.name.lowercase()}", System.currentTimeMillis())
        )
        detector.onRawEvent(key, direction)
    }
}
