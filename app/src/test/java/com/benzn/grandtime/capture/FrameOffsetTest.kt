package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/** 抓帧偏移纯函数(spec §2.5):(按键时刻 − 段起始) 钳制非负,换算微秒。 */
class FrameOffsetTest {

    @Test
    fun `offset is press time minus segment start, in microseconds`() {
        assertEquals(5_500_000L, frameOffsetMicros(10_500L, 5_000L))
    }

    @Test
    fun `offset clamps negative to zero`() {
        assertEquals(0L, frameOffsetMicros(4_000L, 5_000L))
    }

    @Test
    fun `offset zero when pressed exactly at segment start`() {
        assertEquals(0L, frameOffsetMicros(5_000L, 5_000L))
    }
}
