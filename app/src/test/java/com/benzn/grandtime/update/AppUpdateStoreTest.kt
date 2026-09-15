package com.benzn.grandtime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppUpdateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ready = ReadyUpdate(41, "0.7.15", 40, "ab".repeat(32), "41.apk", "notes")

    @Test
    fun `a stored record reads back exactly`() {
        val store = AppUpdateStore(File(tmp.root, "updates"))
        store.write(ready)
        assertEquals(ready, store.read())
    }

    @Test
    fun `nothing stored reads as nothing ready`() {
        assertNull(AppUpdateStore(File(tmp.root, "updates")).read())
    }

    @Test
    fun `a corrupt record reads as nothing ready`() {
        val dir = File(tmp.root, "updates").apply { mkdirs() }
        File(dir, "ready.json").writeText("{ not json")
        assertNull(AppUpdateStore(dir).read())
    }

    @Test
    fun `clear removes downloads and the record`() {
        val store = AppUpdateStore(File(tmp.root, "updates"))
        store.write(ready)
        store.finalFile(41).writeBytes(ByteArray(10))
        store.partFile(42).writeBytes(ByteArray(10))
        store.clear()
        assertNull(store.read())
        assertFalse(store.finalFile(41).exists())
        assertFalse(store.partFile(42).exists())
    }

    @Test
    fun `an interrupted download never has the ready name`() {
        val store = AppUpdateStore(File(tmp.root, "updates"))
        assertTrue(store.partFile(41).name.endsWith(".part"))
        assertEquals(ready.fileName, store.finalFile(41).name)
    }
}
