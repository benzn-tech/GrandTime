package com.benzn.grandtime.update

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the wiring of in-app updates that a JVM test cannot drive: WorkManager, PackageManager and
 * the system installer. The rules themselves are in AppUpdatePolicyTest.
 */
class AppUpdateWiringTest {

    private fun src(path: String) = File("src/main/java/com/benzn/grandtime/$path").readText()
    private val worker = src("update/AppUpdateWorker.kt")
    private val updates = src("update/AppUpdates.kt")

    @Test
    fun `a download is verified before it is ever marked ready`() {
        // From the download on. An earlier publish in handle() re-shows a record that an earlier run
        // already verified, for the same digest, with its APK still on disk -- nothing new is offered
        // there. What must hold is that a file THIS run downloaded is verified before it is recorded
        // or offered.
        val handle = worker.substring(worker.indexOf("private fun handle("))
        val afterDownload = handle.substring(handle.indexOf("val sha = download("))
        val verify = afterDownload.indexOf("accept(part, sha, latest, current)")
        assertTrue("the worker must verify the download", verify >= 0)
        assertTrue("verification must precede the ready record", verify < afterDownload.indexOf("store.write(ready)"))
        assertTrue("verification must precede publishing", verify < afterDownload.indexOf("AppUpdates.publish(ready)"))
        assertTrue("the signer check must be part of acceptance", worker.contains("signersMatch = archiveSigners.isNotEmpty() && archiveSigners == AppUpdates.signerDigests(own)"))
    }

    @Test
    fun `the installer is never opened while recording`() {
        val install = updates.substring(updates.indexOf("fun install("))
        val guard = install.indexOf("AppUpdatePolicy.mayInstallNow(captureIdle)")
        assertTrue(guard >= 0)
        assertTrue("the recording check must come before any startActivity", guard < install.indexOf("startActivity("))
    }

    @Test
    fun `the manifest and file provider allow installing from the private update directory`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        val paths = File("src/main/res/xml/file_paths.xml").readText()
        assertTrue("updates/ must be shareable with the installer", Regex("""<files-path[^>]*path="${AppUpdateStore.DIR}/"""").containsMatchIn(paths))
    }

    @Test
    fun `checks are scheduled at launch and at sign-in`() {
        val app = src("GrandTimeApp.kt")
        assertTrue(app.contains("AppUpdates.restoreOnStart(this)"))
        assertTrue(app.contains("AppUpdates.schedulePeriodic(this)"))
        assertTrue(src("service/CoreService.kt").contains("AppUpdates.checkNow(applicationContext)"))
    }
}
