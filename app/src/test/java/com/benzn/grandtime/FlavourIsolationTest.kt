package com.benzn.grandtime

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural flavour isolation (VizField C1).
 *
 * The gateway is compile-time burned — which backend a recording lands in is decided HERE,
 * not at runtime. A dev flavour once got installed in the field and the symptom was "the app
 * recorded but the prod site shows nothing", so these assertions run for EVERY variant's unit
 * tests: point a vizfield flavour at a FieldSight gateway (or vice versa) and this goes red
 * under that variant before the APK exists.
 */
class FlavourIsolationTest {

    /** API Gateway ids of every FieldSight endpoint the app has ever been burned with. */
    private val fieldsightGatewayIds = listOf("ys94qy2tk0", "wdsgobb7b0", "ouv5cmq6si", "i1r3tuv9bh")

    private val allBurnedUrls = listOf(BuildConfig.ORG_API_BASE_URL, BuildConfig.SITE_VOICE_WS_URL)

    @Test
    fun vizfield_flavours_carry_no_fieldsight_endpoint() {
        if (!BuildConfig.FLAVOR.startsWith("vizfield")) return
        for (url in allBurnedUrls) {
            for (id in fieldsightGatewayIds) {
                assertFalse(
                    "vizfield variant must not embed FieldSight gateway $id (found in $url)",
                    url.contains(id),
                )
            }
        }
    }

    @Test
    fun vizfield_flavours_point_only_at_vizfield_dot_com() {
        if (!BuildConfig.FLAVOR.startsWith("vizfield")) return
        val host = URI(BuildConfig.ORG_API_BASE_URL).host
        assertTrue(
            "vizfield org api host must be under vizfield.com, was $host",
            host == "vizfield.com" || host.endsWith(".vizfield.com"),
        )
        // No VizField WS backend exists; an enabled site voice could only reach a FieldSight one.
        assertFalse("site voice must stay off for vizfield flavours", BuildConfig.SITE_VOICE_ENABLED)
        assertEquals("", BuildConfig.SITE_VOICE_WS_URL)
    }

    @Test
    fun vizfield_flavours_use_the_vizfield_package_and_qr_env() {
        when (BuildConfig.FLAVOR) {
            "vizfield" -> {
                assertEquals("com.benzn.vizfield", BuildConfig.APPLICATION_ID)
                assertEquals("vizfield", BuildConfig.QR_ENV)
            }
            "vizfieldDev" -> {
                assertEquals("com.benzn.vizfield.dev", BuildConfig.APPLICATION_ID)
                assertEquals("vizfield-test", BuildConfig.QR_ENV)
            }
        }
    }

    @Test
    fun fieldsight_flavours_still_point_where_they_always_did() {
        // The inverse guard: adding vizfield must not have moved the pilot's endpoints.
        when (BuildConfig.FLAVOR) {
            "prod" -> {
                assertTrue(BuildConfig.ORG_API_BASE_URL.contains("ys94qy2tk0"))
                assertEquals("com.benzn.grandtime", BuildConfig.APPLICATION_ID)
            }
            "dev" -> {
                assertTrue(BuildConfig.ORG_API_BASE_URL.contains("wdsgobb7b0"))
                assertEquals("com.benzn.grandtime.dev", BuildConfig.APPLICATION_ID)
            }
        }
    }

    @Test
    fun no_flavour_mixes_environments() {
        // Whatever the flavour, the org api must be reachable over https and belong to exactly
        // one world: a FieldSight execute-api or the vizfield.com zone, never anything else.
        val url = BuildConfig.ORG_API_BASE_URL
        assertTrue(url.startsWith("https://"))
        val host = URI(url).host
        val isFieldsight = fieldsightGatewayIds.any { host.startsWith("$it.") }
        val isVizfield = host == "vizfield.com" || host.endsWith(".vizfield.com")
        assertTrue("org api host $host is neither a known FieldSight gateway nor vizfield.com", isFieldsight != isVizfield)
        assertEquals(BuildConfig.FLAVOR.startsWith("vizfield"), isVizfield)
    }
}
