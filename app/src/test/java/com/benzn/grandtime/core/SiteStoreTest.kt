package com.benzn.grandtime.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.benzn.grandtime.net.SitesApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `set site with address then read back`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        val site = SelectedSite("u1", "north", "North Wharf", address = "123 Dock Rd")
        store.set(site)
        assertEquals(site, store.site.first())
    }

    @Test
    fun `set site without address then read back has null address`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        val site = SelectedSite("u1", "north", "North Wharf")
        store.set(site)
        assertEquals(null, store.site.first()?.address)
    }

    @Test
    fun `set null clears site`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        store.set(SelectedSite("u1", "north", "North Wharf"))
        store.set(null)
        assertNull(store.site.first())
    }

    @Test
    fun `setSiteList then read back`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        val sites = listOf(
            SitesApiClient.SiteOption("u1", "north", "North Wharf"),
            SitesApiClient.SiteOption("u2", "south", "South Dock"),
        )
        store.setSiteList(sites)
        assertEquals(sites, store.siteList.first())
    }

    @Test
    fun `setSiteList with address then read back`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        val sites = listOf(
            SitesApiClient.SiteOption("u1", "north", "North Wharf", address = "123 Dock Rd"),
            SitesApiClient.SiteOption("u2", "south", "South Dock"),
        )
        store.setSiteList(sites)
        assertEquals(sites, store.siteList.first())
    }

    @Test
    fun `siteList absent is empty`() = runTest(UnconfinedTestDispatcher()) {
        val store = newStore()
        assertTrue(store.siteList.first().isEmpty())
    }

    @Test
    fun `siteList malformed json is empty`() = runTest(UnconfinedTestDispatcher()) {
        val dataStore = newDataStore()
        dataStore.edit { it[stringPreferencesKey("site_list_json")] = "not json" }
        val store = SiteStore(dataStore)
        assertTrue(store.siteList.first().isEmpty())
    }
}
