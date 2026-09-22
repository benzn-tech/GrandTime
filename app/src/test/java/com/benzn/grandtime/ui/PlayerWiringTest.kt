package com.benzn.grandtime.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins how the Files screen reaches the player. Source-level, because ExoPlayer and Compose UI do
 * not run on the JVM; the timeline rules themselves are driven in PlaybackTimelineTest.
 *
 * What is pinned is the point of the change: a tap plays the WHOLE recording in the app, rather than
 * handing one file to another app or asking which segment to play.
 */
class PlayerWiringTest {

    private val files = File("src/main/java/com/benzn/grandtime/ui/FilesScreen.kt").readText()
    private val player = File("src/main/java/com/benzn/grandtime/ui/RecordingPlayerSheet.kt").readText()

    @Test
    fun `tapping a recording plays it in the app, and only photos are handed to another app`() {
        val cell = files.indexOf("MediaCell(")
        val onClick = files.substring(cell, files.indexOf("onLongClick = {", cell))
        assertTrue("a tap must open the in-app player", onClick.contains("PlaybackRequest(unit)"))
        assertTrue("only a photo leaves the app", Regex("""openFile\(context,\s*unit\.representative\)""").containsMatchIn(onClick))
        assertTrue("the photo case must be the guarded one", onClick.contains("""kind == "photo""""))
        assertFalse("video must not be handed to an external app any more", onClick.contains("""kind == "video""""))
    }

    @Test
    fun `the player is given every segment, in order, as one playlist`() {
        assertTrue(player.contains("unit.segments.map { MediaItem.fromUri("))
        assertTrue("clamped to a real segment", player.contains("startIndex.coerceIn(0, unit.segments.lastIndex)"))
        assertTrue("and starts there", player.contains("player.seekTo(firstIndex, 0L)"))
    }

    @Test
    fun `picking a segment continues into the ones after it`() {
        // "Segment 3" means "from segment 3", not "segment 3 alone" -- the whole point of the change.
        val detail = files.substring(files.indexOf("onPlaySegment = {"), files.indexOf("onDismiss = { detailUnit = null }"))
        assertTrue(detail.contains("PlaybackRequest(unit, index)"))
    }

    @Test
    fun `a recording whose rows carry no length still gets a working bar`() {
        // Older recordings exist whose rows have no duration (reconciled from disk, or finalized
        // after a crash with 0). Without this the bar is dead for the whole recording and the total
        // is short by those segments; the player must not wait for ExoPlayer, which reports a
        // segment only once it opens it.
        val fill = player.indexOf("readDurationMillis(it.filePath)")
        assertTrue("the player must read missing lengths from the files", fill >= 0)
        assertTrue("off the main thread", player.substring(0, fill).contains("withContext(Dispatchers.IO)"))
        assertTrue("only when something is missing", player.contains("durations.any { it <= 0L }"))
        assertTrue("a length the row already has is kept", player.contains("if (known > 0L) known else"))
    }

    @Test
    fun `an unreadable segment is skipped instead of ending the recording`() {
        val onError = player.substring(player.indexOf("override fun onPlayerError("))
        val skip = onError.indexOf("player.seekToNextMediaItem()")
        val giveUp = onError.indexOf("errorMessage =")
        assertTrue("a broken segment must be skipped", skip in 0 until giveUp)
        assertTrue("only with nothing left to play does it give up", onError.contains("player.hasNextMediaItem()"))
    }

    @Test
    fun `the old single-file audio player is gone, so there is one player`() {
        assertFalse(File("src/main/java/com/benzn/grandtime/ui/AudioPlayerSheet.kt").exists())
        assertFalse(files.contains("AudioPlayerSheet"))
    }
}
