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
    fun `re-press without an up does not double-fire`() = runTest {
        val gate = HoldToTalkGate(backgroundScope)
        val events = collectInto(gate)
        gate.onDown()
        advanceTimeBy(400)
        gate.onDown() // re-press before threshold — resets the timer
        advanceTimeBy(400)
        runCurrent()
        assertEquals(0, events.size) // neither segment reached 1s yet
        advanceTimeBy(700)
        runCurrent()
        assertEquals(listOf(HoldDirection.DOWN), events)
        gate.onUp()
        runCurrent()
        assertEquals(listOf(HoldDirection.DOWN, HoldDirection.UP), events)
    }
}
