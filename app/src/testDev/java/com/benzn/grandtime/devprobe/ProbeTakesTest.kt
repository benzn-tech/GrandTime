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

    @Test fun `the block list is S F N then D E W, in that order`() {
        // The original three answer "which capture setting transcribes best" and are run as a
        // set; D answers a different question (two channels or one copied) and runs its own
        // configurations. D is appended rather than inserted so an existing block directory's
        // name still means what it did when it was recorded.
        assertEquals(
            listOf(ProbeBlock.S, ProbeBlock.F, ProbeBlock.N,
                   ProbeBlock.D, ProbeBlock.E, ProbeBlock.W),
            ProbeBlock.entries.toList())
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

    @Test fun `the ladder is one stereo take, long enough to walk it`() {
        // One take, not five. The previous mono ladder was paused to change
        // rooms and its 2 m -> 4 m step ended up mixing distance with a room
        // change -- the whole run had to be repeated.
        assertEquals(1, LADDER_TAKES.size)
        assertEquals(2, LADDER_TAKES.single().config.channelCount)
        assertEquals(LADDER_TAKES, takesFor(ProbeBlock.E))
        assertTrue("five distances plus walking needs well over a minute",
            ProbeBlock.E.seconds >= 150)
    }

    @Test fun `the ladder records at 16 kHz, not 44 point 1`() {
        // Block D's 44.1 kHz stereo take came back with the channels
        // correlating 0.160 and 14.8 dB apart, against ~0.89 and ~3.8 dB for
        // all three 16 kHz takes. Same shape as this board's NS/AGC at
        // 44.1 kHz: reports healthy, does not do the work.
        assertEquals(16000, LADDER_TAKES.single().config.sampleRate)
    }

    @Test fun `only the short blocks keep the ten-second default`() {
        assertEquals(10, ProbeBlock.S.seconds)
        assertEquals(10, ProbeBlock.D.seconds)
        assertTrue(ProbeBlock.E.seconds > ProbeBlock.D.seconds)
    }

    @Test fun `a stereo take is sized by frames, not by samples`() {
        // Regression: the byte target was sampleRate * 2 * seconds, which is a
        // MONO frame. Every stereo take recorded exactly half its duration, and
        // nothing said so -- the take's JSON echoes the requested seconds, and a
        // half-length wav plays back at the right speed. It was only caught by
        // opening the file and reading getnframes().
        for (t in DUAL_TAKES + LADDER_TAKES) {
            val bytesPerFrame = 2 * t.config.channelCount
            assertEquals(if (t.config.channelCount == 2) 4 else 2, bytesPerFrame)
        }
    }

    @Test fun `the worn-far block is one long stereo take`() {
        // Stereo, not two mono takes: the SNR difference being measured (about
        // 7 dB, seen once, under a placement that is not the worn one) is
        // smaller than the difference between two separate moments, so both
        // microphones have to be captured in the same instant.
        assertEquals(1, WORN_FAR_TAKES.size)
        assertEquals(2, WORN_FAR_TAKES.single().config.channelCount)
        assertEquals(16000, WORN_FAR_TAKES.single().config.sampleRate)
        assertEquals(WORN_FAR_TAKES, takesFor(ProbeBlock.W))
    }

    @Test fun `the worn-far block leaves room for taps, friction and three distances`() {
        // tap ID + friction + 1 m + 3 m + 6 m, each with 4 s of silence around
        // it. Under about three minutes the operator has to rush, and rushing
        // is what merged the distances in the first ladder attempt.
        assertTrue(ProbeBlock.W.seconds >= 180)
    }
}
