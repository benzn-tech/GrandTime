package com.benzn.grandtime.capture

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where a finished session's microphone counters go. An interface so [CaptureManager] stays
 *  JVM-testable — the unit tests construct one without a DataStore, exactly as they do for the
 *  upload enqueuer. */
interface MicHealthSink {
    suspend fun record(session: MicSilenceSnapshot)

    object NoOp : MicHealthSink {
        override suspend fun record(session: MicSilenceSnapshot) {}
    }
}

/**
 * Persists [MicHealth] across process death.
 *
 * Needed because the reader and the writer never share a process reliably: the counters are
 * produced by the capture service and read by `DeviceStatusWorker`, a six-hourly WorkManager
 * job that routinely runs in a cold process long after the capture is over. Keeping them in
 * memory would mean the uplink almost always reports zeros.
 */
class MicHealthStore(private val dataStore: DataStore<Preferences>) : MicHealthSink {

    suspend fun read(): MicHealth = dataStore.data.map { p ->
        MicHealth(
            silentSecondsS = p[KEY_SILENT_SECONDS] ?: 0,
            longestSilentRunS = p[KEY_LONGEST_RUN] ?: 0,
            silentRunsWithMicBorrowed = p[KEY_BORROWED_RUNS] ?: 0,
            lowestSessionPeak = p[KEY_LOWEST_PEAK],
            sessionsRecorded = p[KEY_SESSIONS] ?: 0,
        )
    }.first()

    /** Folds one finished session in. Read-modify-write inside [edit], which DataStore
     *  serializes, so two sessions finishing close together cannot lose one of them. */
    override suspend fun record(session: MicSilenceSnapshot) {
        dataStore.edit { p ->
            val folded = MicHealthFold.fold(
                MicHealth(
                    silentSecondsS = p[KEY_SILENT_SECONDS] ?: 0,
                    longestSilentRunS = p[KEY_LONGEST_RUN] ?: 0,
                    silentRunsWithMicBorrowed = p[KEY_BORROWED_RUNS] ?: 0,
                    lowestSessionPeak = p[KEY_LOWEST_PEAK],
                    sessionsRecorded = p[KEY_SESSIONS] ?: 0,
                ),
                session,
            )
            p[KEY_SILENT_SECONDS] = folded.silentSecondsS
            p[KEY_LONGEST_RUN] = folded.longestSilentRunS
            p[KEY_BORROWED_RUNS] = folded.silentRunsWithMicBorrowed
            folded.lowestSessionPeak?.let { p[KEY_LOWEST_PEAK] = it }
            p[KEY_SESSIONS] = folded.sessionsRecorded
        }
    }

    private companion object {
        val KEY_SILENT_SECONDS = intPreferencesKey("mic_silent_seconds")
        val KEY_LONGEST_RUN = intPreferencesKey("mic_longest_silent_run")
        val KEY_BORROWED_RUNS = intPreferencesKey("mic_silent_runs_borrowed")
        val KEY_LOWEST_PEAK = intPreferencesKey("mic_lowest_session_peak")
        val KEY_SESSIONS = intPreferencesKey("mic_sessions_recorded")
    }
}
