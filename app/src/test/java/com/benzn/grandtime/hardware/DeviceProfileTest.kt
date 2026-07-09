package com.benzn.grandtime.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun `f2sp family models detected`() {
        listOf("SDJW-F2SP", "F2S-A", "SDJW-F2S", "XB-15").forEach {
            assertTrue(it, DeviceProfile.isF2spFamily(it))
        }
        assertFalse(DeviceProfile.isF2spFamily("Pixel 6"))
    }
}
