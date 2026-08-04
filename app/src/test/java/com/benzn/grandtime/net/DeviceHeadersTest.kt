package com.benzn.grandtime.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger's heartbeat rides these headers on org-api requests.
 *
 * Header names are fixed by the deployed backend (`src/device_heartbeat.py`)
 * and are matched case-insensitively there, but are sent exactly as written.
 */
class DeviceHeadersTest {

    @Test
    fun `all three headers are sent once the tag is known`() {
        val h = deviceHeaders(assetTag = "FS-07", uuid = "u1", appVersion = "1.4.2").toMap()
        assertEquals("FS-07", h["X-Device-Tag"])
        assertEquals("u1", h["X-Device-Id"])
        assertEquals("1.4.2", h["X-App-Version"])
    }

    @Test
    fun `an untagged device still reports its uuid so it can be claimed`() {
        val h = deviceHeaders(assetTag = null, uuid = "u1", appVersion = "1.4.2").toMap()
        assertFalse(
            "an absent tag must be omitted, not sent empty — the backend turns a " +
                "uuid-without-tag into an unclaimed row for a human to claim",
            h.containsKey("X-Device-Tag"),
        )
        assertEquals("u1", h["X-Device-Id"])
    }

    @Test
    fun `a blank tag is treated as absent`() {
        val h = deviceHeaders(assetTag = "   ", uuid = "u1", appVersion = "1.4.2").toMap()
        assertFalse(h.containsKey("X-Device-Tag"))
    }

    @Test
    fun `no uuid means no headers at all rather than empty ones`() {
        assertTrue(deviceHeaders(assetTag = null, uuid = "", appVersion = "1.4.2").isEmpty())
    }

    @Test
    fun `a device with no identity yet sends nothing even if it has a tag`() {
        assertTrue(deviceHeaders(assetTag = "FS-07", uuid = "", appVersion = "1.4.2").isEmpty())
    }

    @Test
    fun `a blank version is omitted rather than sent empty`() {
        val h = deviceHeaders(assetTag = "FS-07", uuid = "u1", appVersion = "").toMap()
        assertFalse(h.containsKey("X-App-Version"))
        assertEquals("u1", h["X-Device-Id"])
    }
}
