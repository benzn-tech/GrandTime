package com.benzn.grandtime.hardware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsPatternTest {

    // These two must stay byte-identical to what CaptureManager has always emitted:
    // an operator already reads one buzz as "accepted" and two as "refused", and a
    // refusal that feels different from every other refusal teaches nothing.
    @Test
    fun `short is the existing single-buzz waveform`() {
        assertArrayEquals(longArrayOf(0, 80), waveformFor(VibePattern.SHORT))
    }

    @Test
    fun `double short is the existing refusal waveform`() {
        assertArrayEquals(longArrayOf(0, 60, 80, 60), waveformFor(VibePattern.DOUBLE_SHORT))
    }

    @Test
    fun `long is one continuous buzz, not a count`() {
        val w = waveformFor(VibePattern.LONG)
        assertArrayEquals(longArrayOf(0, 350), w)
    }

    // Countable patterns are unreliable through a closed pocket, so LONG must be
    // told apart by duration rather than by counting pulses.
    @Test
    fun `long is at least three times the short buzz`() {
        val short = waveformFor(VibePattern.SHORT).last()
        val long = waveformFor(VibePattern.LONG).last()
        assertTrue("long=$long short=$short", long >= short * 3)
    }
}
