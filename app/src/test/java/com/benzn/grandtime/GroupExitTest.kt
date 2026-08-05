package com.benzn.grandtime

import com.benzn.grandtime.capture.GroupExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving a meeting group.
 *
 * The failure this guards against: an inspector joins a meeting, walks off with
 * the device, records somewhere else the next day, and that audio merges into
 * yesterday's meeting. The result reads perfectly fluently, so nobody notices.
 */
class GroupExitTest {
    private val MIN = 60_000L

    // ---- two actions, not one ---------------------------------------------

    @Test
    fun meetingEndedClearsMineAndTellsTheOthers() {
        val o = GroupExit.resolve(GroupExit.Decision.MEETING_ENDED)
        assertTrue(o.clearsGroup)
        assertTrue(o.notifiesOthers)
    }

    @Test
    fun leavingClearsOnlyMine() {
        // An inspector finishing early must not stop everyone else's recording.
        // Without this second action, using MEETING_ENDED would do exactly that.
        val o = GroupExit.resolve(GroupExit.Decision.I_AM_LEAVING)
        assertTrue(o.clearsGroup)
        assertFalse(o.notifiesOthers)
    }

    @Test
    fun notYetKeepsTheGroupAndDisturbsNobody() {
        val o = GroupExit.resolve(GroupExit.Decision.NOT_YET)
        assertFalse(o.clearsGroup)
        assertFalse(o.notifiesOthers)
        assertFalse(o.asksToResume)
    }

    @Test
    fun bothExitsAskAboutResuming() {
        // Ending a meeting is not finishing work: the person may be walking to
        // the next task or done for the day. No safe default, so it is asked —
        // and asked the same way for both, so this is one behaviour rather than
        // a special case per exit.
        for (d in listOf(GroupExit.Decision.MEETING_ENDED, GroupExit.Decision.I_AM_LEAVING)) {
            assertTrue("$d must ask", GroupExit.resolve(d).asksToResume)
        }
    }

    // ---- expiry: the backstop, not the mechanism ---------------------------

    @Test
    fun anOrdinaryPauseWithinTheMeetingKeepsTheGroup() {
        // Battery swap, walking to the next building, taking a call. If these
        // dropped the group, one meeting would split into two reports and the
        // user would have to re-scan for nothing.
        assertFalse(GroupExit.hasExpired(lastStopAtMillis = 0, nowMillis = 2 * MIN))
        assertFalse(GroupExit.hasExpired(lastStopAtMillis = 0, nowMillis = 14 * MIN))
    }

    @Test
    fun theGroupClearsAfterTheSessionGap() {
        assertTrue(GroupExit.hasExpired(lastStopAtMillis = 0, nowMillis = 16 * MIN))
    }

    @Test
    fun expiryUsesTheSessionGapNotTheMisTouchWindow() {
        // 30s is STOP_GRACE_SECONDS on the backend — the mis-touch window.
        // Using it here would expire the group during any real pause.
        assertFalse(GroupExit.hasExpired(lastStopAtMillis = 0, nowMillis = 31_000))
        assertEquals(15 * MIN, GroupExit.EXPIRY_MILLIS)
    }

    @Test
    fun theNextDayHasDefinitelyExpired() {
        assertTrue(GroupExit.hasExpired(lastStopAtMillis = 0, nowMillis = 23 * 60 * MIN))
    }

    @Test
    fun aClockThatJumpedBackwardsDoesNotResurrectAGroup() {
        // These ROMs have been observed 12 hours out. A negative elapsed time
        // must not read as "still fresh" in a way that silently keeps a stale
        // group alive — and regardless, the server guard is what actually holds.
        assertFalse(GroupExit.hasExpired(lastStopAtMillis = 10 * MIN, nowMillis = 0))
    }

    // ---- reading the pending group -----------------------------------------
    //
    // Expiry is enforced HERE rather than by each caller remembering to check
    // it. A call site that forgets would silently send a stale group id, which
    // is the exact failure this whole mechanism exists to prevent — so the only
    // way to read the group is a way that cannot skip the check.

    @Test
    fun aMeetingLongerThanTheWindowSurvivesBecauseTheClockRefreshes() {
        // A three-hour meeting recorded continuously must NOT expire at 15 min.
        // The timestamp is "when the expiry clock started", refreshed on every
        // stop — not "when I joined". Naming it after joining would make this
        // case look correct in review and fail in the field.
        val g = GroupExit.PendingGroup("b".repeat(32), heldSinceMillis = 170 * MIN)
        assertEquals("b".repeat(32), GroupExit.activeGroupId(g, nowMillis = 180 * MIN))
    }

    @Test
    fun activeGroupIdReturnsTheGroupWhileItIsFresh() {
        val g = GroupExit.PendingGroup("b".repeat(32), heldSinceMillis = 0)
        assertEquals("b".repeat(32), GroupExit.activeGroupId(g, nowMillis = 5 * MIN))
    }

    @Test
    fun activeGroupIdReturnsNullOnceExpired() {
        val g = GroupExit.PendingGroup("b".repeat(32), heldSinceMillis = 0)
        assertNull(GroupExit.activeGroupId(g, nowMillis = 16 * MIN))
    }

    @Test
    fun activeGroupIdOfNothingIsNull() {
        assertNull(GroupExit.activeGroupId(null, nowMillis = 0))
    }

    @Test
    fun theNextDaysRecordingNeverCarriesYesterdaysGroup() {
        // THE guarantee, at the device layer: a device that kept a group
        // overnight must not attach it to tomorrow's recording. (The server
        // enforces this too, on its own clock, because this check trusts the
        // device's — see lead_is_joinable / group_span_ok.)
        val g = GroupExit.PendingGroup("b".repeat(32), heldSinceMillis = 0)
        assertNull(GroupExit.activeGroupId(g, nowMillis = 22 * 60 * MIN))
    }
}
