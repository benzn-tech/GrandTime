package com.benzn.grandtime.capture.camera2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkGeometryTest {
    @Test fun `rotation 90 band fraction matches manual playback-space calc`() {
        // bmp 640x81, enc 1440x1080, rot 90 -> playback 1080x1440 -> b = (1080*81/640)/1440 ~= 0.0949
        val b = WatermarkGeometry.bandFraction(bmpW = 640, bmpH = 81, encW = 1440, encH = 1080, rotationDeg = 90)
        assertEquals(0.0949f, b, 1e-3f)
    }

    @Test fun `rotation 0 band fraction uses enc dims directly as playback dims`() {
        // 无播放旋转:playback = enc,band = playW*bmpH/bmpW / playH
        val b = WatermarkGeometry.bandFraction(bmpW = 640, bmpH = 81, encW = 1440, encH = 1080, rotationDeg = 0)
        val expected = (1440f * 81 / 640) / 1080f
        assertEquals(expected, b, 1e-4f)
    }

    @Test fun `degenerate very wide bitmap clamps band fraction to at most 1`() {
        val b = WatermarkGeometry.bandFraction(bmpW = 10, bmpH = 500, encW = 1440, encH = 1080, rotationDeg = 90)
        assertTrue(b <= 1f)
        assertEquals(1f, b, 1e-6f)
    }
}
