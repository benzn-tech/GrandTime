package com.benzn.grandtime.hardware

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HoldToTalkGateTest {

    private fun kotlinx.coroutines.test.TestScope.collectInto(
        gate: HoldToTalkGate,
    ): MutableList<HoldDirection> {
        val events = mutableListOf<HoldDirection>()
        backgroundScope.launch { gate.events.collect { events.add(it) } }
        runCurrent()
        return events
    }

    @Test
    fun `hold past threshold emits DOWN then UP`() = runTest {
        val gate = HoldToTalkGate(backgroundScope)
        val events = collectInto(gate)
        gate.onDown()
        advanceTimeBy(1001)
        runCurrent()
        gate.onUp()
        runCurrent()
        assertEquals(listOf(HoldDirection.DOWN, HoldDirection.UP), events)
    }

    @Test
    fun `short tap before threshold emits nothing`() = runTest {
        val gate = HoldToTalkGate(backgroundScope)
        val events = collectInto(gate)
        gate.onDown()
        advanceTimeBy(500)
        gate.onUp()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(emptyList<HoldDirection>(), events)
    }

    @Test
    fun `repeat down before threshold does not double-fire`() = runTest {
        val gate = HoldToTalkGate(backgroundScope)
        val events = collectInto(gate)
        gate.onDown()
        advanceTimeBy(400)
        gate.onDown() // repeat DOWN within the cycle — ignored, the first timer keeps running
        advanceTimeBy(400)
        runCurrent()
        assertEquals(0, events.size) // first timer at t=800, not yet 1s
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(HoldDirection.DOWN), events) // first timer fires at t=1000
        gate.onUp()
        runCurrent()
        assertEquals(listOf(HoldDirection.DOWN, HoldDirection.UP), events)
    }

    /** Regression: a ROM that auto-repeats `.down` AFTER activation must not drop the UP
     *  (which would leave the consumer recording until its cap). */
    @Test
    fun `repeat down after activation does not drop the UP`() = runTest {
        val gate = HoldToTalkGate(backgroundScope)
        val events = collectInto(gate)
        gate.onDown()
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(listOf(HoldDirection.DOWN), events) // activated
        gate.onDown() // post-activation auto-repeat — must be ignored, must NOT reset `activated`
        runCurrent()
        assertEquals(listOf(HoldDirection.DOWN), events) // no second DOWN
        gate.onUp()
        runCurrent()
        assertEquals(listOf(HoldDirection.DOWN, HoldDirection.UP), events) // UP still fires
    }
}
