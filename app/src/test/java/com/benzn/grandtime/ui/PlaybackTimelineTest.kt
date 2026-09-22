package com.benzn.grandtime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar that runs across a whole recording while the segments stay separate files on disk.
 *
 * Measured shapes these come from: video segments are ~30 s each and a session can hold twenty of
 * them; a row reconciled from disk carries no duration at all.
 */
class PlaybackTimelineTest {

    private val thirtySeconds = listOf(30_000L, 30_000L, 30_000L)

    @Test
    fun `the whole recording is as long as its segments together`() {
        assertEquals(90_000L, PlaybackTimeline.total(thirtySeconds))
        assertEquals(0L, PlaybackTimeline.total(emptyList()))
    }

    @Test
    fun `a position inside a later segment counts the segments before it`() {
        assertEquals(65_000L, PlaybackTimeline.globalPosition(2, 5_000L, thirtySeconds))
        assertEquals(5_000L, PlaybackTimeline.globalPosition(0, 5_000L, thirtySeconds))
    }

    @Test
    fun `dragging lands in whichever segment holds that moment`() {
        assertEquals(PlaybackTimeline.Seek(0, 5_000L), PlaybackTimeline.locate(5_000L, thirtySeconds))
        assertEquals(PlaybackTimeline.Seek(1, 0L), PlaybackTimeline.locate(30_000L, thirtySeconds))
        assertEquals(PlaybackTimeline.Seek(2, 5_000L), PlaybackTimeline.locate(65_000L, thirtySeconds))
    }

    @Test
    fun `a drag past either end stays inside the recording`() {
        assertEquals(PlaybackTimeline.Seek(0, 0L), PlaybackTimeline.locate(-1_000L, thirtySeconds))
        assertEquals(PlaybackTimeline.Seek(2, 30_000L), PlaybackTimeline.locate(999_999L, thirtySeconds))
    }

    @Test
    fun `position and drag are inverses of each other`() {
        for (globalMs in listOf(0L, 1L, 29_999L, 30_000L, 45_000L, 89_999L)) {
            val at = PlaybackTimeline.locate(globalMs, thirtySeconds)
            assertEquals(globalMs, PlaybackTimeline.globalPosition(at.segmentIndex, at.positionMs, thirtySeconds))
        }
    }

    @Test
    fun `a recording whose lengths are not all known yet cannot be dragged`() {
        // A row reconciled from disk has no duration, and a segment the player has not opened yet
        // reports none. Offering a drag over a bar that does not span the recording would move
        // playback somewhere the person did not point at.
        assertFalse(PlaybackTimeline.seekable(listOf(30_000L, 0L, 30_000L)))
        assertFalse(PlaybackTimeline.seekable(emptyList()))
        assertTrue(PlaybackTimeline.seekable(thirtySeconds))
    }

    @Test
    fun `an unknown length is skipped over rather than swallowing the drag`() {
        val durations = listOf(30_000L, 0L, 30_000L)
        assertEquals(PlaybackTimeline.Seek(2, 5_000L), PlaybackTimeline.locate(35_000L, durations))
        assertEquals(35_000L, PlaybackTimeline.globalPosition(2, 5_000L, durations))
    }

    @Test
    fun `a single-segment recording behaves like a plain file`() {
        val one = listOf(42_000L)
        assertEquals(42_000L, PlaybackTimeline.total(one))
        assertEquals(PlaybackTimeline.Seek(0, 1_000L), PlaybackTimeline.locate(1_000L, one))
        assertEquals(1_000L, PlaybackTimeline.globalPosition(0, 1_000L, one))
    }

    @Test
    fun `a segment index or position out of range is clamped, never negative`() {
        assertEquals(60_000L, PlaybackTimeline.globalPosition(9, 0L, thirtySeconds))
        assertEquals(60_000L, PlaybackTimeline.globalPosition(2, -5_000L, thirtySeconds))
        assertEquals(90_000L, PlaybackTimeline.globalPosition(2, 999_999L, thirtySeconds))
        assertEquals(0L, PlaybackTimeline.globalPosition(0, 5_000L, emptyList()))
    }
}
