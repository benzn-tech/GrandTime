package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** These assertions are the regression guard for "prod behaviour is unchanged": they pin the
 *  defaults to the literal values the two capture sites computed before they were routed through
 *  a shared opener. Literals rather than framework constants on purpose — the test must fail if
 *  the value changes, not silently follow it. */
class AudioCaptureConfigTest {

    @Test fun `standalone default reproduces AudioRecorder's previous hardcoded values`() {
        val c = AudioCaptureConfig.DEFAULT_STANDALONE
        assertEquals(1, c.source)              // MediaRecorder.AudioSource.MIC
        assertEquals(16000, c.sampleRate)
        assertEquals(32000, c.bufferFloorBytes) // was max(minBuf, sampleRate * 2)
        assertNull(c.preferredMic)
        assertFalse(c.enableNs)
        assertFalse(c.enableAgc)
    }

    @Test fun `video default reproduces SegmentRecorder's previous hardcoded values`() {
        val c = AudioCaptureConfig.DEFAULT_VIDEO
        assertEquals(1, c.source)              // MediaRecorder.AudioSource.MIC
        assertEquals(44100, c.sampleRate)
        assertEquals(8192, c.bufferFloorBytes)  // was max(minBuf, 4096 * 2)
        assertNull(c.preferredMic)
        assertFalse(c.enableNs)
        assertFalse(c.enableAgc)
    }

    @Test fun `mic choices carry the addresses this board reports`() {
        assertEquals("bottom", MicChoice.FRONT.address)
        assertEquals("back", MicChoice.BACK.address)
    }
}
