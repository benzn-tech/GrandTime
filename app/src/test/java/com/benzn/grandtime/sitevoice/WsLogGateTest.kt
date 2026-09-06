package com.benzn.grandtime.sitevoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The behaviour these pin is the one the naive rule gets wrong.
 *
 * "Only log on state change" sounds like the fix and is not: a routine API Gateway idle-timeout
 * cycle changes state twice, so that rule keeps every one of the ~76 lines a day this exists to
 * remove. What separates a cycle from an outage is whether the first retry got back in.
 */
class WsLogGateTest {

    /** One routine cycle: the socket drops and the first retry reconnects. */
    private fun WsLogGate.routineCycle(): List<String> = listOfNotNull(
        onDropped(),
        onRetryFailed(attempt = 1, why = "closed 1001"),
        onConnected(),
    )

    @Test
    fun `the first connection is announced`() {
        assertEquals("site-voice: connected", WsLogGate().onConnected())
    }

    @Test
    fun `a routine idle-timeout cycle says nothing at all`() {
        val gate = WsLogGate()
        gate.onConnected()
        assertEquals(emptyList<String>(), gate.routineCycle())
    }

    @Test
    fun `a day of routine cycles costs at most one line`() {
        // ~14 minutes a cycle, so a day is about 100. The whole point is that this does not fill a
        // 200-entry ring; one heartbeat line is the entire budget.
        val gate = WsLogGate()
        gate.onConnected()
        val lines = (1..100).flatMap { gate.routineCycle() }
        assertEquals(1, lines.size)
        assertEquals(100, gate.suppressedCount())
    }

    @Test
    fun `silence is never total — the heartbeat says why it is quiet`() {
        // "Nothing logged" and "client never ran" must not look the same.
        val gate = WsLogGate(heartbeatCycles = 3)
        gate.onConnected()
        val lines = (1..3).flatMap { gate.routineCycle() }
        assertEquals(1, lines.size)
        val line = lines.single()
        assert(line.contains("3")) { "the heartbeat must say how many were suppressed: $line" }
        assert(line.contains("still up")) { "the heartbeat must say the socket is up: $line" }
    }

    @Test
    fun `a real outage is announced, once`() {
        val gate = WsLogGate()
        gate.onConnected()
        assertNull("the drop itself is never the line", gate.onDropped())
        assertNull("the first retry is still the routine cycle", gate.onRetryFailed(1, "failure eof"))
        assertEquals("site-voice: down (failure eof)", gate.onRetryFailed(2, "failure eof"))
        for (n in 3..50) {
            assertNull("a long outage must not repeat itself", gate.onRetryFailed(n, "failure eof"))
        }
    }

    @Test
    fun `recovery from an announced outage is announced`() {
        val gate = WsLogGate()
        gate.onConnected()
        gate.onDropped()
        assertNotNull(gate.onRetryFailed(2, "failure eof"))
        assertEquals("site-voice: connected", gate.onConnected())
    }

    @Test
    fun `an outage does not consume the suppression budget`() {
        // A drop that was announced is not a suppressed cycle; counting it would make the heartbeat
        // overstate how much quiet churn there has been.
        val gate = WsLogGate()
        gate.onConnected()
        gate.onDropped(); gate.onRetryFailed(2, "x"); gate.onConnected()
        assertEquals(0, gate.suppressedCount())
    }

    @Test
    fun `after stop, the next connect is announced again`() {
        val gate = WsLogGate()
        gate.onConnected()
        repeat(5) { gate.routineCycle() }
        gate.onStopped()
        assertEquals("site-voice: connected", gate.onConnected())
        assertEquals(0, gate.suppressedCount())
    }

    @Test
    fun `an unknown reason still produces a readable line`() {
        val gate = WsLogGate()
        gate.onConnected()
        assertEquals("site-voice: down (unknown)", gate.onRetryFailed(2, null))
    }
}
