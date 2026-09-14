package com.benzn.grandtime.core

import com.benzn.grandtime.net.SitesApiClient

/**
 * What to do with the selected site once the signed-in account's site list is known.
 *
 * Pure, so every rule is unit-tested; CoreService applies the decision.
 *
 * WHY. An account with exactly one site made the person tap "Select site" and pick the only row
 * on every sign-in. And the selection survived sign-out, on a device handed between clients
 * monthly, so the next person inherited the previous client's site. `recordings.site_id` is
 * what the backend treats as authoritative for attribution, so that was not a cosmetic leftover:
 * it filed one client's recordings under another client's site.
 *
 * Only call this with a list that was actually fetched. A failed request is not an empty list,
 * and treating it as one would clear a valid selection every time the device is offline.
 */
object SiteAutoSelect {

    sealed interface Decision {
        /** Leave the selection as it is. */
        data object Keep : Decision

        /** Select this site. */
        data class Select(val site: SelectedSite) : Decision

        /** The current selection is a site this account cannot access; remove it. */
        data object Clear : Decision
    }

    fun decide(available: List<SitesApiClient.SiteOption>, current: SelectedSite?): Decision {
        // A site the person chose, and still has, is never overridden -- including when they have
        // several and picked one deliberately.
        if (current != null && available.any { it.id == current.id }) return Decision.Keep

        // Exactly one site: nothing to choose between. This covers "never chosen" and also
        // "chosen, but it is a site this account no longer has", which is the handover case.
        available.singleOrNull()?.let { only ->
            return Decision.Select(
                SelectedSite(id = only.id, slug = only.slug, name = only.name, address = only.address),
            )
        }

        // A selection this account cannot access, and more than one real option (or none):
        // there is no right answer to substitute, so remove the wrong one and let the picker ask.
        // Keeping it would stamp recordings with a site the person does not belong to.
        if (current != null) return Decision.Clear

        // No selection and several sites: that is a genuine choice, and it is the person's.
        return Decision.Keep
    }
}
