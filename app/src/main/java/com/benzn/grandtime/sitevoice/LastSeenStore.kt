package com.benzn.grandtime.sitevoice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore(name = "site_voice")

/**
 * Persists the ISO timestamp of the most-recent Site-voice message this device has played, so a
 * (re)connect can backfill only what was missed while disconnected. Mirrors SiteStore.
 */
class LastSeenStore(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val KEY_LAST_SEEN = stringPreferencesKey("last_seen_ts")
    }

    val lastSeen: Flow<String?> = dataStore.data.map { it[KEY_LAST_SEEN] }

    suspend fun set(iso: String) {
        dataStore.edit { it[KEY_LAST_SEEN] = iso }
    }
}
