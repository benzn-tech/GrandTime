package com.benzn.grandtime.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The deliberate End must reach the durable queue, and the source must keep
 * saying why.
 *
 * These are source-level assertions rather than behavioural ones because
 * CaptureManager needs a real Android context to construct and WorkManager
 * cannot run on the JVM. That is a genuine limitation, so the tests pin the two
 * things a future edit is most likely to undo silently: the routing itself, and
 * the network constraint that decides whether a site with no WiFi gets an email.
 */
class SessionCloseRoutingTest {

    private fun src(path: String) = File("src/main/java/com/benzn/grandtime/$path").readText()

    @Test
    fun `a deliberate end goes through the enqueuer, not the fire-and-forget call`() {
        val s = src("capture/CaptureManager.kt")
        val branch = s.substringAfter("private fun fireSessionClose")
        val guard = branch.indexOf("""if (intent == "end")""")
        val direct = branch.indexOf("sessionsApi.close(")
        assertTrue("the end branch is gone — deliberate ends are back to fire-and-forget", guard >= 0)
        assertTrue(
            "the end branch must come BEFORE the direct call, or both fire and the " +
                "close can overtake the uploads it is supposed to wait for",
            guard < direct,
        )
    }

    @Test
    fun `the close worker waits for this session's chunks`() {
        // grace is 0 for a deliberate End, so a close that arrives before the
        // last chunks do finalizes a transcript that is still arriving — and a
        // `finalizing` session is not resumed by a later chunk.
        val s = src("upload/SessionCloseWorker.kt")
        assertTrue("no in-flight check", s.contains("countInFlightForSession"))
        assertTrue("must retry, not send, while chunks are outstanding",
            s.substringAfter("countInFlightForSession").contains("Result.retry()"))
    }

    @Test
    fun `the close is never gated on unmetered network`() {
        // UNMETERED on a site with no WiFi is not "later", it is never — the same
        // defect that stopped video chunks uploading at all.
        val s = src("upload/SessionCloseEnqueuer.kt")
        assertTrue("must require only CONNECTED", s.contains("NetworkType.CONNECTED"))
        // The USE, not the word — the comment above the constraint explains why
        // UNMETERED is wrong here, and that explanation is worth keeping.
        assertEquals("must not be gated on unmetered", false, s.contains("NetworkType.UNMETERED"))
    }

    @Test
    fun `a missing token retries rather than giving up`() {
        // The old call did `?: return@launch` — a silent give-up, and the single
        // most likely reason a close was lost with nothing logged.
        val s = src("upload/SessionCloseWorker.kt")
        val afterToken = s.substringAfter("freshIdToken()")
        assertTrue("a null token must retry, not fail or return silently",
            afterToken.take(400).contains("Result.retry()"))
    }

    @Test
    fun `a repeated end does not restart the backoff`() {
        // REPLACE would push the email further away each time the user taps End.
        val s = src("upload/SessionCloseEnqueuer.kt")
        assertTrue(s.contains("ExistingWorkPolicy.KEEP"))
    }
}
