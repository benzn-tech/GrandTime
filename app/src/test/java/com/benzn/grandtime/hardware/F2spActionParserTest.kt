package com.benzn.grandtime.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class F2spActionParserTest {

    @Test
    fun `known key actions parse to key and direction`() {
        assertEquals(HardKey.VIDEO to RawDirection.DOWN, F2spKeyEventSource.parse("lolaage.video1.down"))
        assertEquals(HardKey.VIDEO to RawDirection.UP, F2spKeyEventSource.parse("lolaage.video1.up"))
        assertEquals(HardKey.PHOTO to RawDirection.DOWN, F2spKeyEventSource.parse("lolaage.take.picture.down"))
        assertEquals(HardKey.AUDIO to RawDirection.UP, F2spKeyEventSource.parse("lolaage.audio.up"))
    }

    @Test
    fun `ptt and probe-only and unknown actions parse to null`() {
        assertNull(F2spKeyEventSource.parse("lolaage.ptt.down"))
        assertNull(F2spKeyEventSource.parse("lolaage.light"))
        assertNull(F2spKeyEventSource.parse("whatever.else"))
        assertNull(F2spKeyEventSource.parse("lolaage.sos.down"))
    }
}
