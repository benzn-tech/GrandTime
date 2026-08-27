package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingStartTest {

    private val t0 = 1_785_200_580_000L

    @Test fun a_fresh_press_claims_a_meeting_session() {
        assertEquals("meeting", MeetingStart.claim(pendingSinceMillis = t0, nowMillis = t0 + 800))
    }

    @Test fun no_pending_press_means_no_session_type() {
        assertNull(MeetingStart.claim(pendingSinceMillis = null, nowMillis = t0))
    }

    @Test fun a_stale_press_is_ignored() {
        // The button sets the flag and then asks the service to start. If the service was not
        // running, the start never happened and the flag would otherwise sit armed forever —
        // mislabelling whatever hardware-key recording comes next, hours later, as a meeting.
        assertNull(MeetingStart.claim(pendingSinceMillis = t0, nowMillis = t0 + MeetingStart.TTL_MILLIS + 1))
    }

    @Test fun a_press_at_the_ttl_boundary_still_counts() {
        assertEquals("meeting", MeetingStart.claim(pendingSinceMillis = t0, nowMillis = t0 + MeetingStart.TTL_MILLIS))
    }

    @Test fun a_clock_that_went_backwards_is_ignored() {
        assertNull(MeetingStart.claim(pendingSinceMillis = t0, nowMillis = t0 - 1))
    }
}
