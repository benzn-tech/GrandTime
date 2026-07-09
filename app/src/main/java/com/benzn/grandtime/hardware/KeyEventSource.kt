package com.benzn.grandtime.hardware

import kotlinx.coroutines.flow.SharedFlow

data class RawBroadcast(val action: String, val timestampMillis: Long)

interface KeyEventSource {
    val keyPresses: SharedFlow<KeyPress>
    val rawEvents: SharedFlow<RawBroadcast>
    fun start()
    fun stop()
}
