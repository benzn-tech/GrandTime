package com.benzn.grandtime.upload

import com.benzn.grandtime.db.CaptureRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun frozen(
    authorSub: String? = "sub-a",
    code: String = "uploadurl_403",
    build: String? = "build-1",
) = CaptureRecord(
    id = "r", kind = "video", filePath = "/x", fileName = "f.mp4",
    startedAt = 0, codec = "h264", sessionId = "s", createdAt = 0,
    uploadStatus = "frozen", authorSub = authorSub,
    failureClass = FailureClass.OPERATOR_FIXABLE.name, failureCode = code,
    frozenAtBuild = build, frozenSinceMs = 1,
)

class ThawDecisionTest {

    @Test fun `a redeploy thaws what it may have fixed`() {
        assertTrue(ThawDecision.shouldThaw(frozen(), "sub-a", "build-2", emptyList()))
    }

    @Test fun `the same build thaws nothing`() {
        assertFalse(ThawDecision.shouldThaw(frozen(), "sub-a", "build-1", emptyList()))
    }

    @Test fun `an unknown current build thaws nothing`() {
        assertFalse(ThawDecision.shouldThaw(frozen(), "sub-a", null, emptyList()))
    }

    @Test fun `an explicit instruction thaws`() {
        assertTrue(ThawDecision.shouldThaw(frozen(), "sub-a", "build-1", listOf("uploadurl_403")))
    }

    @Test fun `an instruction for a different fingerprint thaws nothing`() {
        assertFalse(ThawDecision.shouldThaw(frozen(), "sub-a", "build-1", listOf("complete_403")))
    }

    /**
     * These devices rotate between clients monthly, and a uploadurl_403 is most likely an
     * identity mis-scoping — precisely the failure a NEW account might be allowed to commit.
     * Thawing it under whoever is signed in now is the cross-tenant upload leak, again.
     */
    @Test fun `a thaw never crosses accounts`() {
        assertFalse(ThawDecision.shouldThaw(frozen(authorSub = "sub-a"), "sub-b", "build-2", listOf("uploadurl_403")))
    }

    @Test fun `an unowned row is never thawed`() {
        assertFalse(ThawDecision.shouldThaw(frozen(authorSub = null), null, "build-2", listOf("uploadurl_403")))
    }

    @Test fun `an unowned row is not thawed even when someone is signed in`() {
        assertFalse(ThawDecision.shouldThaw(frozen(authorSub = null), "sub-a", "build-2", listOf("uploadurl_403")))
    }

    @Test fun `nobody signed in thaws nothing`() {
        assertFalse(ThawDecision.shouldThaw(frozen(), null, "build-2", listOf("uploadurl_403")))
    }

    /** Null build must not read as "different". Otherwise: thaw, refail, refreeze, forever. */
    @Test fun `a record frozen before the first probe is adopted, not thawed`() {
        val r = frozen(build = null)
        assertFalse(ThawDecision.shouldThaw(r, "sub-a", "build-2", emptyList()))
        assertTrue(ThawDecision.shouldAdoptBuild(r))
    }

    @Test fun `an explicit instruction still thaws a record with no build`() {
        assertTrue(ThawDecision.shouldThaw(frozen(build = null), "sub-a", "build-2", listOf("uploadurl_403")))
    }

    @Test fun `a record that already knows the build is not re-adopted`() {
        assertFalse(ThawDecision.shouldAdoptBuild(frozen()))
    }

    @Test fun `a record that is not frozen is not adopted`() {
        val notFrozen = frozen(build = null).copy(uploadStatus = "pending", frozenSinceMs = null)
        assertFalse(ThawDecision.shouldAdoptBuild(notFrozen))
    }
}
