package com.benzn.grandtime.devprobe

import com.benzn.grandtime.capture.MicChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTakesTest {

    @Test fun `there are ten takes, indexed one to ten in order`() {
        assertEquals(10, PROBE_TAKES.size)
        assertEquals((1..10).toList(), PROBE_TAKES.map { it.index })
    }

    @Test fun `names are unique and filename-safe`() {
        assertEquals(PROBE_TAKES.size, PROBE_TAKES.map { it.name }.toSet().size)
        assertTrue(PROBE_TAKES.all { it.name.matches(Regex("[a-z0-9_]+")) })
    }

    @Test fun `take 1 is today's standalone baseline and take 10 repeats it exactly`() {
        assertEquals(com.benzn.grandtime.capture.AudioCaptureConfig.DEFAULT_STANDALONE, PROBE_TAKES[0].config)
        assertEquals(PROBE_TAKES[0].config, PROBE_TAKES[9].config)
    }

    @Test fun `take 7 is today's video baseline`() {
        assertEquals(com.benzn.grandtime.capture.AudioCaptureConfig.DEFAULT_VIDEO, PROBE_TAKES[6].config)
    }

    @Test fun `the two mic-selection takes ask for different physical mics`() {
        assertEquals(MicChoice.FRONT, PROBE_TAKES[2].config.preferredMic)
        assertEquals(MicChoice.BACK, PROBE_TAKES[3].config.preferredMic)
    }

    @Test fun `the voice_communication takes cover both sample rates`() {
        val vc = PROBE_TAKES.filter { it.config.source == 7 } // AudioSource.VOICE_COMMUNICATION
        assertEquals(listOf(16000, 44100), vc.map { it.config.sampleRate })
    }

    @Test fun `the app-effect takes request both NS and AGC and cover both sample rates`() {
        val fx = PROBE_TAKES.filter { it.config.enableNs || it.config.enableAgc }
        assertTrue(fx.all { it.config.enableNs && it.config.enableAgc })
        assertEquals(listOf(16000, 44100), fx.map { it.config.sampleRate })
    }

    @Test fun `the camcorder model check is present at the standalone rate`() {
        val cam = PROBE_TAKES.single { it.config.source == 5 } // AudioSource.CAMCORDER
        assertEquals(16000, cam.config.sampleRate)
    }

    @Test fun `every block is recorded for every take`() {
        assertEquals(listOf(ProbeBlock.S, ProbeBlock.F, ProbeBlock.N), ProbeBlock.entries.toList())
    }
}
