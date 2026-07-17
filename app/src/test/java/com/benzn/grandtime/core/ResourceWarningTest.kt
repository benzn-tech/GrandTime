package com.benzn.grandtime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceWarningTest {
    private val plentyBytes = 10L * 1024 * 1024 * 1024 // 10 GB
    private val plentyPct = 80

    @Test fun `plenty of storage and battery yields no warnings`() {
        val status = assessResources(plentyBytes, plentyPct, charging = false)
        assertEquals(WarnLevel.NONE, status.storage)
        assertEquals(WarnLevel.NONE, status.battery)
        assertFalse(status.hasWarning)
    }

    @Test fun `1GB free triggers storage warning`() {
        val status = assessResources(1L * 1024 * 1024 * 1024, plentyPct, charging = false)
        assertEquals(WarnLevel.WARNING, status.storage)
        assertTrue(status.hasWarning)
    }

    @Test fun `400MB free triggers storage critical`() {
        val status = assessResources(400L * 1024 * 1024, plentyPct, charging = false)
        assertEquals(WarnLevel.CRITICAL, status.storage)
        assertTrue(status.hasWarning)
    }

    @Test fun `15 percent battery not charging triggers battery warning`() {
        val status = assessResources(plentyBytes, 15, charging = false)
        assertEquals(WarnLevel.WARNING, status.battery)
        assertTrue(status.hasWarning)
    }

    @Test fun `8 percent battery not charging triggers battery critical`() {
        val status = assessResources(plentyBytes, 8, charging = false)
        assertEquals(WarnLevel.CRITICAL, status.battery)
        assertTrue(status.hasWarning)
    }

    @Test fun `8 percent battery while charging suppresses battery warning`() {
        val status = assessResources(plentyBytes, 8, charging = true)
        assertEquals(WarnLevel.NONE, status.battery)
        assertFalse(status.hasWarning)
    }

    @Test fun `exactly 2GB free is NOT a warning (boundary)`() {
        val status = assessResources(2L * 1024 * 1024 * 1024, plentyPct, charging = false)
        assertEquals(WarnLevel.NONE, status.storage)
    }

    @Test fun `just under 2GB free IS a warning (boundary)`() {
        val status = assessResources(2L * 1024 * 1024 * 1024 - 1, plentyPct, charging = false)
        assertEquals(WarnLevel.WARNING, status.storage)
    }

    @Test fun `exactly 512MB free is WARNING not critical (less-than excludes the exact boundary)`() {
        val status = assessResources(512L * 1024 * 1024, plentyPct, charging = false)
        assertEquals(WarnLevel.WARNING, status.storage)
    }

    @Test fun `just under 512MB free IS critical (boundary)`() {
        val status = assessResources(512L * 1024 * 1024 - 1, plentyPct, charging = false)
        assertEquals(WarnLevel.CRITICAL, status.storage)
    }

    @Test fun `exactly 20 percent battery not charging IS a warning (boundary)`() {
        val status = assessResources(plentyBytes, 20, charging = false)
        assertEquals(WarnLevel.WARNING, status.battery)
    }

    @Test fun `21 percent battery not charging is NOT a warning (boundary)`() {
        val status = assessResources(plentyBytes, 21, charging = false)
        assertEquals(WarnLevel.NONE, status.battery)
    }

    @Test fun `exactly 10 percent battery not charging IS critical (boundary)`() {
        val status = assessResources(plentyBytes, 10, charging = false)
        assertEquals(WarnLevel.CRITICAL, status.battery)
    }

    @Test fun `11 percent battery not charging is warning not critical (boundary)`() {
        val status = assessResources(plentyBytes, 11, charging = false)
        assertEquals(WarnLevel.WARNING, status.battery)
    }

    @Test fun `both storage and battery warn simultaneously`() {
        val status = assessResources(1L * 1024 * 1024 * 1024, 15, charging = false)
        assertEquals(WarnLevel.WARNING, status.storage)
        assertEquals(WarnLevel.WARNING, status.battery)
        assertTrue(status.hasWarning)
    }

    @Test fun `freeBytes and batteryPct are carried through on the status`() {
        val status = assessResources(123L, 42, charging = false)
        assertEquals(123L, status.freeBytes)
        assertEquals(42, status.batteryPct)
    }
}
