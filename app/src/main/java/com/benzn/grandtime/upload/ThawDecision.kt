package com.benzn.grandtime.upload

import com.benzn.grandtime.db.CaptureRecord

/**
 * Whether a frozen record may be tried again.
 *
 * Two ways to earn it — the backend has been redeployed since the freeze, or an operator
 * named this fingerprint explicitly — and one veto that outranks both.
 *
 * The veto is ownership. A frozen `uploadurl_403` is most likely an identity the backend
 * refused to map, and these devices change hands between clients every month — so the
 * account signed in now is exactly the account that must NOT inherit the last one's
 * rejected upload. Same reason [com.benzn.grandtime.db.CaptureRecordDao.listPendingForAuthor]
 * refuses to match a null author.
 */
object ThawDecision {

    fun shouldThaw(
        record: CaptureRecord,
        currentAuthorSub: String?,
        serverBuild: String?,
        thawList: List<String>,
    ): Boolean {
        val owner = record.authorSub ?: return false
        if (currentAuthorSub == null || owner != currentAuthorSub) return false

        val explicit = record.failureCode != null && record.failureCode in thawList
        // A null frozenAtBuild is "we don't know yet", not "different". Reading it as
        // different thaws on every single probe: thaw, refail, refreeze, forever.
        val redeployed = record.frozenAtBuild != null &&
            serverBuild != null &&
            record.frozenAtBuild != serverBuild

        return explicit || redeployed
    }

    /** A record frozen before the device ever saw a build adopts the first one it hears. */
    fun shouldAdoptBuild(record: CaptureRecord): Boolean =
        record.frozenSinceMs != null && record.frozenAtBuild == null
}
