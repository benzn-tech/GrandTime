package com.benzn.grandtime.hardware

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PressTypeDetectorTest {

    private fun kotlinx.coroutines.test.TestScope.collectInto(
        detector: PressTypeDetector,
    ): MutableList<KeyPress> {
        val events = mutableListOf<KeyPress>()
        backgroundScope.launch { detector.keyPresses.collect { events.add(it) } }
        runCurrent()
        return events
    }

    @Test
    fun `up within 1s emits SHORT`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.VIDEO, RawDirection.DOWN)
        advanceTimeBy(900)
        detector.onRawEvent(HardKey.VIDEO, RawDirection.UP)
        runCurrent()
        assertEquals(listOf(KeyPress(HardKey.VIDEO, PressType.SHORT)), events)
    }

    @Test
    fun `timer firing at 1s emits LONG immediately, later up ignored`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.SOS, RawDirection.DOWN)
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(listOf(KeyPress(HardKey.SOS, PressType.LONG)), events)
        detector.onRawEvent(HardKey.SOS, RawDirection.UP)
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test
    fun `up without down emits nothing`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.AUDIO, RawDirection.UP)
        runCurrent()
        assertEquals(0, events.size)
    }

    @Test
    fun `repeated down resets the timer`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.PHOTO, RawDirection.DOWN)
        advanceTimeBy(800)
        detector.onRawEvent(HardKey.PHOTO, RawDirection.DOWN) // 重复 down,重置
        advanceTimeBy(800)
        runCurrent()
        assertEquals(0, events.size) // 两段都没到 1s,无 LONG
        detector.onRawEvent(HardKey.PHOTO, RawDirection.UP)
        runCurrent()
        assertEquals(listOf(KeyPress(HardKey.PHOTO, PressType.SHORT)), events)
    }

    @Test
    fun `two keys interleaved are independent`() = runTest {
        val detector = PressTypeDetector(backgroundScope)
        val events = collectInto(detector)
        detector.onRawEvent(HardKey.VIDEO, RawDirection.DOWN)
        advanceTimeBy(500)
        detector.onRawEvent(HardKey.SOS, RawDirection.DOWN)
        advanceTimeBy(600) // VIDEO 到 1.1s → LONG;SOS 才 0.6s
        runCurrent()
        detector.onRawEvent(HardKey.SOS, RawDirection.UP) // SOS SHORT
        detector.onRawEvent(HardKey.VIDEO, RawDirection.UP) // 已 LONG,忽略
        runCurrent()
        assertEquals(
            listOf(
                KeyPress(HardKey.VIDEO, PressType.LONG),
                KeyPress(HardKey.SOS, PressType.SHORT),
            ),
            events,
        )
    }
}
