package com.benzn.grandtime

import com.benzn.grandtime.capture.SessionGroup
import org.junit.Assert.assertEquals
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
    private val NOPE = SessionGroup.Scan.NotAMeetingCode

    @Test
    fun formatsWithNamespacePrefix() {
        assertEquals("fs1:test:$sid", SessionGroup.format(sid, env = "test"))
    }

    @Test
    fun parsesItsOwnFormat() {
        assertEquals(SessionGroup.Scan.Ok(sid), SessionGroup.parse("fs1:test:$sid", env = "test"))
    }

    @Test
    fun roundTrips() {
        assertEquals(SessionGroup.Scan.Ok(sid),
                     SessionGroup.parse(SessionGroup.format(sid, env = "prod"), env = "prod"))
    }

    @Test
    fun rejectsAnotherAppsCode() {
        assertEquals(NOPE, SessionGroup.parse("https://example.com", env = "test"))
        assertEquals(NOPE, SessionGroup.parse("random text", env = "test"))
    }

    @Test
    fun rejectsABareSessionIdWithNoPrefix() {
        // Without the guard, any 32-hex string on any QR code would join a group.
        assertEquals(NOPE, SessionGroup.parse(sid, env = "test"))
    }

    @Test
    fun rejectsTheLoginQrSoTheWrongScanCannotJoinAGroup() {
        assertEquals(NOPE, SessionGroup.parse("fsqr1:somecode:prod", env = "test"))
    }

    @Test
    fun rejectsAMalformedSessionId() {
        assertEquals(NOPE, SessionGroup.parse("fs1:test:not-hex", env = "test"))
        assertEquals(NOPE, SessionGroup.parse("fs1:test:" + "a".repeat(31), env = "test"))   // too short
        assertEquals(NOPE, SessionGroup.parse("fs1:test:" + "a".repeat(33), env = "test"))   // too long
        assertEquals(NOPE, SessionGroup.parse("fs1:test:" + "A".repeat(32), env = "test"))   // uppercase
    }

    @Test
    fun toleratesSurroundingWhitespace() {
        // Some scanners append a newline.
        assertEquals(SessionGroup.Scan.Ok(sid), SessionGroup.parse("  fs1:test:$sid\n", env = "test"))
    }

    @Test
    fun rejectsNullAndEmpty() {
        assertEquals(NOPE, SessionGroup.parse(null, env = "test"))
        assertEquals(NOPE, SessionGroup.parse("", env = "test"))
        assertEquals(NOPE, SessionGroup.parse("   ", env = "test"))
    }

    // ---- the environment tag ------------------------------------------------
    //
    // Devices on different flavours talk to different backends and different
    // databases. Without a tag in the payload, a prod device scanning a dev
    // device's code succeeds, both record happily, the two groups land in two
    // separate databases, and NOTHING merges — with no error anywhere to
    // explain it. The login QR already refuses a cross-environment code; this
    // is the same guard for the same reason.

    @Test
    fun aCodeFromTheSameEnvironmentJoins() {
        val code = SessionGroup.format(sid, env = "test")
        assertEquals(SessionGroup.Scan.Ok(sid), SessionGroup.parse(code, env = "test"))
    }

    @Test
    fun aCodeFromTheOtherEnvironmentIsRefusedAndSaysSo() {
        // Distinguished from "not a meeting code" on purpose: the user needs to
        // be told to match the builds, not to scan again harder.
        val code = SessionGroup.format(sid, env = "prod")
        assertEquals(SessionGroup.Scan.WrongEnvironment, SessionGroup.parse(code, env = "test"))
    }

    @Test
    fun aLoginCodeIsStillJustNotAMeetingCode() {
        assertEquals(SessionGroup.Scan.NotAMeetingCode,
                     SessionGroup.parse("fs-login:abc", env = "test"))
    }

    @Test
    fun aMalformedSessionIdIsNotAMeetingCode() {
        assertEquals(SessionGroup.Scan.NotAMeetingCode,
                     SessionGroup.parse("fs1:test:nothex", env = "test"))
    }

    @Test
    fun theTagTravelsInTheCodeItself() {
        // Pinning the wire format: it is rendered into a QR on one device and
        // read on another, so the two halves can only agree by construction.
        assertEquals("fs1:test:$sid", SessionGroup.format(sid, env = "test"))
    }
}
