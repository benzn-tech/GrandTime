package com.benzn.grandtime.core

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

class SiteStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
        ) { File(tmp.root, "site.preferences_pb") }

    private fun TestScope.newStore(): SiteStore = SiteStore(newDataStore())

    @Test
    fun `set site then read back`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        val site = SelectedSite("u1", "north", "North Wharf")
        store.set(site)
        assertEquals(site, store.site.first())
    }

    @Test
    fun `set null clears site`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.set(SelectedSite("u1", "north", "North Wharf"))
        store.set(null)
        assertNull(store.site.first())
    }
}
