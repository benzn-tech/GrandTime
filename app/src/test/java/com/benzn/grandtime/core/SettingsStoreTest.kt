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
            RecordingSettings(VideoQuality.P1080, 5, PhotoQuality.HIGH),
            store.settings.first(),
        )
    }

    @Test
    fun `write and read back`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        store.setVideoQuality(VideoQuality.P480)
        store.setSegmentMinutes(10)
        store.setPhotoQuality(PhotoQuality.STANDARD)
        assertEquals(
            RecordingSettings(VideoQuality.P480, 10, PhotoQuality.STANDARD),
            store.settings.first(),
        )
    }

    @Test
    fun `invalid stored values fall back to defaults`() = runTest(UnconfinedTestDispatcher()) {
        val (store, ds) = newStore()
        ds.edit {
            it[stringPreferencesKey("video_quality")] = "BOGUS"
            it[intPreferencesKey("video_segment_minutes")] = 42
            it[stringPreferencesKey("photo_quality")] = "ULTRA"
        }
        assertEquals(RecordingSettings(), store.settings.first())
    }

    @Test
    fun `setSegmentMinutes rejects values outside options`() = runTest(UnconfinedTestDispatcher()) {
        val (store, _) = newStore()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setSegmentMinutes(2) }
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
}
