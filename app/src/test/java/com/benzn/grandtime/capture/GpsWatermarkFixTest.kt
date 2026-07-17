package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsWatermarkFixTest {

    @Test fun `gpsAgeLabel under a minute shows seconds`() {
        assertEquals("~45s ago", gpsAgeLabel(45_000))
    }

    @Test fun `gpsAgeLabel zero shows 0s ago`() {
        assertEquals("~0s ago", gpsAgeLabel(0))
    }

    @Test fun `gpsAgeLabel just over a minute shows minutes`() {
        assertEquals("~1m ago", gpsAgeLabel(65_000))
    }

    @Test fun `gpsAgeLabel five minutes shows minutes`() {
        assertEquals("~5m ago", gpsAgeLabel(300_000))
    }

    @Test fun `fresh fix present yields coords with no age label`() {
        val g = gpsWatermark(fresh = 1.0 to 2.0, aged = GpsTracker.AgedFix(9.0, 9.0, 999_999))
        assertEquals(1.0, g.lat)
        assertEquals(2.0, g.lon)
        assertNull(g.ageLabel)
    }

    @Test fun `no fresh but aged within window yields coords with age label`() {
        val g = gpsWatermark(fresh = null, aged = GpsTracker.AgedFix(1.0, 2.0, 45_000))
        assertEquals(1.0, g.lat)
        assertEquals(2.0, g.lon)
        assertEquals("~45s ago", g.ageLabel)
    }

    @Test fun `no fresh and aged beyond window yields no coords`() {
        val g = gpsWatermark(fresh = null, aged = GpsTracker.AgedFix(1.0, 2.0, GPS_FALLBACK_WINDOW_MS + 1))
        assertNull(g.lat)
        assertNull(g.lon)
        assertNull(g.ageLabel)
    }

    @Test fun `no fresh and no aged yields no coords`() {
        val g = gpsWatermark(fresh = null, aged = null)
        assertNull(g.lat)
        assertNull(g.lon)
        assertNull(g.ageLabel)
    }

    @Test fun `boundary aged exactly at window is still shown`() {
        val g = gpsWatermark(fresh = null, aged = GpsTracker.AgedFix(1.0, 2.0, GPS_FALLBACK_WINDOW_MS))
        assertEquals(1.0, g.lat)
        assertEquals(2.0, g.lon)
        assertEquals("~10m ago", g.ageLabel)
    }
}
