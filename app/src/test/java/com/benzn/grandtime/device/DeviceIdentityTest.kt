package com.benzn.grandtime.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who this device says it is.
 *
 * The label stuck on the case is authoritative and the ROM's identifier is only
 * a hint, because these units were likely flashed from one factory image and
 * may all report the same ANDROID_ID — and this ROM already misreports
 * SENSOR_ORIENTATION, so its identifiers are not to be taken on trust.
 */
class DeviceIdentityTest {

    // --- uuid resolution ---

    @Test
    fun `a plausible ANDROID_ID is used as-is`() {
        assertEquals(
            "a3f9c2d1e0b74a15",
            resolveUuid(androidId = "a3f9c2d1e0b74a15", stored = null, mint = { "MINTED" }),
        )
    }

    @Test
    fun `a null ANDROID_ID falls back to a minted uuid`() {
        assertEquals("MINTED", resolveUuid(androidId = null, stored = null, mint = { "MINTED" }))
    }

    @Test
    fun `a blank ANDROID_ID falls back to a minted uuid`() {
        assertEquals("MINTED", resolveUuid(androidId = "   ", stored = null, mint = { "MINTED" }))
    }

    @Test
    fun `an all-zero ANDROID_ID is rejected`() {
        assertEquals(
            "MINTED",
            resolveUuid(androidId = "0000000000000000", stored = null, mint = { "MINTED" }),
        )
    }

    @Test
    fun `the well-known emulator ANDROID_ID is rejected`() {
        assertEquals(
            "MINTED",
            resolveUuid(androidId = "9774d56d682e549c", stored = null, mint = { "MINTED" }),
        )
    }

    @Test
    fun `a stored value always wins, so the id survives an OS upgrade that rotates ANDROID_ID`() {
        assertEquals(
            "STORED",
            resolveUuid(androidId = "a3f9c2d1e0b74a15", stored = "STORED", mint = { "MINTED" }),
        )
    }

    @Test
    fun `a blank stored value is not treated as stored`() {
        assertEquals("MINTED", resolveUuid(androidId = null, stored = "  ", mint = { "MINTED" }))
    }

    @Test
    fun `ANDROID_ID is normalised so case differences do not look like two devices`() {
        assertEquals(
            "a3f9c2d1e0b74a15",
            resolveUuid(androidId = "  A3F9C2D1E0B74A15 ", stored = null, mint = { "MINTED" }),
        )
    }

    @Test
    fun `minting twice never produces the same id`() {
        val a = resolveUuid(null, null) { java.util.UUID.randomUUID().toString() }
        val b = resolveUuid(null, null) { java.util.UUID.randomUUID().toString() }
        assertNotEquals(a, b)
    }

    // --- asset tag ---

    private class FakeStore : IdentityStore {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String) { map[key] = value }
    }

    @Test
    fun `an asset tag is trimmed and upper-cased`() {
        val s = FakeStore()
        writeAssetTag(s, "  fs-07 ")
        assertEquals("FS-07", readAssetTag(s))
    }

    @Test
    fun `a blank asset tag is not stored`() {
        val s = FakeStore()
        writeAssetTag(s, "   ")
        assertNull(readAssetTag(s))
    }

    @Test
    fun `an unset asset tag reads back as null, not as empty`() {
        assertNull(readAssetTag(FakeStore()))
    }

    @Test
    fun `a stored tag survives being read many times`() {
        val s = FakeStore()
        writeAssetTag(s, "FS-12")
        assertEquals("FS-12", readAssetTag(s))
        assertEquals("FS-12", readAssetTag(s))
    }

    // --- short code ---

    @Test
    fun `the short code is the first six characters of the uuid`() {
        assertEquals("a3f9c2", shortCodeOf("a3f9c2d1e0b74a15"))
    }

    @Test
    fun `a short uuid yields itself rather than throwing`() {
        assertEquals("abc", shortCodeOf("abc"))
    }

    @Test
    fun `an empty uuid yields an empty short code`() {
        assertTrue(shortCodeOf("").isEmpty())
    }
}
