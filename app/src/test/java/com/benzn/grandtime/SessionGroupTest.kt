package com.benzn.grandtime

import com.benzn.grandtime.capture.SessionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The join payload for a multi-device meeting.
 *
 * The `fs1:` prefix is a namespace guard, and the reason it matters is that this
 * app already scans a LOGIN qr with a different parser. Scanning the wrong code
 * must fail cleanly rather than produce a nonsense group — a bad group is worse
 * than no group, because it merges two unrelated meetings into one report.
 */
class SessionGroupTest {
    private val sid = "a".repeat(32)

    @Test
    fun formatsWithNamespacePrefix() {
        assertEquals("fs1:$sid", SessionGroup.format(sid))
    }

    @Test
    fun parsesItsOwnFormat() {
        assertEquals(sid, SessionGroup.parse("fs1:$sid"))
    }

    @Test
    fun roundTrips() {
        assertEquals(sid, SessionGroup.parse(SessionGroup.format(sid)))
    }

    @Test
    fun rejectsAnotherAppsCode() {
        assertNull(SessionGroup.parse("https://example.com"))
        assertNull(SessionGroup.parse("random text"))
    }

    @Test
    fun rejectsABareSessionIdWithNoPrefix() {
        // Without the guard, any 32-hex string on any QR code would join a group.
        assertNull(SessionGroup.parse(sid))
    }

    @Test
    fun rejectsTheLoginQrSoTheWrongScanCannotJoinAGroup() {
        assertNull(SessionGroup.parse("fsqr1:somecode:prod"))
    }

    @Test
    fun rejectsAMalformedSessionId() {
        assertNull(SessionGroup.parse("fs1:not-hex"))
        assertNull(SessionGroup.parse("fs1:" + "a".repeat(31)))   // too short
        assertNull(SessionGroup.parse("fs1:" + "a".repeat(33)))   // too long
        assertNull(SessionGroup.parse("fs1:" + "A".repeat(32)))   // uppercase — backend requires lowercase
    }

    @Test
    fun toleratesSurroundingWhitespace() {
        // Some scanners append a newline.
        assertEquals(sid, SessionGroup.parse("  fs1:$sid\n"))
    }

    @Test
    fun rejectsNullAndEmpty() {
        assertNull(SessionGroup.parse(null))
        assertNull(SessionGroup.parse(""))
        assertNull(SessionGroup.parse("   "))
    }
}
