package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import java.io.File
import java.time.OffsetDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsApiClientTest {

    private val sid = "9f8c1e2a4b6d47f0a1b2c3d4e5f60718"
    // 2026-07-28T14:03:00 NZST is epoch 1785200580000 (deterministic; asserted via round-trip, not literal).
    private val startMs = 1785200580000L

    private class FakeHttp(val code: Int = 200) : HttpFns {
        var url: String? = null
        var authToken: String? = null
        var body: String? = null
        override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
            this.url = url; this.authToken = authToken; this.body = jsonBody
            return HttpResult(code, "{}")
        }
        override fun putFile(url: String, contentType: String, file: File): Int = 200
    }

    @Test fun open_posts_to_open_url_with_camelCase_body_and_raw_token() {
        val fake = FakeHttp(200)
        val ok = SessionsApiClient("https://api.example/prod/api", fake).open(
            idToken = "TOK", sessionId = sid, startedAtMillis = startMs, kind = "video", siteId = "site-1",
        )
        assertTrue(ok)
        assertEquals("https://api.example/prod/api/org/sessions/$sid/open", fake.url)
        assertEquals("TOK", fake.authToken) // raw idToken, no "Bearer "
        val b = JSONObject(fake.body!!)
        assertEquals("video", b.getString("kind"))
        assertEquals("site-1", b.getString("siteId"))
        // startedAt is ISO8601 that round-trips to the input instant (DST-agnostic assertion)
        assertEquals(startMs, OffsetDateTime.parse(b.getString("startedAt")).toInstant().toEpochMilli())
    }

    @Test fun open_omits_siteId_when_null() {
        val fake = FakeHttp(200)
        SessionsApiClient("https://api.example/prod/api", fake).open("TOK", sid, startMs, "audio", null)
        val b = JSONObject(fake.body!!)
        assertFalse(b.has("siteId"))
        assertEquals("audio", b.getString("kind"))
    }

    @Test fun close_posts_to_close_url_with_intent_and_endedAt() {
        val fake = FakeHttp(200)
        val ok = SessionsApiClient("https://api.example/prod/api", fake).close("TOK", sid, startMs, "idle")
        assertTrue(ok)
        assertEquals("https://api.example/prod/api/org/sessions/$sid/close", fake.url)
        val b = JSONObject(fake.body!!)
        assertEquals("idle", b.getString("intent"))
        assertEquals(startMs, OffsetDateTime.parse(b.getString("endedAt")).toInstant().toEpochMilli())
    }

    @Test fun non_2xx_returns_false() {
        assertFalse(SessionsApiClient("https://api.example/prod/api", FakeHttp(500)).close("TOK", sid, startMs, "idle"))
    }

    @Test fun http_exception_returns_false() {
        val throwing = object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult = throw RuntimeException("network")
            override fun putFile(url: String, contentType: String, file: File): Int = 200
        }
        assertFalse(SessionsApiClient("https://api.example/prod/api", throwing).open("TOK", sid, startMs, "video", null))
    }
}
