package com.benzn.grandtime.sitevoice

/**
 * Decides which Site-voice connection events reach the probe log. Pure and JVM-testable, in the
 * same spirit as [WsBackoff]: the WS shell owns the socket, this owns what gets said about it.
 *
 * ## Why this exists
 *
 * API Gateway closes an idle WebSocket at ten minutes and OkHttp's 20s ping does not prevent it, so
 * a perfectly healthy device reconnects roughly every fourteen minutes, all day. Announcing each
 * cycle wrote ~76 lines a day into a 200-entry ring ([com.benzn.grandtime.core.AppState.PROBE_LIMIT]).
 * The reconnects were never the fault; evicting the record of everything that WAS a fault is.
 *
 * ## Why "log on state change" is not the rule
 *
 * It is the obvious rule and it does not work: a routine cycle changes state twice (up -> down ->
 * up), so keying on the flip keeps all 76 lines. The distinction that matters is not whether the
 * state changed, it is whether the FIRST retry got back in — a cycle recovers on that retry, an
 * outage does not. So [onDropped] says nothing, and a drop only becomes a line once a retry has
 * already failed ([onRetryFailed]).
 *
 * ## Why it is never fully silent
 *
 * "Nothing was logged" and "the client was never running" must not look the same — the same reason
 * the upload guards log on the way through. Suppressed cycles are counted, and every
 * [HEARTBEAT_CYCLES]th one prints a line that says how many were suppressed and that the socket is
 * still up. Quiet always has a stated reason.
 */
class WsLogGate(private val heartbeatCycles: Int = HEARTBEAT_CYCLES) {

    /** What the log currently SAYS. Starts down: nothing has claimed a connection yet. */
    private var loggedDown = true
    private var suppressed = 0

    /** The socket opened. Returns the line to log, or null when this merely closed a routine cycle. */
    fun onConnected(): String? {
        if (loggedDown) {
            loggedDown = false
            return "site-voice: connected"
        }
        suppressed++
        if (suppressed % heartbeatCycles == 0) {
            return "site-voice: $suppressed reconnect cycles, still up (idle-timeout churn)"
        }
        return null
    }

    /**
     * The socket dropped and a reconnect is coming. Always silent — at this instant a routine cycle
     * and an outage are indistinguishable, and guessing is what produced the noise.
     */
    fun onDropped(): String? = null

    /**
     * A reconnect attempt failed. [attempt] is the 1-based retry number about to be made; from
     * [ANNOUNCE_DOWN_AFTER_ATTEMPTS] on, the retry that would have closed a routine cycle has
     * already come and gone, so this is an outage. Idempotent — a long outage still gets one line.
     */
    fun onRetryFailed(attempt: Int, why: String?): String? {
        if (attempt < ANNOUNCE_DOWN_AFTER_ATTEMPTS || loggedDown) return null
        loggedDown = true
        return "site-voice: down (${why ?: "unknown"})"
    }

    /** The client stopped. The next start must be free to announce its connect. */
    fun onStopped() {
        loggedDown = true
        suppressed = 0
    }

    /** Test/diagnostic view of how many cycles have gone unprinted. */
    fun suppressedCount(): Int = suppressed

    companion object {
        /** Retries before a drop is called an outage. The routine idle-timeout cycle recovers on
         *  retry 1, so only retry 2 and beyond mean something is actually wrong. */
        const val ANNOUNCE_DOWN_AFTER_ATTEMPTS = 2

        /** Suppressed cycles between "still up" lines. At ~14 minutes a cycle this is roughly
         *  daily: often enough that silence is never mistaken for a dead client, rare enough that
         *  it cannot crowd the probe ring. */
        const val HEARTBEAT_CYCLES = 100
    }
}
