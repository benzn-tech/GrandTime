package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import org.junit.Assert.assertEquals
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
}
