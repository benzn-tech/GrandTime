package com.benzn.grandtime.sitevoice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LastSeenStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.newStore(): LastSeenStore = LastSeenStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(coroutineContext + Job())) {
            File(tmp.root, "site_voice.preferences_pb")
        },
    )

    @Test fun `absent is null`() = runTest(UnconfinedTestDispatcher()) {
        assertNull(newStore().lastSeen.first())
    }

    @Test fun `set then read back`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.set("2026-07-18T02:03:04Z")
        assertEquals("2026-07-18T02:03:04Z", store.lastSeen.first())
    }

    @Test fun `set overwrites`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.set("2026-07-18T01:00:00Z")
        store.set("2026-07-18T05:00:00Z")
        assertEquals("2026-07-18T05:00:00Z", store.lastSeen.first())
    }
}
