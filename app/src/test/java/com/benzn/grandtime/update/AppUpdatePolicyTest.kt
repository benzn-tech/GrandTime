package com.benzn.grandtime.update

import com.benzn.grandtime.update.AppUpdatePolicy.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {

    @Test
    fun `nothing is offered when the installed build is current or newer`() {
        assertEquals(Offer.NONE, AppUpdatePolicy.offer(currentVersionCode = 41, latestVersionCode = 41, minVersionCode = 0))
        assertEquals(Offer.NONE, AppUpdatePolicy.offer(currentVersionCode = 42, latestVersionCode = 41, minVersionCode = 41))
    }

    @Test
    fun `a newer build is offered`() {
        assertEquals(Offer.OPTIONAL, AppUpdatePolicy.offer(currentVersionCode = 40, latestVersionCode = 41, minVersionCode = 0))
    }

    @Test
    fun `below the minimum the update is required`() {
        assertEquals(Offer.REQUIRED, AppUpdatePolicy.offer(currentVersionCode = 39, latestVersionCode = 41, minVersionCode = 40))
        assertEquals(Offer.OPTIONAL, AppUpdatePolicy.offer(currentVersionCode = 40, latestVersionCode = 41, minVersionCode = 40))
    }

    private fun accept(
        sha: String = "ab".repeat(32),
        size: Long = 26_000_000,
        pkg: String? = "com.benzn.grandtime",
        version: Long? = 41,
        current: Long = 40,
        signers: Boolean = true,
    ) = AppUpdatePolicy.acceptDownload(
        expectedSha256 = "ab".repeat(32), actualSha256 = sha,
        expectedSizeBytes = 26_000_000, actualSizeBytes = size,
        ownPackage = "com.benzn.grandtime", archivePackage = pkg,
        expectedVersionCode = 41, archiveVersionCode = version,
        currentVersionCode = current, signersMatch = signers,
    )

    @Test
    fun `a download that matches everything is accepted`() {
        assertTrue(accept())
        assertTrue("digest case does not matter", accept(sha = "AB".repeat(32)))
    }

    @Test
    fun `a download that differs in any one respect is refused`() {
        assertFalse("digest", accept(sha = "cd".repeat(32)))
        assertFalse("size", accept(size = 25_999_999))
        assertFalse("another app, e.g. the dev flavour", accept(pkg = "com.benzn.grandtime.dev"))
        assertFalse("not an APK the system could read", accept(pkg = null))
        assertFalse("not the version the manifest promised", accept(version = 42))
        assertFalse("not newer than installed", accept(current = 41))
        assertFalse("signed by anyone else", accept(signers = false))
    }

    @Test
    fun `never installs while recording`() {
        assertFalse(AppUpdatePolicy.mayInstallNow(captureIdle = false))
        assertTrue(AppUpdatePolicy.mayInstallNow(captureIdle = true))
    }
}
