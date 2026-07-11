package com.benzn.grandtime.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class VideoQuality(val label: String) { P1080("1080P"), P720("720P"), P480("480P") }

enum class PhotoQuality(val label: String) { HIGH("High"), STANDARD("Standard") }

/** 录制参数:本子项目仅持久化;SP3 采集读取生效。上传固定实时,无开关(产品决定)。 */
data class RecordingSettings(
    val videoQuality: VideoQuality = VideoQuality.P1080,
    val segmentMinutes: Int = 5,
    val photoQuality: PhotoQuality = PhotoQuality.HIGH,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        val SEGMENT_OPTIONS = listOf(1, 3, 5, 10)
        private val KEY_VIDEO_QUALITY = stringPreferencesKey("video_quality")
        private val KEY_SEGMENT_MINUTES = intPreferencesKey("video_segment_minutes")
        private val KEY_PHOTO_QUALITY = stringPreferencesKey("photo_quality")
    }

    val settings: Flow<RecordingSettings> = dataStore.data.map { prefs ->
        RecordingSettings(
            videoQuality = prefs[KEY_VIDEO_QUALITY]
                ?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } }
                ?: VideoQuality.P1080,
            segmentMinutes = prefs[KEY_SEGMENT_MINUTES]?.takeIf { it in SEGMENT_OPTIONS } ?: 5,
            photoQuality = prefs[KEY_PHOTO_QUALITY]
                ?.let { name -> PhotoQuality.entries.firstOrNull { it.name == name } }
                ?: PhotoQuality.HIGH,
        )
    }

    suspend fun setVideoQuality(value: VideoQuality) {
        dataStore.edit { it[KEY_VIDEO_QUALITY] = value.name }
    }

    suspend fun setSegmentMinutes(value: Int) {
        require(value in SEGMENT_OPTIONS) { "segment minutes must be one of $SEGMENT_OPTIONS" }
        dataStore.edit { it[KEY_SEGMENT_MINUTES] = value }
    }

    suspend fun setPhotoQuality(value: PhotoQuality) {
        dataStore.edit { it[KEY_PHOTO_QUALITY] = value.name }
    }
}
