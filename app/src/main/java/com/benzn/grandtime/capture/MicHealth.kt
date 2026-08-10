package com.benzn.grandtime.capture

/**
 * What this device has seen of its own microphone, cumulatively.
 *
 * Cumulative, and never reset. `POST /api/org/device/status` writes vitals as a
 * last-write-wins `UPDATE` on `devices` — a gauge. If these were per-session values, the next
 * clean report would erase the fault from the ledger before anyone looked, and the whole
 * uplink would be worse than useless: it would read as proof of health.
 *
 * Monotone counters plus a max cannot be erased that way. The cost is that the numbers answer
 * "has this device ever" rather than "is it happening now" — and it is the local log line, not
 * this, that is the timely artifact. The uplink is a six-hour periodic worker.
 */
data class MicHealth(
    val silentSecondsS: Int = 0,
    val longestSilentRunS: Int = 0,
    val silentRunsWithMicBorrowed: Int = 0,
    /** Lowest per-session peak amplitude across sessions that actually recorded something.
     *  Null until one has. A microphone dying to a DC offset never reaches exact zero, so the
     *  zero counters stay clean while this collapses. */
    val lowestSessionPeak: Int? = null,
    val sessionsRecorded: Int = 0,
)

/** Pure fold, so the "a clean session cannot erase a dirty one" rule is testable without
 *  DataStore, a device, or a capture. */
object MicHealthFold {
    fun fold(stored: MicHealth, session: MicSilenceSnapshot): MicHealth {
        // A session that never read a buffer says nothing about the microphone. Folding it
        // would drive lowestSessionPeak to 0 and manufacture a fault out of an idle app.
        if (session.recordedSecondsS <= 0) return stored
        return MicHealth(
            silentSecondsS = stored.silentSecondsS + session.silentSecondsS,
            longestSilentRunS = maxOf(stored.longestSilentRunS, session.longestSilentRunS),
            silentRunsWithMicBorrowed =
                stored.silentRunsWithMicBorrowed + session.silentRunsWithMicBorrowed,
            lowestSessionPeak = stored.lowestSessionPeak
                ?.let { minOf(it, session.peakAmplitude) }
                ?: session.peakAmplitude,
            sessionsRecorded = stored.sessionsRecorded + 1,
        )
    }
}
