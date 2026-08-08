package com.benzn.grandtime.devprobe

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmStatsTest {

    /** Little-endian PCM16 from signed sample values. */
    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            b[i * 2] = (s and 0xFF).toByte()
            b[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test fun `full-scale square wave is 0 dBFS peak and 0 dBFS rms`() {
        val s = pcmStats(pcm(32767, -32767, 32767, -32767), 8)
        assertEquals(0.0, s.peakDbfs, 0.01)
        assertEquals(0.0, s.rmsDbfs, 0.01)
    }

    @Test fun `half-scale square wave is about -6 dBFS`() {
        val s = pcmStats(pcm(16384, -16384), 4)
        assertEquals(-6.0, s.rmsDbfs, 0.1)
    }

    @Test fun `digital silence reports a floor instead of negative infinity`() {
        val s = pcmStats(pcm(0, 0, 0, 0), 8)
        assertEquals(-120.0, s.rmsDbfs, 0.01)
        assertEquals(-120.0, s.peakDbfs, 0.01)
    }

    @Test fun `clipping fraction counts samples at or above 98 percent of full scale`() {
        // 2 of 4 samples are clipped (32767 and -32700 both exceed 0.98 * 32768 = 32112).
        val s = pcmStats(pcm(32767, 100, -32700, 200), 8)
        assertEquals(50, (s.clippedFraction * 100).roundToInt())
    }

    @Test fun `only the first len bytes are read`() {
        val buf = pcm(16384, -16384, 32767, -32767)
        val s = pcmStats(buf, 4) // first two samples only
        assertEquals(-6.0, s.rmsDbfs, 0.1)
    }

    @Test fun `an odd len ignores the trailing half sample`() {
        val s = pcmStats(pcm(16384, -16384), 3)
        assertEquals(-6.0, s.rmsDbfs, 0.1)
    }
}
