package com.benzn.grandtime.update

/**
 * Pure decisions for in-app updates: whether to offer a build, whether a downloaded APK may be
 * offered at all, and whether it may be installed right now.
 *
 * WHY. Every update was a one-to-one remote install on a 320-dp screen, for each of twenty devices
 * rotated between clients. From the first build that carries this, a device finds, downloads and
 * verifies a new build itself; a person only confirms the system install dialog.
 */
object AppUpdatePolicy {

    enum class Offer {
        /** Nothing newer than what is installed. */
        NONE,
        /** Newer build available. */
        OPTIONAL,
        /**
         * Installed build is below the release's minimum. The prompt stays up, but recording is never
         * blocked: a device that cannot install (a full disk, no one there to confirm) must still
         * record.
         */
        REQUIRED,
    }

    fun offer(currentVersionCode: Long, latestVersionCode: Long, minVersionCode: Long): Offer = when {
        latestVersionCode <= currentVersionCode -> Offer.NONE
        currentVersionCode < minVersionCode -> Offer.REQUIRED
        else -> Offer.OPTIONAL
    }

    /**
     * Everything that must hold before a downloaded APK is offered for install. The system installer
     * would refuse a different signer anyway, but by then the person has already been asked, on site,
     * to install something that was never ours -- so it is refused here, before anyone sees it.
     */
    fun acceptDownload(
        expectedSha256: String,
        actualSha256: String,
        expectedSizeBytes: Long,
        actualSizeBytes: Long,
        ownPackage: String,
        archivePackage: String?,
        expectedVersionCode: Long,
        archiveVersionCode: Long?,
        currentVersionCode: Long,
        signersMatch: Boolean,
    ): Boolean =
        expectedSha256.equals(actualSha256, ignoreCase = true) &&
            expectedSizeBytes == actualSizeBytes &&
            archivePackage == ownPackage &&
            archiveVersionCode == expectedVersionCode &&
            expectedVersionCode > currentVersionCode &&
            signersMatch

    /** Installing replaces the running app and kills its process -- and any recording with it. */
    fun mayInstallNow(captureIdle: Boolean): Boolean = captureIdle
}
