package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GpsFixStateTest {

    @Test fun `fix outside a recording is kept but not tracked`() {
        val s = GpsFixState()
        s.onFix(1.0, 2.0, gpsTime = 100, nowMillis = 1_000)
        assertEquals(1.0 to 2.0, s.freshFix(nowMillis = 1_000))
        assertEquals(0, s.trackPoints().size)
    }

    @Test fun `beginTrack keeps the warm fix so the first segment has coords at once`() {
        val s = GpsFixState()
        s.onFix(1.0, 2.0, gpsTime = 100, nowMillis = 1_000)
        s.beginTrack()
        assertEquals(1.0 to 2.0, s.freshFix(nowMillis = 2_000))
    }

    @Test fun `beginTrack starts an empty track and fixes during it are tracked`() {
        val s = GpsFixState()
        s.beginTrack()
        s.onFix(1.0, 2.0, gpsTime = 100, nowMillis = 1_000)
        s.endTrack()
        s.beginTrack()
        s.onFix(3.0, 4.0, gpsTime = 200, nowMillis = 2_000)
        assertEquals(listOf(GpsFixState.TrackPoint(200, 3.0, 4.0)), s.trackPoints())
    }

    @Test fun `endTrack keeps the fix and the track for the last segment row`() {
        val s = GpsFixState()
        s.beginTrack()
        s.onFix(1.0, 2.0, gpsTime = 100, nowMillis = 1_000)
        s.endTrack()
        s.onFix(5.0, 6.0, gpsTime = 300, nowMillis = 3_000)
        assertEquals(5.0 to 6.0, s.freshFix(nowMillis = 3_000))
        assertEquals(listOf(GpsFixState.TrackPoint(100, 1.0, 2.0)), s.trackPoints())
    }

    @Test fun `fix older than 30s is not fresh but is still aged`() {
        val s = GpsFixState()
        s.onFix(1.0, 2.0, gpsTime = 100, nowMillis = 1_000)
        assertNull(s.freshFix(nowMillis = 31_001))
        assertEquals(30_001L, s.agedFix(nowMillis = 31_001)!!.ageMs)
    }

    @Test fun `seed from last known fix carries its real age`() {
        val s = GpsFixState()
        // last known fix taken 90 s ago on the elapsed-realtime clock
        s.seed(1.0, 2.0, fixElapsedNanos = 10_000_000_000L, nowElapsedNanos = 100_000_000_000L, nowMillis = 500_000)
        assertNull(s.freshFix(nowMillis = 500_000))
        assertEquals(90_000L, s.agedFix(nowMillis = 500_000)!!.ageMs)
    }

    @Test fun `seed never overwrites a live fix`() {
        val s = GpsFixState()
        s.onFix(1.0, 2.0, gpsTime = 100, nowMillis = 1_000)
        s.seed(9.0, 9.0, fixElapsedNanos = 0, nowElapsedNanos = 1_000_000_000L, nowMillis = 1_000)
        assertEquals(1.0 to 2.0, s.freshFix(nowMillis = 1_000))
    }

    @Test fun `seed is not tracked even during a recording`() {
        val s = GpsFixState()
        s.beginTrack()
        s.seed(1.0, 2.0, fixElapsedNanos = 0, nowElapsedNanos = 1_000_000_000L, nowMillis = 1_000)
        assertNotNull(s.agedFix(nowMillis = 1_000))
        assertEquals(0, s.trackPoints().size)
    }
}
