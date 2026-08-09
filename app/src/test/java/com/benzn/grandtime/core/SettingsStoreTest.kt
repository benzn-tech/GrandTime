package com.benzn.grandtime.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newStore(): Pair<SettingsStore, DataStore<Preferences>> {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
        ) { File(tmp.root, "settings.preferences_pb") }
        return SettingsStore(ds) to ds
    }

    @Test
    fun `defaults when empty`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertEquals(
            RecordingSettings(VideoQuality.P1080, 30, PhotoQuality.HIGH),
            store.settings.first(),
        )
    }

    @Test
    fun `write and read back`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        store.setVideoQuality(VideoQuality.P480)
        store.setSegmentSeconds(60)
        store.setPhotoQuality(PhotoQuality.STANDARD)
        assertEquals(
            RecordingSettings(VideoQuality.P480, 60, PhotoQuality.STANDARD),
            store.settings.first(),
        )
    }

    @Test
    fun `invalid stored values fall back to defaults`() = runTest(UnconfinedTestDispatcher()) {
        val (store, ds) = newStore()
        ds.edit {
            it[stringPreferencesKey("video_quality")] = "BOGUS"
            it[intPreferencesKey("video_segment_seconds")] = 42
            it[stringPreferencesKey("photo_quality")] = "ULTRA"
        }
        assertEquals(RecordingSettings(), store.settings.first())
    }

    @Test
    fun `setSegmentSeconds rejects values outside options`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setSegmentSeconds(45) }
        }
    }

    @Test
    fun `photo resolution and screen off default and roundtrip`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertEquals(PhotoResolution.MAX, store.settings.first().photoResolution)
        assertEquals(3, store.settings.first().screenOffMinutes)
        store.setPhotoResolution(PhotoResolution.STD)
        store.setScreenOffMinutes(0)
        assertEquals(PhotoResolution.STD, store.settings.first().photoResolution)
        assertEquals(0, store.settings.first().screenOffMinutes)
    }

    @Test
    fun `invalid photo resolution and screen off fall back`() = runTest(UnconfinedTestDispatcher()) {
        val (store, ds) = newStore()
        ds.edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("photo_resolution")] = "BOGUS"
            it[androidx.datastore.preferences.core.intPreferencesKey("screen_off_minutes")] = 42
        }
        assertEquals(PhotoResolution.MAX, store.settings.first().photoResolution)
        assertEquals(3, store.settings.first().screenOffMinutes)
    }

    @Test
    fun `setScreenOffMinutes rejects values outside options`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setScreenOffMinutes(2) }
        }
    }

    @Test
    fun `aspect ratio defaults to 4-3`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertEquals(AspectRatio.RATIO_4_3, store.settings.first().aspectRatio)
    }

    @Test
    fun `aspect ratio roundtrips`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        store.setAspectRatio(AspectRatio.RATIO_16_9)
        assertEquals(AspectRatio.RATIO_16_9, store.settings.first().aspectRatio)
    }

    @Test
    fun `unknown stored aspect ratio falls back to 4-3`() = runTest(UnconfinedTestDispatcher()) {
        val (store, ds) = newStore()
        ds.edit { it[stringPreferencesKey("aspect_ratio")] = "RATIO_99_9" }
        assertEquals(AspectRatio.RATIO_4_3, store.settings.first().aspectRatio)
    }

    @Test
    fun `watermark defaults to enabled`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertEquals(true, store.settings.first().watermarkEnabled)
    }

    @Test
    fun `watermark toggle roundtrips`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        store.setWatermarkEnabled(false)
        assertEquals(false, store.settings.first().watermarkEnabled)
    }

    @Test
    fun `video upload wifi only defaults to DISABLED`() = runTest(UnconfinedTestDispatcher()) {
        // It defaulted to true, and on a site with no WiFi that is not a slow
        // upload -- it is no upload at all. WorkManager's UNMETERED constraint is
        // never satisfied, so the chunks sit in the queue indefinitely, showing
        // as neither progress nor failure. The customer records, sees nothing go
        // wrong, and nothing arrives.
        //
        // A recording chunk is ~30 seconds, not a whole video file, so the
        // metered-network caution the default was protecting is worth far less
        // than the recording it was silently discarding.
        val (store, _) = newStore()
        assertEquals(false, store.settings.first().videoUploadWifiOnly)
    }

    @Test
    fun `the two wifi-only defaults agree`() = runTest(UnconfinedTestDispatcher()) {
        // The setting has TWO defaults -- the data class parameter and the read
        // path's `?:` fallback -- written in different places. Changing one and
        // not the other gives a build where a freshly-installed device and a
        // directly-constructed RecordingSettings disagree about whether video
        // uploads need WiFi, which shows up as "it works on my phone".
        val (store, _) = newStore()
        assertEquals(RecordingSettings().videoUploadWifiOnly,
                     store.settings.first().videoUploadWifiOnly)
    }

    @Test
    fun `video upload wifi only toggle roundtrips`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        store.setVideoUploadWifiOnly(false)
        assertEquals(false, store.settings.first().videoUploadWifiOnly)
    }
}
