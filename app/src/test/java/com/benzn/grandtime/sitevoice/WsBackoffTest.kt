package com.benzn.grandtime.sitevoice

import org.junit.Assert.assertEquals
import org.junit.Test

class WsBackoffTest {
    @Test fun first_attempt_is_base() = assertEquals(1_000L, WsBackoff.delayMillis(0))
    @Test fun doubles_each_attempt() {
        assertEquals(2_000L, WsBackoff.delayMillis(1))
        assertEquals(4_000L, WsBackoff.delayMillis(2))
        assertEquals(8_000L, WsBackoff.delayMillis(3))
    }
    @Test fun caps_at_thirty_seconds() {
        assertEquals(30_000L, WsBackoff.delayMillis(6))   // 64s uncapped -> capped
        assertEquals(30_000L, WsBackoff.delayMillis(100)) // no overflow
    }
    @Test fun negative_attempt_treated_as_zero() = assertEquals(1_000L, WsBackoff.delayMillis(-5))
}
