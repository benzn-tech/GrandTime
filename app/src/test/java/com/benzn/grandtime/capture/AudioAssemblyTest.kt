package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [AudioAssembly.finish], the pure decision AudioRecorder.stop() delegates
 *  to: given the temp PCM and whether capture failed, either build the WAV or fail closed. */
class AudioAssemblyTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `captureFailed true returns false, deletes temp, leaves target untouched`() {
        val tmp = tmpFolder.newFile("clip.pcm")
        tmp.writeBytes(ByteArray(32000) { 1 }) // partial PCM from a killed worker
        val target = tmpFolder.newFile("clip.wav")
        target.delete() // start from "not created yet", like the real caller

        val ok = AudioAssembly.finish(tmp, target, captureFailed = true)

        assertFalse(ok)
        assertFalse("temp PCM must be deleted on failure", tmp.exists())
        assertFalse("target WAV must not be produced on failure", target.exists())
    }

    @Test
    fun `captureFailed false with data builds a valid WAV and deletes temp`() {
        val pcmLen = 32000
        val tmp = tmpFolder.newFile("clip2.pcm")
        tmp.writeBytes(ByteArray(pcmLen) { 7 })
        val target = tmpFolder.newFile("clip2.wav")

        val ok = AudioAssembly.finish(tmp, target, captureFailed = false)

        assertTrue(ok)
        assertFalse("temp PCM must be deleted after assembly", tmp.exists())
        assertEquals(44L + pcmLen, target.length())
        assertEquals("RIFF", String(target.readBytes(), 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `missing temp returns false without throwing`() {
        val tmp = tmpFolder.newFile("clip3.pcm")
        tmp.delete() // never created / already gone
        val target = tmpFolder.newFile("clip3.wav")

        val ok = AudioAssembly.finish(tmp, target, captureFailed = false)

        assertFalse(ok)
    }
}
