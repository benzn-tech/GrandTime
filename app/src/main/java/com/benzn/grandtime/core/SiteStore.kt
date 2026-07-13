package com.benzn.grandtime.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.siteDataStore: DataStore<Preferences> by preferencesDataStore(name = "site")

data class SelectedSite(val id: String, val slug: String, val name: String)

class SiteStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_SITE_ID = stringPreferencesKey("site_id")
        private val KEY_SITE_SLUG = stringPreferencesKey("site_slug")
        private val KEY_SITE_NAME = stringPreferencesKey("site_name")
    }

    val site: Flow<SelectedSite?> = dataStore.data.map { prefs ->
        val id = prefs[KEY_SITE_ID]
        if (id.isNullOrBlank()) {
            null
        } else {
            SelectedSite(
                id = id,
                slug = prefs[KEY_SITE_SLUG].orEmpty(),
                name = prefs[KEY_SITE_NAME].orEmpty(),
            )
        }
    }

    suspend fun set(s: SelectedSite?) {
        dataStore.edit {
            if (s == null) {
                it.remove(KEY_SITE_ID)
                it.remove(KEY_SITE_SLUG)
                it.remove(KEY_SITE_NAME)
            } else {
                it[KEY_SITE_ID] = s.id
                it[KEY_SITE_SLUG] = s.slug
                it[KEY_SITE_NAME] = s.name
            }
        }
    }
}
