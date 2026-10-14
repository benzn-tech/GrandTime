package com.benzn.grandtime.ask

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Started = AskApiClient.StartResult.Started
private typealias Status = AskApiClient.TurnStatus

private class FakeTurnApi(
    private val startDelayMs: Long = 0,
    private val startResult: AskApiClient.StartResult,
    /** Each entry: (delay before it is returned, status). The last one repeats. */
    private val results: List<Pair<Long, Status>>,
) : TurnApi {
    val sinceSeen = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var resultCalls = 0
    override suspend fun start(clip: ByteArray): AskApiClient.StartResult {
        delay(startDelayMs); return startResult
    }
    override suspend fun result(turnId: String, since: String): Status {
        sinceSeen += since
        val (d, s) = results[minOf(resultCalls++, results.size - 1)]
        delay(d)
        return s
    }
    override fun cancel(turnId: String) { cancelled += turnId }
}

private class FakeTurnAudio(private val scope: TestScope) : TurnAudio {
    /** "name@startMs" and "name-end@endMs" in order. */
    val log = mutableListOf<String>()
    var stopped = 0
    var cueMs = 2_000L
    private fun t() = scope.currentTime
    override fun earcon() { log += "earcon@${t()}" }
    private suspend fun cue(name: String) {
        log += "$name@${t()}"; delay(cueMs); log += "$name-end@${t()}"
    }
    override suspend fun playFiller() = cue("filler")
    override suspend fun playRestatement(wav: ByteArray) = cue("restatement")
    override suspend fun playOnlineCue() = cue("online")
    override fun stopAll() { stopped++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AskTurnRunnerTest {
    private val turn = "0123456789abcdef0123456789abcdef"
    private val clip = byteArrayOf(1)
    private val answer = byteArrayOf(7, 7)

    private fun started(web: Boolean = false, restatement: Boolean = true) =
        Started(turn, "q", web, if (restatement) byteArrayOf(5) else null)

    private fun done() = AskApiClient.TurnStatus.Done("q", "a", answer)

    private fun runner(api: TurnApi, audio: TurnAudio, onId: (String) -> Unit = {}) =
        AskTurnRunner(api, audio, onId)

    @Test fun earcon_plays_first_and_immediately() = runTest {
        val audio = FakeTurnAudio(this)
        runner(FakeTurnApi(startResult = started(), results = listOf(0L to done())), audio).run(clip)
        assertEquals("earcon@0", audio.log.first())
    }

    @Test fun restatement_inside_the_window_plays_and_the_filler_never_does() = runTest {
        val audio = FakeTurnAudio(this)
        val api = FakeTurnApi(startDelayMs = 3_000, startResult = started(), results = listOf(0L to done()))
        runner(api, audio).run(clip)
        assertTrue(audio.log.contains("restatement@3000"))
        assertTrue(audio.log.none { it.startsWith("filler") })
    }

    @Test fun window_expiring_first_plays_the_filler_and_drops_the_late_restatement() = runTest {
        val audio = FakeTurnAudio(this)
        val api = FakeTurnApi(startDelayMs = 6_000, startResult = started(), results = listOf(0L to done()))
        runner(api, audio).run(clip)
        assertTrue(audio.log.contains("filler@4500"))
        assertTrue("late restatement dropped", audio.log.none { it.startsWith("restatement") })
    }

    @Test fun a_null_restatement_plays_the_filler_now() = runTest {
        val audio = FakeTurnAudio(this)
        val api = FakeTurnApi(startDelayMs = 1_000, startResult = started(restatement = false), results = listOf(0L to done()))
        runner(api, audio).run(clip)
        assertTrue(audio.log.contains("filler@1000"))
    }

    @Test fun the_answer_waits_for_the_restatement_to_finish() = runTest {
        val audio = FakeTurnAudio(this)
        audio.cueMs = 5_000
        val api = FakeTurnApi(startResult = started(), results = listOf(0L to done()))
        val out = runner(api, audio).run(clip) as TurnOutcome.Answer
        assertEquals("restatement-end@5000", audio.log.last())
        assertEquals(5_000L, currentTime)
        assertTrue(answer.contentEquals(out.wav))
    }

    @Test fun searching_web_queues_the_online_cue_once_after_the_current_line() = runTest {
        val audio = FakeTurnAudio(this)
        audio.cueMs = 3_000
        val api = FakeTurnApi(
            startResult = started(web = false),
            results = listOf(1_000L to AskApiClient.TurnStatus.SearchingWeb, 1_000L to AskApiClient.TurnStatus.SearchingWeb, 0L to done()),
        )
        runner(api, audio).run(clip)
        // restatement 0..3000; searching_web seen at 1000 -> online waits until 3000
        assertEquals(1, audio.log.count { it.startsWith("online@") })
        assertTrue(audio.log.contains("online@3000"))
        assertEquals("online-end@6000", audio.log.last())
        assertEquals(listOf("pending", "searching_web", "searching_web"), api.sinceSeen)
    }

    @Test fun web_intent_suppresses_the_online_cue() = runTest {
        val audio = FakeTurnAudio(this)
        val api = FakeTurnApi(
            startResult = started(web = true),
            results = listOf(0L to AskApiClient.TurnStatus.SearchingWeb, 0L to done()),
        )
        runner(api, audio).run(clip)
        assertTrue(audio.log.none { it.startsWith("online") })
    }

    @Test fun pending_keeps_polling_with_the_same_since() = runTest {
        val api = FakeTurnApi(
            startResult = started(),
            results = listOf(0L to AskApiClient.TurnStatus.Pending, 0L to AskApiClient.TurnStatus.Pending, 0L to done()),
        )
        runner(api, FakeTurnAudio(this)).run(clip)
        assertEquals(listOf("pending", "pending", "pending"), api.sinceSeen)
    }

    @Test fun the_turn_id_is_reported() = runTest {
        var seen: String? = null
        runner(FakeTurnApi(startResult = started(), results = listOf(0L to done())), FakeTurnAudio(this)) { seen = it }.run(clip)
        assertEquals(turn, seen)
    }

    @Test fun no_route_is_reported_for_the_old_path() = runTest {
        val audio = FakeTurnAudio(this)
        val out = runner(FakeTurnApi(startResult = AskApiClient.StartResult.NoRoute, results = listOf()), audio).run(clip)
        assertEquals(TurnOutcome.NoRoute, out)
        assertEquals(1, audio.stopped)
    }

    @Test fun stage_error_and_failed_status_are_failures() = runTest {
        val a = runner(FakeTurnApi(startResult = AskApiClient.StartResult.StageError("Empty", ""), results = listOf()), FakeTurnAudio(this)).run(clip)
        assertTrue(a is TurnOutcome.Failed)
        val b = runner(FakeTurnApi(startResult = started(), results = listOf(0L to AskApiClient.TurnStatus.Failed("No answer"))), FakeTurnAudio(this)).run(clip)
        assertEquals(TurnOutcome.Failed("No answer"), b)
    }

    @Test fun the_60s_cap_times_out_silences_audio_and_cancels_the_turn() = runTest {
        val audio = FakeTurnAudio(this)
        val api = FakeTurnApi(startResult = started(), results = listOf(20_000L to AskApiClient.TurnStatus.Pending))
        val out = runner(api, audio).run(clip)
        assertEquals(TurnOutcome.TimedOut, out)
        assertEquals(60_000L, currentTime)
        assertEquals(listOf(turn), api.cancelled)
        assertEquals(1, audio.stopped)
    }

    @Test fun the_cap_before_start_returns_has_no_turn_to_cancel() = runTest {
        val api = FakeTurnApi(startDelayMs = 100_000, startResult = started(), results = listOf())
        val out = runner(api, FakeTurnAudio(this)).run(clip)
        assertEquals(TurnOutcome.TimedOut, out)
        assertTrue(api.cancelled.isEmpty())
    }

    @Test fun interrupt_mid_poll_cancels_the_job_stops_audio_and_cancels_the_turn() = runTest {
        val audio = FakeTurnAudio(this)
        val api = FakeTurnApi(startResult = started(), results = listOf(20_000L to AskApiClient.TurnStatus.Pending))
        val job = async(start = CoroutineStart.UNDISPATCHED) { runner(api, audio).run(clip) }
        delay(8_000)
        assertTrue(job.isActive)
        job.cancel()
        runCatching { job.await() }
        assertEquals(listOf(turn), api.cancelled)
        assertTrue(audio.stopped >= 1)
        assertTrue(job.isCancelled)
    }

    @Test fun interrupt_before_the_turn_id_is_known_sends_no_cancel() = runTest {
        val api = FakeTurnApi(startDelayMs = 2_000, startResult = started(), results = listOf())
        val audio = FakeTurnAudio(this)
        val job = async(start = CoroutineStart.UNDISPATCHED) { runner(api, audio).run(clip) }
        delay(1_000)
        job.cancel()
        runCatching { job.await() }
        assertTrue(api.cancelled.isEmpty())
        assertTrue(audio.stopped >= 1)
    }

    @Test fun a_cancelled_answer_after_done_is_not_cancelled_on_the_server() = runTest {
        // Done was delivered; an interrupt during the (instant) hand-off must not cancel a finished turn.
        val api = FakeTurnApi(startResult = started(), results = listOf(0L to done()))
        val out = runner(api, FakeTurnAudio(this)).run(clip)
        assertTrue(out is TurnOutcome.Answer)
        assertTrue(api.cancelled.isEmpty())
    }
}
