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
    fun `single volume used when no sd card`() {
        val internal = tmp.newFolder("internal")
        val storage = MediaStorage({ listOf(internal) })
        assertEquals(internal, storage.root())
    }

    @Test
    fun `second volume preferred when sd present, nulls filtered`() {
        val internal = tmp.newFolder("internal")
        val sd = tmp.newFolder("sd")
        val storage = MediaStorage({ listOf(internal, null, sd) })
        assertEquals(sd, storage.root())
    }

    @Test
    fun `newFile names by kind prefix and timestamp`() {
        val internal = tmp.newFolder("i2")
        val storage = MediaStorage({ listOf(internal) })
        val f = storage.newFile(MediaStorage.Kind.VIDEO, millis(2026, 7, 11, 16, 2, 46))
        assertEquals("VID_20260711_160246.mp4", f.name)
        assertEquals(File(File(internal, "media"), "video"), f.parentFile)
    }

    @Test
    fun `same-second collision appends suffix`() {
        val internal = tmp.newFolder("i3")
        val storage = MediaStorage({ listOf(internal) })
        val t = millis(2026, 7, 11, 8, 0, 0)
        val first = storage.newFile(MediaStorage.Kind.PHOTO, t)
        first.parentFile!!.mkdirs(); first.createNewFile()
        val second = storage.newFile(MediaStorage.Kind.PHOTO, t)
        assertEquals("IMG_20260711_080000_1.jpg", second.name)
    }

    @Test
    fun `hasFreeSpace true for tiny threshold, false for absurd`() {
        val internal = tmp.newFolder("i4")
        val storage = MediaStorage({ listOf(internal) })
        assertTrue(storage.hasFreeSpace(minBytes = 1L))
        assertTrue(!storage.hasFreeSpace(minBytes = Long.MAX_VALUE))
    }
}
