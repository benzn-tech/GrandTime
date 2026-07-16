package com.benzn.grandtime.ask

import com.benzn.grandtime.auth.HttpResult
import com.benzn.grandtime.net.HttpFns
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakeHttp(
    val response: HttpResult,
    val throwOnPost: Boolean = false,
) : HttpFns {
    var lastUrl: String? = null
    var lastToken: String? = null
    var lastBody: String? = null
    override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
        if (throwOnPost) throw java.io.IOException("network")
        lastUrl = url; lastToken = authToken; lastBody = jsonBody
        return response
    }
    override fun putFile(url: String, contentType: String, file: File): Int = 200
}

class AskApiClientTest {
    private fun okBody() = JSONObject()
        .put("transcript", "what happened at ellesmere")
        .put("answerText", "The slab pour finished.")
        .put("audioBase64", "UklGRg==")
        .put("audioFormat", "wav").toString()

    @Test fun builds_request_to_ask_voice_with_mode_voice() {
        val http = FakeHttp(HttpResult(200, okBody()))
        AskApiClient("https://h/prod/api", http).ask("ID", "QUJD", "wav")
        assertEquals("https://h/prod/api/ask/voice", http.lastUrl)
        assertEquals("ID", http.lastToken)
        val body = JSONObject(http.lastBody!!)
        assertEquals("QUJD", body.getString("audio"))
        assertEquals("wav", body.getString("format"))
        assertEquals("voice", body.getString("mode"))
    }

    @Test fun parses_ok() {
        val http = FakeHttp(HttpResult(200, okBody()))
        val r = AskApiClient("https://h/prod/api", http).ask("ID", "QUJD") as AskApiClient.AskResult.Ok
        assertEquals("what happened at ellesmere", r.transcript)
        assertEquals("The slab pour finished.", r.answerText)
        assertEquals("UklGRg==", r.audioBase64)
        assertEquals("wav", r.audioFormat)
    }

    @Test fun parses_stage_error_with_transcript() {
        val body = JSONObject().put("error", "Empty transcript").put("transcript", "").toString()
        val http = FakeHttp(HttpResult(200, body))
        val r = AskApiClient("b", http).ask("ID", "QUJD") as AskApiClient.AskResult.StageError
        assertEquals("Empty transcript", r.message)
        assertEquals("", r.transcript)
    }

    @Test fun parses_stage_error_without_transcript() {
        val body = JSONObject().put("error", "Speech recognition failed").toString()
        val http = FakeHttp(HttpResult(200, body))
        val r = AskApiClient("b", http).ask("ID", "QUJD") as AskApiClient.AskResult.StageError
        assertEquals("Speech recognition failed", r.message)
        assertNull(r.transcript)
    }

    @Test fun maps_401_to_auth_expired() {
        val http = FakeHttp(HttpResult(401, ""))
        assertTrue(AskApiClient("b", http).ask("ID", "QUJD") is AskApiClient.AskResult.AuthExpired)
    }

    @Test fun maps_5xx_to_error() {
        val http = FakeHttp(HttpResult(500, "boom"))
        assertTrue(AskApiClient("b", http).ask("ID", "QUJD") is AskApiClient.AskResult.Error)
    }

    @Test fun missing_audio_base64_is_error() {
        val body = JSONObject().put("transcript", "x").put("answerText", "y").toString()
        val http = FakeHttp(HttpResult(200, body))
        assertTrue(AskApiClient("b", http).ask("ID", "QUJD") is AskApiClient.AskResult.Error)
    }

    @Test fun network_exception_is_error() {
        val http = FakeHttp(HttpResult(200, okBody()), throwOnPost = true)
        assertTrue(AskApiClient("b", http).ask("ID", "QUJD") is AskApiClient.AskResult.Error)
    }
}
