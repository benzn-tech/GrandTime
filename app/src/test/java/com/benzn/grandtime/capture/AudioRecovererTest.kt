package com.benzn.grandtime.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Unit tests for [classifyOrphanPcm], the pure decision [AudioRecoverer.recover] uses to
 *  triage an orphan .pcm temp left behind by a killed AudioRecorder: recover it into a WAV
 *  when it holds data and no WAV was ever written, otherwise discard it. */
class AudioRecovererTest {

    @Test
    fun `no sibling wav and pcm has data recovers`() {
        assertEquals(PcmAction.RECOVER, classifyOrphanPcm(siblingWavExists = false, pcmLength = 32000L))
    }

    @Test
    fun `no sibling wav but pcm is empty discards`() {
        assertEquals(PcmAction.DISCARD, classifyOrphanPcm(siblingWavExists = false, pcmLength = 0L))
    }

    @Test
    fun `sibling wav already exists and pcm has data discards`() {
        assertEquals(PcmAction.DISCARD, classifyOrphanPcm(siblingWavExists = true, pcmLength = 32000L))
    }

    @Test
    fun `sibling wav already exists and pcm is empty discards`() {
        assertEquals(PcmAction.DISCARD, classifyOrphanPcm(siblingWavExists = true, pcmLength = 0L))
    }
}

/** Unit tests for [AudioRecoverer.recover], the filesystem sweep that walks a root dir for
 *  orphan .pcm temps and assembles the recoverable ones into their sibling .wav. */
class AudioRecovererRecoverTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `recovers an orphan pcm with no sibling wav into a valid wav and deletes the temp`() {
        val root = tmpFolder.newFolder("root")
        val audioDir = File(root, "user1/audio").apply { mkdirs() }
        val pcm = File(audioDir, "AUD_2026-07-17_10-00-00.pcm")
        val pcmLen = 32000
        pcm.writeBytes(ByteArray(pcmLen) { 3 })
        val wav = File(audioDir, "AUD_2026-07-17_10-00-00.wav")

        val recovered = AudioRecoverer.recover(root)

        assertEquals(1, recovered)
        assertFalse("temp PCM must be deleted after recovery", pcm.exists())
        assertTrue("recovered WAV must exist", wav.exists())
        assertEquals(44L + pcmLen, wav.length())
    }

    @Test
    fun `discards an empty orphan pcm without producing a wav`() {
        val root = tmpFolder.newFolder("root2")
        val audioDir = File(root, "user1/audio").apply { mkdirs() }
        val pcm = File(audioDir, "AUD_empty.pcm")
        pcm.writeBytes(ByteArray(0))
        val wav = File(audioDir, "AUD_empty.wav")

        val recovered = AudioRecoverer.recover(root)

        assertEquals(0, recovered)
        assertFalse("empty temp must be deleted, not assembled", pcm.exists())
        assertFalse(wav.exists())
    }

    @Test
    fun `discards a leftover pcm whose wav already exists, leaving the wav untouched`() {
        val root = tmpFolder.newFolder("root3")
        val audioDir = File(root, "user1/audio").apply { mkdirs() }
        val pcm = File(audioDir, "AUD_done.pcm")
        pcm.writeBytes(ByteArray(1000) { 5 })
        val wav = File(audioDir, "AUD_done.wav")
        val wavBytes = ByteArray(500) { 9 }
        wav.writeBytes(wavBytes)

        val recovered = AudioRecoverer.recover(root)

        assertEquals(0, recovered)
        assertFalse("leftover temp must be deleted", pcm.exists())
        assertTrue(wav.exists())
        assertArrayEquals("existing wav must not be touched", wavBytes, wav.readBytes())
    }

    @Test
    fun `recovers multiple orphans across nested directories and skips non-pcm files`() {
        val root = tmpFolder.newFolder("root4")
        val dir1 = File(root, "user1/audio").apply { mkdirs() }
        val dir2 = File(root, "user2/audio").apply { mkdirs() }
        File(dir1, "a.pcm").writeBytes(ByteArray(100) { 1 })
        File(dir2, "b.pcm").writeBytes(ByteArray(200) { 2 })
        File(dir1, "unrelated.txt").writeBytes(ByteArray(10))

        val recovered = AudioRecoverer.recover(root)

        assertEquals(2, recovered)
        assertTrue(File(dir1, "a.wav").exists())
        assertTrue(File(dir2, "b.wav").exists())
    }

    @Test
    fun `no orphan pcm files present recovers nothing`() {
        val root = tmpFolder.newFolder("root5")
        File(root, "user1/audio").mkdirs()

        val recovered = AudioRecoverer.recover(root)

        assertEquals(0, recovered)
    }
}
