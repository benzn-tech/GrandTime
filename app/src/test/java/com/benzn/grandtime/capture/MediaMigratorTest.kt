package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaMigratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun seed(oldRoot: File, kind: String, name: String) {
        val d = File(File(oldRoot, "media"), kind); d.mkdirs()
        File(d, name).writeText("x")
    }

    @Test
    fun `moves old private files to public and reports each`() {
        val oldRoot = tmp.newFolder("old"); val newRoot = tmp.newFolder("new")
        seed(oldRoot, "video", "VID_1.mp4"); seed(oldRoot, "photo", "IMG_1.jpg")
        val moved = mutableListOf<Pair<String, String>>()
        val n = MediaMigrator(oldRoot, newRoot) { old, nf -> moved.add(old to nf.name) }.migrate()
        assertEquals(2, n)
        assertTrue(File(File(File(File(newRoot, "FieldSight"), "device"), "video"), "VID_1.mp4").exists())
        assertFalse(File(File(File(oldRoot, "media"), "video"), "VID_1.mp4").exists())
        assertEquals(2, moved.size)
    }

    @Test
    fun `idempotent - second run moves nothing and skips existing`() {
        val oldRoot = tmp.newFolder("old2"); val newRoot = tmp.newFolder("new2")
        seed(oldRoot, "audio", "AUD_1.m4a")
        MediaMigrator(oldRoot, newRoot) { _, _ -> }.migrate()
        val n2 = MediaMigrator(oldRoot, newRoot) { _, _ -> }.migrate()
        assertEquals(0, n2)
    }

    @Test
    fun `no old dir - returns zero`() {
        val n = MediaMigrator(tmp.newFolder("none"), tmp.newFolder("dst")) { _, _ -> }.migrate()
        assertEquals(0, n)
    }

    @Test
    fun `dest already exists with same content - source deleted, not recounted, dest untouched (idempotent)`() {
        val oldRoot = tmp.newFolder("olddup"); val newRoot = tmp.newFolder("newdup")
        seed(oldRoot, "video", "VID_1.mp4") // source content "x"
        val destDir = File(File(File(newRoot, "FieldSight"), "device"), "video").apply { mkdirs() }
        File(destDir, "VID_1.mp4").writeText("x") // pre-existing dest with SAME content
        val moved = mutableListOf<String>()
        val n = MediaMigrator(oldRoot, newRoot) { old, _ -> moved.add(old) }.migrate()
        assertEquals(0, n)                                   // duplicate not counted
        assertFalse(File(File(File(oldRoot, "media"), "video"), "VID_1.mp4").exists()) // source removed
        assertEquals("x", File(destDir, "VID_1.mp4").readText())               // dest NOT overwritten
    }

    @Test
    fun `cross-root same-name different-content preserves both`() {
        val root1 = tmp.newFolder("r1"); val root2 = tmp.newFolder("r2"); val newRoot = tmp.newFolder("dst")
        // same kind+name on two roots, DIFFERENT content
        File(File(File(root1, "media"), "video"), "VID_1.mp4").apply { parentFile!!.mkdirs(); writeText("AAA") }
        File(File(File(root2, "media"), "video"), "VID_1.mp4").apply { parentFile!!.mkdirs(); writeText("BBBBBB") }
        val moved = mutableListOf<String>()
        val n1 = MediaMigrator(root1, newRoot) { o, _ -> moved.add(o) }.migrate()
        val n2 = MediaMigrator(root2, newRoot) { o, _ -> moved.add(o) }.migrate()
        val destDir = File(File(File(newRoot, "FieldSight"), "device"), "video")
        assertEquals(1, n1); assertEquals(1, n2)
        assertEquals("AAA", File(destDir, "VID_1.mp4").readText())      // first preserved
        assertEquals("BBBBBB", File(destDir, "VID_1_1.mp4").readText()) // second under suffixed name, both kept
    }
}
