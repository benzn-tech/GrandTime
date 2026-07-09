package com.benzn.grandtime.keymap

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.PressType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.keymapDataStore: DataStore<Preferences> by preferencesDataStore(name = "keymap")

class KeyMapStore(private val dataStore: DataStore<Preferences>) {

    val overrides: Flow<Map<String, KeyAction>> = dataStore.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (prefKey, value) ->
            val action = (value as? String)
                ?.let { name -> KeyAction.entries.firstOrNull { it.name == name } }
            action?.let { prefKey.name to it }
        }.toMap()
    }

    suspend fun setOverride(key: HardKey, pressType: PressType, action: KeyAction) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(KeyMapping.overrideKeyOf(key, pressType))] = action.name
        }
    }

    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }
}
