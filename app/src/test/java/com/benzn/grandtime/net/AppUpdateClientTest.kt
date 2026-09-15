package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateClientTest {

    private val release = """
        {"available": true, "versionCode": 41, "versionName": "0.7.15", "minVersionCode": 40,
         "sha256": "${"A1".repeat(32)}", "sizeBytes": 26000000, "notes": "x",
         "url": "https://signed.example/app-releases/41/a.apk", "expiresIn": 900}
    """.trimIndent()

    @Test
    fun `a published release is parsed`() {
        val a = AppUpdateClient.parse(HttpResult(200, release))
        assertTrue(a is AppUpdateClient.Answer.Release)
        val r = (a as AppUpdateClient.Answer.Release).release
        assertEquals(41L, r.versionCode)
        assertEquals(40L, r.minVersionCode)
        assertEquals("a1".repeat(32), r.sha256)
    }

    @Test
    fun `no release published yet is its own answer`() {
        assertEquals(AppUpdateClient.Answer.NoRelease, AppUpdateClient.parse(HttpResult(200, """{"available": false}""")))
    }

    @Test
    fun `failures are never read as up to date`() {
        // A broken endpoint that read as "nothing new" would silently stop every device updating.
        val failed = AppUpdateClient.Answer.Failed
        assertEquals("503 (manifest unreadable)", failed, AppUpdateClient.parse(HttpResult(503, """{"error":"x"}""")))
        assertEquals("403", failed, AppUpdateClient.parse(HttpResult(403, """{"available": false}""")))
        assertEquals("not JSON", failed, AppUpdateClient.parse(HttpResult(200, "<html>")))
        assertEquals("no available key", failed, AppUpdateClient.parse(HttpResult(200, "{}")))
    }

    @Test
    fun `an incomplete or implausible release is a failure`() {
        val failed = AppUpdateClient.Answer.Failed
        assertEquals(failed, AppUpdateClient.parse(HttpResult(200, release.replace("\"sha256\": \"${"A1".repeat(32)}\",", ""))))
        assertEquals(failed, AppUpdateClient.parse(HttpResult(200, release.replace("A1".repeat(32), "zz".repeat(32)))))
        assertEquals(failed, AppUpdateClient.parse(HttpResult(200, release.replace("https://", "http://"))))
        assertEquals(failed, AppUpdateClient.parse(HttpResult(200, release.replace("\"minVersionCode\": 40", "\"minVersionCode\": 42"))))
    }
}
