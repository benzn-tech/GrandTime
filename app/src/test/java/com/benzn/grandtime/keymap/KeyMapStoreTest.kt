package com.benzn.grandtime.keymap

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    private fun TestScope.newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
        ) { File(tmp.root, "keymap.preferences_pb") }

    private fun TestScope.newStore(): KeyMapStore = KeyMapStore(newDataStore())

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
        val ds = newDataStore()
        val store = KeyMapStore(ds)
        // 直接写入一个非法的原始字符串(不是任何 KeyAction 的合法值),绕过 setOverride 的类型安全
        ds.edit { it[stringPreferencesKey("VIDEO_SHORT")] = "BOGUS_ACTION" }
        // 直接读也不抛异常,非法条目被静默丢弃
        assertEquals(emptyMap<String, KeyAction>(), store.overrides.first())
    }
}
