package com.benzn.grandtime.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class AskRecorderTest {

    // --- pure file-naming (JVM) ---

    @Test fun clip_file_is_under_cache_dir_and_m4a() {
        val cache = File("build/tmp/ask-test")
        val f = AskRecorder.clipFile(cache, 1_700_000_000_000L)
        assertTrue(f.path.replace('\\', '/').contains("ask-test"))
        assertTrue(f.name.endsWith(".m4a"))
        assertTrue(f.name.contains("1700000000000"))
    }

    @Test fun distinct_timestamps_give_distinct_files() {
        val cache = File("build/tmp/ask-test")
        val a = AskRecorder.clipFile(cache, 1L)
        val b = AskRecorder.clipFile(cache, 2L)
        assertTrue(a.name != b.name)
    }

    // --- failure-path deletion + double-start guard, via injected fake recorder ---

    private fun freshCache(): File =
        File("build/tmp/ask-test-${UUID.randomUUID()}").apply { mkdirs() }

    private fun clipsIn(dir: File): List<File> =
        dir.listFiles { f -> f.name.endsWith(".m4a") }?.toList() ?: emptyList()

    /** Fake [AskRecorder.Recorder]: simulates MediaRecorder writing bytes to disk. */
    private class FakeRecorder(
        val startResult: Boolean = true,
        val stopResult: Boolean = true,
        val bytesOnStart: ByteArray = byteArrayOf(1, 2, 3),
    ) : AskRecorder.Recorder {
        var startCalls = 0
        var stopCalls = 0
        private var recording = false
        override val isRecording: Boolean get() = recording
        override fun start(file: File): Boolean {
            startCalls++
            // MediaRecorder may leave partial bytes on disk even when start() ultimately fails.
            file.parentFile?.mkdirs()
            file.writeBytes(bytesOnStart)
            recording = startResult
            return startResult
        }
        override fun stop(): Boolean {
            stopCalls++
            recording = false
            return stopResult
        }
    }

    @Test fun start_failure_returns_false_and_leaves_no_temp_clip() {
        val cache = freshCache()
        val fake = FakeRecorder(startResult = false)
        val rec = AskRecorder(cache, fake)

        assertFalse(rec.start())
        assertTrue("temp clip must be deleted on start failure", clipsIn(cache).isEmpty())
    }

    @Test fun stop_with_empty_file_returns_null_and_deletes_temp_clip() {
        val cache = freshCache()
        val fake = FakeRecorder(startResult = true, stopResult = true, bytesOnStart = ByteArray(0))
        val rec = AskRecorder(cache, fake)

        assertTrue(rec.start())
        assertNull("empty clip must not be returned", rec.stop())
        assertTrue("empty temp clip must be deleted on stop", clipsIn(cache).isEmpty())
    }

    @Test fun stop_with_recorder_failure_returns_null_and_deletes_temp_clip() {
        val cache = freshCache()
        val fake = FakeRecorder(startResult = true, stopResult = false)
        val rec = AskRecorder(cache, fake)

        assertTrue(rec.start())
        assertNull(rec.stop())
        assertTrue("temp clip must be deleted when recorder.stop() fails", clipsIn(cache).isEmpty())
    }

    @Test fun double_start_returns_false_without_touching_recorder() {
        val cache = freshCache()
        val fake = FakeRecorder(startResult = true)
        val rec = AskRecorder(cache, fake)

        assertTrue(rec.start())
        assertFalse("second start while recording must be rejected", rec.start())
        assertEquals("recorder.start must be called exactly once", 1, fake.startCalls)
    }

    @Test fun successful_start_then_stop_returns_non_empty_clip() {
        val cache = freshCache()
        val fake = FakeRecorder(startResult = true, stopResult = true)
        val rec = AskRecorder(cache, fake)

        assertTrue(rec.start())
        val out = rec.stop()
        assertNotNull(out)
        assertTrue(out!!.exists())
        assertTrue(out.length() > 0)
    }
}
