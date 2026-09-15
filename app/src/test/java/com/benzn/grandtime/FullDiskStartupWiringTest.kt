package com.benzn.grandtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the wiring that lets the app start on a disk filled to 0 bytes. Source scans, because the
 * failure happens inside platform code a JVM test cannot run; the behaviour itself was measured
 * on DQF2S.
 *
 * Measured there with 0.7.10: at 0 bytes with nothing of ours left to delete, WorkManager's
 * ForceStopRunnable hit SQLITE_FULL on its own executor thread and threw -- the process died
 * 2.5 s after launch, beyond the reach of any runCatching in onCreate.
 */
class FullDiskStartupWiringTest {

    private val app = File("src/main/java/com/benzn/grandtime/GrandTimeApp.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `WorkManager is not auto-initialised before onCreate`() {
        // androidx.startup opened WorkManager's database before Application.onCreate, so nothing
        // of ours could give space back first.
        val entry = Regex("""<meta-data[^>]*androidx\.work\.WorkManagerInitializer[^>]*>""").find(manifest)
        assertTrue("WorkManagerInitializer must be declared in the manifest to be removed", entry != null)
        assertTrue("and it must be removed", entry!!.value.contains("""tools:node="remove""""))
    }

    @Test
    fun `a WorkManager initialisation failure is logged, not thrown`() {
        val config = app.substringAfter("override val workManagerConfiguration", "")
        assertTrue("GrandTimeApp must provide the WorkManager configuration", config.isNotEmpty())
        assertTrue(
            "without an initialization exception handler, SQLITE_FULL in ForceStopRunnable kills the process",
            config.substringBefore(".build()").contains("setInitializationExceptionHandler"),
        )
    }

    @Test
    fun `the emergency reclaim runs before anything touches WorkManager`() {
        val onCreate = app.substringAfter("override fun onCreate()", "")
        val reclaim = onCreate.indexOf(".emergency()")
        val workManager = onCreate.indexOf("WorkManager.getInstance")
        assertTrue("onCreate must run the emergency reclaim", reclaim >= 0)
        assertTrue("and before WorkManager opens its database", workManager < 0 || reclaim < workManager)
    }
}
