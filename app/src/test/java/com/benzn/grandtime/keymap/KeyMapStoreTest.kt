package com.benzn.grandtime.keymap

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.PressType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeyMapStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newStore(): KeyMapStore {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
        ) { File(tmp.root, "keymap.preferences_pb") }
        return KeyMapStore(ds)
    }

    @Test
    fun `set override then read back`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.setOverride(HardKey.VIDEO, PressType.SHORT, KeyAction.TAKE_PHOTO)
        assertEquals(
            mapOf("VIDEO_SHORT" to KeyAction.TAKE_PHOTO),
            store.overrides.first(),
        )
    }

    @Test
    fun `reset clears all overrides`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.setOverride(HardKey.SOS, PressType.LONG, KeyAction.TAKE_PHOTO)
        store.resetToDefaults()
        assertEquals(emptyMap<String, KeyAction>(), store.overrides.first())
    }

    @Test
    fun `unknown stored value is ignored not crash`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.setOverride(HardKey.AUDIO, PressType.SHORT, KeyAction.NONE)
        // 直接读也不抛异常
        assertEquals(mapOf("AUDIO_SHORT" to KeyAction.NONE), store.overrides.first())
    }
}
