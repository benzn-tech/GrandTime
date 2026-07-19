package com.benzn.grandtime.sitevoice

/**
 * Pure exponential reconnect-delay calculator for [VoiceWsClient]. Deterministic (no jitter) so it
 * is JVM-testable; the WS shell adds the wall-clock delay. attempt is 0-based (0 = first retry).
 */
object WsBackoff {
    const val BASE_MILLIS = 1_000L
    const val CAP_MILLIS = 30_000L

    fun delayMillis(attempt: Int): Long {
        val n = attempt.coerceIn(0, 30) // 2^30 * base already far exceeds CAP; guards overflow
        val raw = BASE_MILLIS shl n     // BASE * 2^n
        return raw.coerceAtMost(CAP_MILLIS)
    }
}
