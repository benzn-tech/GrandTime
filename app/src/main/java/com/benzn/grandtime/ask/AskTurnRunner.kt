package com.benzn.grandtime.ask

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The three server calls of one two-phase turn. Tokens are the implementation's business. */
internal interface TurnApi {
    suspend fun start(clip: ByteArray): AskApiClient.StartResult
    suspend fun result(turnId: String, since: String): AskApiClient.TurnStatus
    /** Fire-and-forget. */
    fun cancel(turnId: String)
}

/**
 * Everything the operator can hear between releasing the key and the answer. The `play*`
 * functions return when the sound has finished (or a stuck-player guard gave up).
 */
internal interface TurnAudio {
    /** Bundled "checking your records" line. */
    suspend fun playFiller()
    suspend fun playRestatement(wav: ByteArray)
    /** Bundled "searching online now" line. */
    suspend fun playOnlineCue()
    /** Silence everything, pending or playing. */
    fun stopAll()
}

internal sealed interface TurnOutcome {
    class Answer(val wav: ByteArray) : TurnOutcome
    /** The backend has no two-phase routes: run the old single-call ask. */
    class NoRoute(val fillerPlayed: Boolean) : TurnOutcome
    data class Failed(val reason: String) : TurnOutcome
    data object TimedOut : TurnOutcome
}

/**
 * One two-phase turn after the key was released (spec section 9; the earcon belongs to the
 * release itself and plays before this runs): restatement window,
 * filler fallback, polling, online cue, answer queued behind the current cue, overall cap.
 *
 * Pure coroutine logic over [TurnApi] and [TurnAudio] so it runs on virtual time in unit tests.
 * Cancelling [run] (an interrupt) stops all audio and, once a turn id is known, sends a
 * best-effort cancel to the server.
 */
internal class AskTurnRunner(
    private val api: TurnApi,
    private val audio: TurnAudio,
    private val onTurnId: (String) -> Unit = {},
    private val restateWindowMs: Long = RESTATE_WINDOW_MS,
    private val capMs: Long = TURN_CAP_MS,
    /** Monotonic millis; the window and the cap are measured from the release, not from here. */
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    suspend fun run(clip: ByteArray, releasedAtMs: Long): TurnOutcome = coroutineScope {
        // Time already spent since the key came up (recording flush, token refresh) counts
        // against both budgets.
        val sinceRelease = (now() - releasedAtMs).coerceAtLeast(0)
        val windowMs = (restateWindowMs - sinceRelease).coerceAtLeast(0)
        var fillerPlayed = false
        val scope = this
        var turnId: String? = null
        var answered = false
        var cues: Job? = null

        // Cues play one after another: each waits for the previous to finish, so nothing is cut
        // off mid-sentence and the answer can wait on the last one.
        fun queueCue(block: suspend () -> Unit) {
            val previous = cues
            cues = scope.launch {
                previous?.join()
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // a cue that fails to play must not take the turn down with it
                }
            }
        }

        try {
            val outcome = withTimeoutOrNull((capMs - sinceRelease).coerceAtLeast(0)) {
                val startCall = async { api.start(clip) }
                val early = withTimeoutOrNull(windowMs) { startCall.await() }
                val started = (early ?: run {
                    // The window expired first: the bundled line covers the wait, and a
                    // restatement that turns up after this is dropped.
                    fillerPlayed = true
                    queueCue { audio.playFiller() }
                    startCall.await()
                })

                when (started) {
                    is AskApiClient.StartResult.NoRoute -> return@withTimeoutOrNull TurnOutcome.NoRoute(fillerPlayed)
                    is AskApiClient.StartResult.StageError -> return@withTimeoutOrNull TurnOutcome.Failed(started.message)
                    is AskApiClient.StartResult.AuthExpired -> return@withTimeoutOrNull TurnOutcome.Failed("auth expired")
                    is AskApiClient.StartResult.Error -> return@withTimeoutOrNull TurnOutcome.Failed(started.message)
                    is AskApiClient.StartResult.Started -> Unit
                }
                turnId = started.turnId
                onTurnId(started.turnId)
                if (early != null) {
                    val wav = started.restatementWav
                    if (wav != null) queueCue { audio.playRestatement(wav) } else { fillerPlayed = true; queueCue { audio.playFiller() } }
                }

                var since = "pending"
                var onlineQueued = false
                var networkErrors = 0
                while (true) {
                    when (val status = api.result(started.turnId, since)) {
                        AskApiClient.TurnStatus.Pending -> networkErrors = 0
                        AskApiClient.TurnStatus.NetworkError -> {
                            // The poll broke, not necessarily the turn. Bounded, and still
                            // inside the overall cap.
                            if (++networkErrors > MAX_POLL_RETRIES) return@withTimeoutOrNull TurnOutcome.Failed("network")
                            delay(POLL_RETRY_DELAY_MS)
                        }
                        AskApiClient.TurnStatus.SearchingWeb -> {
                            networkErrors = 0
                            since = "searching_web"
                            // The restatement already said it when the question asked for the web.
                            if (!started.webIntent && !onlineQueued) {
                                onlineQueued = true
                                queueCue { audio.playOnlineCue() }
                            }
                        }
                        is AskApiClient.TurnStatus.Done -> {
                            cues?.join()  // the answer waits for whatever is still being said
                            answered = true
                            return@withTimeoutOrNull TurnOutcome.Answer(status.wav)
                        }
                        AskApiClient.TurnStatus.Cancelled -> return@withTimeoutOrNull TurnOutcome.Failed("cancelled")
                        AskApiClient.TurnStatus.NotFound -> return@withTimeoutOrNull TurnOutcome.Failed("not found")
                        AskApiClient.TurnStatus.AuthExpired -> return@withTimeoutOrNull TurnOutcome.Failed("auth expired")
                        is AskApiClient.TurnStatus.Failed -> return@withTimeoutOrNull TurnOutcome.Failed(status.message)
                    }
                }
                throw IllegalStateException("poll loop exited")
            } ?: TurnOutcome.TimedOut

            if (outcome !is TurnOutcome.Answer) {
                cues?.cancel()
                audio.stopAll()
                if (outcome is TurnOutcome.TimedOut) turnId?.let(api::cancel)
            }
            outcome
        } catch (e: CancellationException) {
            // Interrupt (or shutdown): abandon the turn on the server too, and go quiet.
            cues?.cancel()
            audio.stopAll()
            if (!answered) turnId?.let(api::cancel)
            throw e
        }
    }

    companion object {
        /** To be tuned on TEST from the server's start_total (spec section 9). */
        const val RESTATE_WINDOW_MS = 4_500L
        /** From release; at the cap the turn is cancelled and the error cue plays. */
        const val TURN_CAP_MS = 60_000L
        /** Retries after a broken poll: three consecutive errors fail the turn. */
        const val MAX_POLL_RETRIES = 2
        const val POLL_RETRY_DELAY_MS = 1_000L
    }
}
