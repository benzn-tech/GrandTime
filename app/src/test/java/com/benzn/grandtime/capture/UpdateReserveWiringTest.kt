package com.benzn.grandtime.capture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins how CaptureManager uses the update reserve. Source-level, because the behaviour lives in
 * suspend functions over Android storage; the rules themselves are driven in StoragePolicyTest and
 * StorageReclaimerTest.
 *
 * Measured on DQF2S 2026-09-15: rolling overwrite reclaiming only back to the floors left the device
 * at 265 MB free, below Android's install line, so no update could be installed.
 */
class UpdateReserveWiringTest {

    private val manager = File("src/main/java/com/benzn/grandtime/capture/CaptureManager.kt").readText()

    private fun body(signature: String): String {
        val start = manager.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val end = manager.indexOf("\n    }", start)
        return manager.substring(start, end)
    }

    @Test
    fun `the update reserve is only ever bought with uploaded recordings`() {
        val reserve = body("private suspend fun keepUpdateReserve(")
        assertTrue(reserve.contains("StoragePolicy.belowUpdateReserve("))
        assertTrue(
            "an update-reserve reclaim that may take unsent footage deletes it for headroom nobody is using",
            reserve.contains("uploadedOnly = true"),
        )
    }

    @Test
    fun `both space checks keep the reserve before deciding on the floors`() {
        for (fn in listOf("private suspend fun ensureRoomToStart(", "private suspend fun ensureRoomToContinue(")) {
            val b = body(fn)
            val reserve = b.indexOf("keepUpdateReserve(")
            val floor = b.indexOf("StoragePolicy.can")
            assertTrue("$fn does not keep the update reserve", reserve >= 0)
            assertTrue("$fn must keep the reserve BEFORE the floor check", reserve < floor)
        }
    }

    @Test
    fun `the floors still decide whether capture goes on`() {
        // The reserve is a trigger, never a stop line: the return values stay on the floors.
        assertTrue(body("private suspend fun ensureRoomToStart(").contains("return StoragePolicy.canStart("))
        assertTrue(body("private suspend fun ensureRoomToContinue(").contains("return StoragePolicy.canContinue("))
        assertTrue(!body("private suspend fun keepUpdateReserve(").contains("return"))
    }
}
