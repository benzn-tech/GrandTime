package com.benzn.grandtime.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {
    @Test fun `44-byte RIFF WAVE header for 16k mono 16-bit`() {
        val pcmLen = 32000 // 1s of 16k mono 16-bit
        val h = WavHeader.riffWav(pcmLen, sampleRate = 16000, channels = 1, bits = 16)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(h, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(h, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(h, 36, 4, Charsets.US_ASCII))
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcmLen, bb.getInt(4))       // RIFF chunk size
        assertEquals(16, bb.getInt(16))               // fmt subchunk size
        assertEquals(1.toShort(), bb.getShort(20))    // PCM
        assertEquals(1.toShort(), bb.getShort(22))    // mono
        assertEquals(16000, bb.getInt(24))            // sample rate
        assertEquals(16000 * 1 * 16 / 8, bb.getInt(28)) // byte rate
        assertEquals((1 * 16 / 8).toShort(), bb.getShort(32)) // block align
        assertEquals(16.toShort(), bb.getShort(34))   // bits
        assertEquals(pcmLen, bb.getInt(40))           // data size
    }
}
