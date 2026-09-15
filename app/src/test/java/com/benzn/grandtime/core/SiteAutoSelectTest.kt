package com.benzn.grandtime.core

import com.benzn.grandtime.core.SiteAutoSelect.Decision
import com.benzn.grandtime.net.SitesApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An account with one site had to tap "Select site" and pick the only row on every sign-in, and
 * the selection survived sign-out on a device handed between clients monthly -- so the next
 * person inherited the previous client's site, and `recordings.site_id` (which the backend treats
 * as authoritative) filed their recordings under it.
 */
class SiteAutoSelectTest {

    private fun opt(id: String) = SitesApiClient.SiteOption(id = id, slug = "slug-$id", name = "Site $id", address = null)
    private fun sel(id: String) = SelectedSite(id = id, slug = "slug-$id", name = "Site $id", address = null)

    @Test
    fun `one site and nothing selected - it is selected`() {
        assertEquals(Decision.Select(sel("a")), SiteAutoSelect.decide(listOf(opt("a")), current = null))
    }

    @Test
    fun `several sites and nothing selected - the person chooses`() {
        assertEquals(Decision.Keep, SiteAutoSelect.decide(listOf(opt("a"), opt("b")), current = null))
    }

    @Test
    fun `a site the person chose and still has is never overridden`() {
        assertEquals(Decision.Keep, SiteAutoSelect.decide(listOf(opt("a"), opt("b")), current = sel("b")))
    }

    @Test
    fun `one site that is already selected - nothing changes`() {
        assertEquals(Decision.Keep, SiteAutoSelect.decide(listOf(opt("a")), current = sel("a")))
    }

    @Test
    fun `an inherited site this account cannot access is replaced when there is only one real option`() {
        // The handover case: the previous client's site is still selected.
        assertEquals(
            Decision.Select(sel("mine")),
            SiteAutoSelect.decide(listOf(opt("mine")), current = sel("previous-clients")),
        )
    }

    @Test
    fun `an inaccessible site is cleared rather than kept when there are several options`() {
        // Keeping it would stamp recordings with a site the person does not belong to.
        assertEquals(
            Decision.Clear,
            SiteAutoSelect.decide(listOf(opt("a"), opt("b")), current = sel("previous-clients")),
        )
    }

    @Test
    fun `an account with no sites loses an inaccessible selection`() {
        assertEquals(Decision.Clear, SiteAutoSelect.decide(emptyList(), current = sel("previous-clients")))
    }

    @Test
    fun `an account with no sites and nothing selected - nothing to do`() {
        assertEquals(Decision.Keep, SiteAutoSelect.decide(emptyList(), current = null))
    }

    @Test
    fun `the selected site carries every field the picker would have stored`() {
        val withAddress = SitesApiClient.SiteOption(id = "a", slug = "uc-pk", name = "UC PK", address = "1 Main Rd")
        assertEquals(
            Decision.Select(SelectedSite(id = "a", slug = "uc-pk", name = "UC PK", address = "1 Main Rd")),
            SiteAutoSelect.decide(listOf(withAddress), current = null),
        )
    }
}
