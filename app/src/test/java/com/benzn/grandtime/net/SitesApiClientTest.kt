package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitesApiClientTest {

    @Test fun `parse success`() {
        val b = """{"sites":[{"id":"u1","slug":"north","name":"North Wharf"}]}"""
        val result = SitesApiClient.parseSites(HttpResult(200, b))
        assertEquals(1, result.size)
        assertEquals(SitesApiClient.SiteOption("u1", "north", "North Wharf"), result[0])
    }

    @Test fun `non-2xx maps to empty list`() {
        assertTrue(SitesApiClient.parseSites(HttpResult(500, "boom")).isEmpty())
    }

    @Test fun `malformed JSON maps to empty list`() {
        assertTrue(SitesApiClient.parseSites(HttpResult(200, "not json")).isEmpty())
    }

    @Test fun `missing sites key maps to empty list`() {
        assertTrue(SitesApiClient.parseSites(HttpResult(200, """{"other":true}""")).isEmpty())
    }

    @Test fun `entries missing id are skipped`() {
        val b = """{"sites":[{"slug":"north","name":"North Wharf"},{"id":"u2","slug":"south","name":"South Dock"}]}"""
        val result = SitesApiClient.parseSites(HttpResult(200, b))
        assertEquals(1, result.size)
        assertEquals("u2", result[0].id)
    }

    @Test fun `address is parsed when present`() {
        val b = """{"sites":[{"id":"u1","slug":"north","name":"North Wharf","address":"123 Dock Rd"}]}"""
        val result = SitesApiClient.parseSites(HttpResult(200, b))
        assertEquals(1, result.size)
        assertEquals("123 Dock Rd", result[0].address)
    }

    @Test fun `address is null when absent`() {
        val b = """{"sites":[{"id":"u1","slug":"north","name":"North Wharf"}]}"""
        val result = SitesApiClient.parseSites(HttpResult(200, b))
        assertEquals(1, result.size)
        assertEquals(null, result[0].address)
    }

    @Test fun `listSites wires injected http to parsed list`() {
        val fake = object : SitesHttpFns {
            override fun getJson(url: String, authToken: String): HttpResult {
                assertEquals("https://api.example.com/prod/api/org/sites", url)
                assertEquals("idtok", authToken)
                return HttpResult(200, """{"sites":[{"id":"u1","slug":"north","name":"North Wharf"}]}""")
            }
        }
        val client = SitesApiClient("https://api.example.com/prod/api", fake)
        val result = client.listSites("idtok")
        assertEquals(1, result.size)
        assertEquals("u1", result[0].id)
    }

    // ------------------------------------------------------------------ failure-aware
    //
    // listSites maps a network error, a non-2xx and a malformed body all to an empty list, so
    // "this account has no sites" and "the device is offline" are indistinguishable. The
    // single-site auto-select ACTS on that answer, and clearing a selection because the device
    // happened to be offline would lose a valid one on every cold start without a connection.

    @Test fun `parseSitesOrNull returns the sites on success`() {
        val b = """{"sites":[{"id":"u1","slug":"north","name":"North Wharf"}]}"""
        assertEquals(
            listOf(SitesApiClient.SiteOption("u1", "north", "North Wharf")),
            SitesApiClient.parseSitesOrNull(HttpResult(200, b)),
        )
    }

    @Test fun `parseSitesOrNull keeps an empty sites array as a real answer`() {
        assertEquals(
            emptyList<SitesApiClient.SiteOption>(),
            SitesApiClient.parseSitesOrNull(HttpResult(200, """{"sites":[]}""")),
        )
    }

    @Test fun `parseSitesOrNull gives no answer for a non-2xx`() {
        assertNull(SitesApiClient.parseSitesOrNull(HttpResult(500, "boom")))
        assertNull(SitesApiClient.parseSitesOrNull(HttpResult(401, "")))
    }

    @Test fun `parseSitesOrNull gives no answer for malformed JSON or a missing sites key`() {
        assertNull(SitesApiClient.parseSitesOrNull(HttpResult(200, "not json")))
        assertNull(SitesApiClient.parseSitesOrNull(HttpResult(200, """{"other":true}""")))
    }

    @Test fun `fetchSites gives no answer when the network call throws`() {
        val offline = object : SitesHttpFns {
            override fun getJson(url: String, authToken: String): HttpResult = throw java.io.IOException("offline")
        }
        assertNull(SitesApiClient("https://example.test/api", offline).fetchSites("token"))
    }

    @Test fun `listSites is unchanged - still an empty list for every failure`() {
        val offline = object : SitesHttpFns {
            override fun getJson(url: String, authToken: String): HttpResult = throw java.io.IOException("offline")
        }
        assertTrue(SitesApiClient("https://example.test/api", offline).listSites("token").isEmpty())
    }
}
