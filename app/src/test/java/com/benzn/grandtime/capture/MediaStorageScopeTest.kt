package com.benzn.grandtime.capture

import com.benzn.grandtime.auth.MediaScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaStorageScopeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `logged-out scope uses device folder and kind prefix`() {
        val root = tmp.newFolder("r")
        val s = MediaStorage({ root }, scopeProvider = { MediaScope("device", null) })
        val f = s.newFile(MediaStorage.Kind.VIDEO, 0L)
        assertEquals(File(File(File(root, "FieldSight"), "device"), "video"), f.parentFile)
        assert(f.name.startsWith("VID_")) { f.name }
    }

    @Test fun `logged-in scope uses user folder and username prefix`() {
        val root = tmp.newFolder("r2")
        val s = MediaStorage({ root }, scopeProvider = { MediaScope("jane_abc12345", "jane") })
        val f = s.newFile(MediaStorage.Kind.VIDEO, 0L)
        assertEquals(File(File(File(root, "FieldSight"), "jane_abc12345"), "video"), f.parentFile)
        assert(f.name.startsWith("jane_")) { f.name }
    }
}
