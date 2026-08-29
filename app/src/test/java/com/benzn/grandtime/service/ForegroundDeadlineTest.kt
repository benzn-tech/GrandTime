package com.benzn.grandtime.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level pins for the foreground-service deadline, in the same style as
 * CaptureLifecycleSerializationTest: there is no JVM harness for a Service here, and the thing
 * worth protecting is a call being present on a path, which is exactly what a source pin holds.
 *
 * Every startForegroundService() arms a fresh 5-second deadline in ActivityManagerService whether
 * or not the service is already running, and only startForeground() disarms it. onCreate covers
 * the first start and nothing after it. MainActivity.onResume re-kicks startForegroundService on
 * every resume, so without a re-assert in onStartCommand a resume landing on a busy main thread
 * kills the process outright — reproduced on F2S202503103059 on 2026-08-29, and it took the
 * recording that was finishing with it.
 */
class ForegroundDeadlineTest {

    /**
     * The source with every comment removed.
     *
     * The first draft of this test searched the raw text and PASSED on the word
     * `startForeground()` appearing in the comment that explains why the call is there — a test
     * that would have stayed green with the call itself deleted. What is asserted here is that
     * code exists, so comments must not be part of what is searched.
     */
    private val service = File(
        "src/main/java/com/benzn/grandtime/service/CoreService.kt"
    ).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString(SEPARATOR) { it.substringBefore("//") }

    private fun body(signature: String, chars: Int = 1_200): String {
        val start = service.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        return service.substring(start, minOf(start + chars, service.length))
    }

    @Test
    fun `the comment stripper works, so the pins above cannot pass on prose`() {
        assertTrue("comments must be gone", !service.contains("ActivityManagerService"))
        assertTrue("code must survive", service.contains("override fun onStartCommand"))
    }

    @Test
    fun `onStartCommand re-asserts foreground`() {
        val b = body("override fun onStartCommand")
        assertTrue(
            "onStartCommand must call startForeground — it is the only thing that disarms a " +
                "deadline armed by a startForegroundService this service did not handle in onCreate",
            b.contains("startForeground("),
        )
    }

    @Test
    fun `onCreate still goes foreground first`() {
        val b = body("override fun onCreate", chars = 600)
        assertTrue("onCreate must still go foreground", b.contains("startForeground("))
    }

    @Test
    fun `the re-assert keeps the notification's current text`() {
        // Hardcoding "Standing by" here would reset a live "Recording video" every time the
        // operator opens the app — the notification is the only place a screen-off device says
        // what it is doing.
        val b = body("override fun onStartCommand")
        val call = b.indexOf("startForeground(")
        assertTrue("startForeground call not found", call >= 0)
        val args = b.substring(call, minOf(call + 120, b.length))
        assertTrue(
            "the re-assert must reuse the current notification text, not a literal",
            args.contains("lastNotificationText"),
        )
    }

    @Test
    fun `notifyStatus records what it published`() {
        val b = body("private fun notifyStatus", chars = 300)
        assertTrue(
            "notifyStatus must record the text so onStartCommand can re-assert with it",
            b.contains("lastNotificationText = text"),
        )
    }
}

private const val SEPARATOR = "\n"
