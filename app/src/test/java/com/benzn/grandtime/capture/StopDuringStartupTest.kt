package com.benzn.grandtime.capture

import com.benzn.grandtime.keymap.KeyAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Announcement (~1.4s) plus the Camera2 session configuration, in round numbers. */
private const val STARTUP_MILLIS = 2_000L

/**
 * The two halves of the video path that the operator's key press falls between.
 *
 * [FakePipeline] keeps the one behaviour of Camera2Pipeline that matters here: a segment becomes
 * live only at the END of a slow start, and stopSegment does nothing at all when there is none —
 * the early `val rec = segment ?: return` that swallowed the press.
 */
private class FakePipeline(private val scope: CoroutineScope) {
    var isRecording = false
        private set
    private var onFinalized: (() -> Unit)? = null

    suspend fun startSegment(cb: () -> Unit) {
        delay(STARTUP_MILLIS)
        isRecording = true
        onFinalized = cb
    }

    fun stopSegment() {
        if (!isRecording) return
        isRecording = false
        val cb = onFinalized
        onFinalized = null
        scope.launch { cb?.invoke() }  // the real one hands this to a teardown thread
    }
}

/** CaptureManager's dispatch shape, with the serialization as a switch so the defect and the fix
 *  can be asserted against the same machine. */
private class Harness(private val scope: CoroutineScope, private val serialize: Boolean) {
    val core = CaptureCore(clock = { 0L }, newId = { "session" })
    val pipeline = FakePipeline(scope)
    private val lock = Mutex()
    private var pendingStopReason = StopReason.ROLLOVER

    fun press(action: KeyAction) = scope.launch { guarded { execute(core.onAction(action)) } }

    private suspend fun guarded(block: suspend () -> Unit) =
        if (serialize) lock.withLock { block() } else block()

    private suspend fun execute(commands: List<CaptureCommand>) {
        for (cmd in commands) when (cmd) {
            is CaptureCommand.StartVideoSegment ->
                pipeline.startSegment { scope.launch { guarded { finalize() } } }
            is CaptureCommand.StopVideo -> {
                pendingStopReason = cmd.reason
                pipeline.stopSegment()
            }
            else -> Unit
        }
    }

    private suspend fun finalize() {
        val reason = pendingStopReason
        pendingStopReason = StopReason.ROLLOVER
        execute(core.onVideoFinalized(reason))
    }
}

/**
 * A stop pressed while the start is still running must not vanish.
 *
 * CaptureCore commits to RecordingVideo the instant the start key is read, but the recorder behind
 * that decision takes about two seconds to exist: the spoken announcement is awaited, then the
 * camera session is configured. Neither START_STOP_VIDEO nor END_VIDEO changes the state itself
 * while recording — both wait for the onFinalized callback — so a stop that reaches a pipeline with
 * no live segment is not merely early, it is GONE, and the machine sits in RecordingVideo with the
 * key dead and the recording running.
 *
 * That is what the operator reports as "pause takes forever" and "holding it doesn't stop".
 */
class StopDuringStartupTest {

    @Test
    fun `unserialized, a pause pressed mid-startup is swallowed`() = runTest {
        val h = Harness(this, serialize = false)
        h.press(KeyAction.START_STOP_VIDEO)
        advanceTimeBy(500)  // the camera is still opening
        h.press(KeyAction.START_STOP_VIDEO)
        advanceUntilIdle()

        assertTrue("the defect: still recording after a pause press", h.core.state is CaptureState.RecordingVideo)
        assertTrue("and the segment the press meant to stop started anyway", h.pipeline.isRecording)
    }

    @Test
    fun `serialized, that pause lands`() = runTest {
        val h = Harness(this, serialize = true)
        h.press(KeyAction.START_STOP_VIDEO)
        advanceTimeBy(500)
        h.press(KeyAction.START_STOP_VIDEO)
        advanceUntilIdle()

        assertTrue("the press must apply after the start, not fall through it", h.core.state is CaptureState.PausedVideo)
        assertEquals(false, h.pipeline.isRecording)
    }

    @Test
    fun `unserialized, a long press mid-startup does not end the recording`() = runTest {
        val h = Harness(this, serialize = false)
        h.press(KeyAction.START_STOP_VIDEO)
        advanceTimeBy(1_000)  // LONG fires one second after key-down — inside the startup window
        h.press(KeyAction.END_VIDEO)
        advanceUntilIdle()

        assertTrue(h.core.state is CaptureState.RecordingVideo)
        assertTrue(h.pipeline.isRecording)
    }

    @Test
    fun `serialized, a long press mid-startup ends the recording`() = runTest {
        val h = Harness(this, serialize = true)
        h.press(KeyAction.START_STOP_VIDEO)
        advanceTimeBy(1_000)
        h.press(KeyAction.END_VIDEO)
        advanceUntilIdle()

        assertEquals(CaptureState.Idle, h.core.state)
        assertEquals(false, h.pipeline.isRecording)
    }

    @Test
    fun `a stop after the start has settled works either way`() = runTest {
        // The case that always worked, kept so a fix that only ever stops things cannot pass.
        val h = Harness(this, serialize = true)
        h.press(KeyAction.START_STOP_VIDEO)
        advanceUntilIdle()
        assertTrue(h.pipeline.isRecording)

        h.press(KeyAction.START_STOP_VIDEO)
        advanceUntilIdle()
        assertTrue(h.core.state is CaptureState.PausedVideo)
    }
}

/**
 * The model above proves the shape; these pin it to the file that has to keep it.
 *
 * Source-level for the same reason [AnnounceBeforeRecordTest] is: Camera2 and MediaPlayer do not
 * exist on the JVM, so CaptureManager cannot be built here. What an edit could otherwise undo with
 * no test failing is the lock on the paths that open and close a session.
 */
class CaptureLifecycleSerializationTest {

    private val manager = File(
        "src/main/java/com/benzn/grandtime/capture/CaptureManager.kt"
    ).readText()

    private fun body(signature: String, chars: Int = 900): String {
        val start = manager.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        return manager.substring(start, minOf(start + chars, manager.length))
    }

    @Test
    fun `key actions that start or stop a session are serialized`() {
        val b = body("fun handle(action: KeyAction)")
        assertTrue("handle must take the lock", b.contains("sessionLock.withLock"))
        assertTrue("the lock must cover the lifecycle actions", b.contains("action in lifecycleActions"))
    }

    @Test
    fun `the lifecycle set is the four session actions`() {
        val b = body("private val lifecycleActions", chars = 200)
        for (action in listOf("START_STOP_VIDEO", "END_VIDEO", "START_STOP_AUDIO", "END_AUDIO")) {
            assertTrue("$action must be serialized", b.contains(action))
        }
        // A photo, the torch and the volume must NOT be: they never start or stop a session, and
        // holding a 3s photo capture would make the stop key unresponsive all over again.
        assertEquals(false, b.contains("TAKE_PHOTO"))
        assertEquals(false, b.contains("TOGGLE_TORCH"))
    }

    @Test
    fun `the segment rollover takes the same lock`() {
        // A rollover opens the next segment — the same window, once every segment rather than once
        // per session, which is the version that would survive a fix aimed only at the first start.
        val b = body("private fun startSegmentTimer")
        assertTrue("the rollover timer must be serialized", b.contains("sessionLock.withLock"))
    }

    @Test
    fun `the finalize callback takes the same lock`() {
        val b = body("private suspend fun startVideoSegment", chars = 3500)
        val cb = b.indexOf("{ error, message ->")
        assertTrue("onFinalized callback not found", cb >= 0)
        assertTrue(
            "the finalize callback must be serialized — it opens the next segment on a rollover",
            b.indexOf("sessionLock.withLock", cb) >= 0,
        )
    }

    @Test
    fun `execute does not take the lock`() {
        // execute is re-entrant: startVideoSegment and startAudio call it back on their failure
        // paths. Taking a non-reentrant Mutex in there is a deadlock on exactly the path that has
        // to still work — the camera being unavailable.
        val b = body("private suspend fun execute", chars = 2_200)
        assertEquals(false, b.contains("sessionLock.withLock"))
    }

    @Test
    fun `a stop with nothing to stop is reported`() {
        val b = body("is CaptureCommand.StopVideo", chars = 1200)
        assertTrue(
            "a stop that cannot be delivered must show up in the probe log, not look like a dead key",
            b.contains("!pipeline.isRecording") && b.contains("probe("),
        )
    }
}
