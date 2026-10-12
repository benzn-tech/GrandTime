package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class MediaStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance(TimeZone.getDefault()).apply {
            set(y, mo - 1, d, h, mi, s); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `newFile names by kind prefix and timestamp under FieldSight device`() {
        val root = tmp.newFolder("root")
        val storage = MediaStorage({ root })
        val f = storage.newFile(MediaStorage.Kind.VIDEO, millis(2026, 7, 11, 16, 2, 46))
        assertEquals("VID_2026-07-11_16-02-46.mp4", f.name)
        assertEquals(File(File(File(root, "FieldSight"), "device"), "video"), f.parentFile)
    }

    @Test
    fun `same-second collision appends suffix`() {
        val root = tmp.newFolder("root2")
        val storage = MediaStorage({ root })
        val t = millis(2026, 7, 11, 8, 0, 0)
        val first = storage.newFile(MediaStorage.Kind.PHOTO, t)
        first.parentFile!!.mkdirs(); first.createNewFile()
        assertEquals("IMG_2026-07-11_08-00-00_1.jpg", storage.newFile(MediaStorage.Kind.PHOTO, t).name)
    }

    @Test
    fun `newFile name matches the pipeline-parseable dashed timestamp format`() {
        val root = tmp.newFolder("root4")
        val storage = MediaStorage({ root })
        val f = storage.newFile(MediaStorage.Kind.VIDEO, millis(2026, 7, 11, 16, 2, 46))
        assertTrue(f.name, Regex("""^.+_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.\w+$""").matches(f.name))
    }

    @Test
    fun `hasFreeSpace true for tiny threshold`() {
        val root = tmp.newFolder("root3")
        assertTrue(MediaStorage({ root }).hasFreeSpace(minBytes = 1L))
    }

    // The F2SP's SD slot sits on an SPI bus that cannot keep up with 1080p video; recording to it
    // stalled the encoder drain until the low-memory killer took the app (see publicRoot).
    @Test
    fun `a mounted SD card is not chosen for new recordings`() {
        val internal = File("/storage/emulated/0")
        val card = File("/storage/2D81-13F1")
        assertEquals(internal, MediaStorage.pickRecordingRoot(internal, listOf(card)))
        assertEquals(internal, MediaStorage.pickRecordingRoot(internal, emptyList()))
    }

    @Test
    fun `a mounted SD card is still scanned, so recordings already on it are not marked missing`() {
        val internal = File("/storage/emulated/0")
        val card = File("/storage/2D81-13F1")
        assertEquals(listOf(internal, card), MediaStorage.scanRoots(internal, listOf(card)))
        assertEquals("no card, one root", listOf(internal), MediaStorage.scanRoots(internal, emptyList()))
        assertEquals("never the same root twice", listOf(internal), MediaStorage.scanRoots(internal, listOf(internal)))
    }

    // Source-level: publicRoot/scanDisk need an Android Context. Pins that the decision tested
    // above is the one the app actually uses.
    @Test
    fun `the app routes recording and scanning through the tested decisions`() {
        val storage = File("src/main/java/com/benzn/grandtime/capture/MediaStorage.kt").readText()
        val publicRoot = storage.substring(storage.indexOf("fun publicRoot("), storage.indexOf("fun pickRecordingRoot("))
        assertTrue(publicRoot.contains("pickRecordingRoot("))
        val files = File("src/main/java/com/benzn/grandtime/ui/FilesScreen.kt").readText()
        val scanDisk = files.substring(files.indexOf("internal fun scanDisk("), files.indexOf("internal fun readDurationMillis("))
        assertTrue("the reconcile must scan the card too", scanDisk.contains("MediaStorage.scanRoots(context)"))
    }
}
