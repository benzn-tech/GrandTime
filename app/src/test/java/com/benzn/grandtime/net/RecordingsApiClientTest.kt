package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingsApiClientTest {

    @Test fun `parse success`() {
        val b = """{"recordingId":"r1","uploadUrl":"https://s3/x","s3Key":"users/a/video/2026-07-13/x.mp4"}"""
        val r = RecordingsApiClient.parseUploadUrl(HttpResult(200, b)) as RecordingsApiClient.UploadUrlResult.Ok
        assertEquals("r1", r.recordingId)
        assertEquals("https://s3/x", r.uploadUrl)
        assertEquals("users/a/video/2026-07-13/x.mp4", r.s3Key)
    }

    @Test fun `401 maps to AuthExpired`() {
        assertEquals(
            RecordingsApiClient.UploadUrlResult.AuthExpired,
            RecordingsApiClient.parseUploadUrl(HttpResult(401, "")),
        )
    }

    @Test fun `4xx5xx maps to Error`() {
        assertTrue(
            RecordingsApiClient.parseUploadUrl(HttpResult(500, "boom"))
                is RecordingsApiClient.UploadUrlResult.Error,
        )
    }

    @Test fun `malformed JSON maps to Error`() {
        assertTrue(
            RecordingsApiClient.parseUploadUrl(HttpResult(200, "not json"))
                is RecordingsApiClient.UploadUrlResult.Error,
        )
    }

    @Test fun `200 with blank recordingId maps to Error`() {
        val b = """{"recordingId":"","uploadUrl":"https://s3/x","s3Key":"k"}"""
        assertTrue(
            RecordingsApiClient.parseUploadUrl(HttpResult(200, b))
                is RecordingsApiClient.UploadUrlResult.Error,
        )
    }

    @Test fun `uploadUrl wires injected HttpFns to Ok`() {
        val fake = object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
                return HttpResult(200, """{"recordingId":"r2","uploadUrl":"https://s3/y","s3Key":"k2"}""")
            }
            override fun putFile(url: String, contentType: String, file: java.io.File): Int = 200
        }
        val client = RecordingsApiClient("https://api.example.com/prod/api", fake)
        val req = UploadUrlReq(
            kind = "video",
            clientUuid = "uuid-1",
            siteId = null,
            fileName = "x.mp4",
            contentType = "video/mp4",
            startedAt = "2026-07-13T00:00:00Z",
            endedAt = null,
            durationS = null,
            sizeBytes = null,
            resolution = null,
            codec = null,
        )
        val result = client.uploadUrl("idtok", req) as RecordingsApiClient.UploadUrlResult.Ok
        assertEquals("r2", result.recordingId)
        assertEquals("https://s3/y", result.uploadUrl)
    }

    @Test fun `complete returns true on 2xx`() {
        val fake = object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
                return HttpResult(200, """{"ok":true}""")
            }
            override fun putFile(url: String, contentType: String, file: java.io.File): Int = 200
        }
        val client = RecordingsApiClient("https://api.example.com/prod/api", fake)
        assertTrue(client.complete("idtok", "r1", 1234L))
    }
}
