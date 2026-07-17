package com.benzn.grandtime.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure helpers backing rolling-audio-segment sizing and the overlap
 *  ring buffer used to seed each new segment with the tail of the previous one. */
class AudioSegmentationTest {

    @Test
    fun `segmentBytesFor 3 minutes at 16kHz mono 16-bit`() {
        assertEquals(5_760_000L, segmentBytesFor(3))
    }

    @Test
    fun `segmentBytesFor 1 minute at 16kHz mono 16-bit`() {
        assertEquals(1_920_000L, segmentBytesFor(1))
    }

    @Test
    fun `overlapBytesFor 2 seconds at 16kHz mono 16-bit`() {
        assertEquals(64_000L, overlapBytesFor(2))
    }

    @Test
    fun `overlapBytesFor caps at a sane maximum`() {
        // 100s would be 3_200_000 bytes uncapped; must not exceed the cap.
        val uncapped = 100L * 16000 * 2
        assertEquals(true, overlapBytesFor(100) < uncapped)
    }

    @Test
    fun `PcmRingBuffer empty snapshot is empty`() {
        val ring = PcmRingBuffer(10)

        assertArrayEquals(ByteArray(0), ring.snapshot())
    }

    @Test
    fun `PcmRingBuffer under capacity returns everything appended in order`() {
        val ring = PcmRingBuffer(10)

        ring.append(byteArrayOf(1, 2, 3), 3)
        ring.append(byteArrayOf(4, 5), 2)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), ring.snapshot())
    }

    @Test
    fun `PcmRingBuffer over capacity in one append returns exactly the last capacity bytes in order`() {
        val ring = PcmRingBuffer(10)
        val bytes = ByteArray(100) { it.toByte() } // 0..99

        ring.append(bytes, bytes.size)

        val expected = ByteArray(10) { (90 + it).toByte() } // 90..99
        assertArrayEquals(expected, ring.snapshot())
    }

    @Test
    fun `PcmRingBuffer over capacity across multiple appends wraps and returns the last capacity bytes in order`() {
        val ring = PcmRingBuffer(5)

        ring.append(byteArrayOf(1, 2, 3), 3)
        ring.append(byteArrayOf(4, 5, 6, 7), 4)

        // Conceptual stream: 1,2,3,4,5,6,7 -> last 5 = 3,4,5,6,7
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), ring.snapshot())
    }

    @Test
    fun `PcmRingBuffer zero capacity always snapshots empty`() {
        val ring = PcmRingBuffer(0)

        ring.append(byteArrayOf(1, 2, 3), 3)

        assertArrayEquals(ByteArray(0), ring.snapshot())
    }
}
