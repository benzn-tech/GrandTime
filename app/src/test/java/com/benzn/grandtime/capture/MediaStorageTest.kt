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
        assertEquals("VID_20260711_160246.mp4", f.name)
        assertEquals(File(File(File(root, "FieldSight"), "device"), "video"), f.parentFile)
    }

    @Test
    fun `same-second collision appends suffix`() {
        val root = tmp.newFolder("root2")
        val storage = MediaStorage({ root })
        val t = millis(2026, 7, 11, 8, 0, 0)
        val first = storage.newFile(MediaStorage.Kind.PHOTO, t)
        first.parentFile!!.mkdirs(); first.createNewFile()
        assertEquals("IMG_20260711_080000_1.jpg", storage.newFile(MediaStorage.Kind.PHOTO, t).name)
    }

    @Test
    fun `hasFreeSpace true for tiny threshold`() {
        val root = tmp.newFolder("root3")
        assertTrue(MediaStorage({ root }).hasFreeSpace(minBytes = 1L))
    }
}
