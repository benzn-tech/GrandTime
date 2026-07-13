package com.benzn.grandtime.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.benzn.grandtime.net.SitesApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.siteDataStore: DataStore<Preferences> by preferencesDataStore(name = "site")

data class SelectedSite(val id: String, val slug: String, val name: String)

class SiteStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_SITE_ID = stringPreferencesKey("site_id")
        private val KEY_SITE_SLUG = stringPreferencesKey("site_slug")
        private val KEY_SITE_NAME = stringPreferencesKey("site_name")
        private val KEY_SITE_LIST_JSON = stringPreferencesKey("site_list_json")

        private fun encodeSiteList(sites: List<SitesApiClient.SiteOption>): String {
            val arr = JSONArray()
            sites.forEach { site ->
                arr.put(
                    JSONObject().apply {
                        put("id", site.id)
                        put("slug", site.slug)
                        put("name", site.name)
                    },
                )
            }
            return arr.toString()
        }

        private fun decodeSiteList(json: String?): List<SitesApiClient.SiteOption> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    SitesApiClient.SiteOption(
                        id = id,
                        slug = o.optString("slug"),
                        name = o.optString("name"),
                    )
                }
            }.getOrElse { emptyList() }
        }
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

    /** 工地列表缓存(与单选 site 分开存)——冷开 SitePickerDialog 时从磁盘秒读,见该文件注释。 */
    val siteList: Flow<List<SitesApiClient.SiteOption>> = dataStore.data.map { prefs ->
        decodeSiteList(prefs[KEY_SITE_LIST_JSON])
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

    suspend fun setSiteList(sites: List<SitesApiClient.SiteOption>) {
        dataStore.edit { it[KEY_SITE_LIST_JSON] = encodeSiteList(sites) }
    }
}
