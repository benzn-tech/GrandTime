package com.benzn.grandtime.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class VideoQuality(val label: String) { P1080("1080P"), P720("720P"), P480("480P") }

enum class PhotoQuality(val label: String) { HIGH("High"), STANDARD("Standard") }

enum class PhotoResolution(val label: String) { MAX("Max (5MP)"), HIGH("High (3MP)"), STD("Standard (1MP)") }

enum class AspectRatio(val label: String) { RATIO_4_3("4:3"), RATIO_16_9("16:9") }

/** 录制参数:本子项目仅持久化;SP3 采集读取生效。上传固定实时,无开关(产品决定)。 */
data class RecordingSettings(
    val videoQuality: VideoQuality = VideoQuality.P1080,
    val segmentMinutes: Int = 5,
    val photoQuality: PhotoQuality = PhotoQuality.HIGH,
    val photoResolution: PhotoResolution = PhotoResolution.MAX,
    val screenOffMinutes: Int = 3,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val watermarkEnabled: Boolean = true,
    val videoUploadWifiOnly: Boolean = true,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        val SEGMENT_OPTIONS = listOf(1, 3, 5, 10)
        val SCREEN_OFF_OPTIONS = listOf(1, 3, 5, 0)
        private val KEY_VIDEO_QUALITY = stringPreferencesKey("video_quality")
        private val KEY_SEGMENT_MINUTES = intPreferencesKey("video_segment_minutes")
        private val KEY_PHOTO_QUALITY = stringPreferencesKey("photo_quality")
        private val KEY_PHOTO_RESOLUTION = stringPreferencesKey("photo_resolution")
        private val KEY_SCREEN_OFF_MINUTES = intPreferencesKey("screen_off_minutes")
        private val KEY_ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        private val KEY_WATERMARK = booleanPreferencesKey("watermark_enabled")
        private val KEY_VIDEO_UPLOAD_WIFI_ONLY = booleanPreferencesKey("video_upload_wifi_only")
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
            photoResolution = prefs[KEY_PHOTO_RESOLUTION]
                ?.let { name -> PhotoResolution.entries.firstOrNull { it.name == name } }
                ?: PhotoResolution.MAX,
            screenOffMinutes = prefs[KEY_SCREEN_OFF_MINUTES]?.takeIf { it in SCREEN_OFF_OPTIONS } ?: 3,
            aspectRatio = prefs[KEY_ASPECT_RATIO]
                ?.let { name -> AspectRatio.entries.firstOrNull { it.name == name } }
                ?: AspectRatio.RATIO_4_3,
            watermarkEnabled = prefs[KEY_WATERMARK] ?: true,
            videoUploadWifiOnly = prefs[KEY_VIDEO_UPLOAD_WIFI_ONLY] ?: true,
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

    suspend fun setPhotoResolution(value: PhotoResolution) {
        dataStore.edit { it[KEY_PHOTO_RESOLUTION] = value.name }
    }

    suspend fun setScreenOffMinutes(value: Int) {
        require(value in SCREEN_OFF_OPTIONS) { "screen off minutes must be one of $SCREEN_OFF_OPTIONS" }
        dataStore.edit { it[KEY_SCREEN_OFF_MINUTES] = value }
    }

    suspend fun setAspectRatio(value: AspectRatio) {
        dataStore.edit { it[KEY_ASPECT_RATIO] = value.name }
    }

    suspend fun setWatermarkEnabled(value: Boolean) {
        dataStore.edit { it[KEY_WATERMARK] = value }
    }

    suspend fun setVideoUploadWifiOnly(value: Boolean) {
        dataStore.edit { it[KEY_VIDEO_UPLOAD_WIFI_ONLY] = value }
    }
}
