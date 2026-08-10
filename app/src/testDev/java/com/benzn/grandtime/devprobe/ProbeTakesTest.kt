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

    @Test fun `block D runs the dual-mic list and every other block keeps the original ten`() {
        assertEquals(DUAL_TAKES, takesFor(ProbeBlock.D))
        for (b in listOf(ProbeBlock.S, ProbeBlock.F, ProbeBlock.N)) {
            assertEquals(PROBE_TAKES, takesFor(b))
        }
    }

    @Test fun `block D pairs every stereo take with a mono control at the same settings`() {
        // Without a mono take recorded in the same block, a channel comparison has nothing to
        // be compared against except recordings made under different conditions.
        val mono = DUAL_TAKES.filter { it.config.channelCount == 1 }
        val stereo = DUAL_TAKES.filter { it.config.channelCount == 2 }
        assertTrue("needs a mono control", mono.size >= 2)
        assertTrue("needs stereo takes", stereo.size >= 2)
        assertEquals(
            "the first take must be the mono control so drift is bounded on both sides",
            1, DUAL_TAKES.first().config.channelCount)
        assertEquals(1, DUAL_TAKES.last().config.channelCount)
    }

    @Test fun `block D take names are unique and say their channel count`() {
        assertEquals(DUAL_TAKES.size, DUAL_TAKES.map { it.name }.toSet().size)
        for (t in DUAL_TAKES) {
            val expected = if (t.config.channelCount == 2) "stereo" else "mono"
            assertTrue("${t.name} should start with $expected", t.name.startsWith(expected))
        }
    }
}
