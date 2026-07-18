package com.benzn.grandtime.sitevoice

import com.benzn.grandtime.auth.HttpResult
import com.benzn.grandtime.net.HttpFns
import com.benzn.grandtime.net.SitesHttpFns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakePost(val resp: HttpResult) : HttpFns {
    var lastUrl: String? = null; var lastBody: String? = null
    override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
        lastUrl = url; lastBody = jsonBody; return resp
    }
    override fun putFile(url: String, contentType: String, file: File): Int = 200
}
private class FakeGet(val resp: HttpResult) : SitesHttpFns {
    var lastUrl: String? = null
    override fun getJson(url: String, authToken: String): HttpResult { lastUrl = url; return resp }
}

class VoiceApiClientTest {
    @Test fun `parse upload url ok`() {
        val b = """{"uploadUrl":"https://s3/put","s3Key":"voice/co/s/x.wav"}"""
        val r = VoiceApiClient.parseUploadUrl(HttpResult(200, b)) as VoiceApiClient.UploadUrlResult.Ok
        assertEquals("https://s3/put", r.uploadUrl)
        assertEquals("voice/co/s/x.wav", r.s3Key)
    }

    @Test fun `upload url 401 is AuthExpired`() {
        assertEquals(
            VoiceApiClient.UploadUrlResult.AuthExpired,
            VoiceApiClient.parseUploadUrl(HttpResult(401, "")),
        )
    }

    @Test fun `upload url blank s3Key is Error`() {
        val b = """{"uploadUrl":"u","s3Key":""}"""
        assertTrue(VoiceApiClient.parseUploadUrl(HttpResult(200, b)) is VoiceApiClient.UploadUrlResult.Error)
    }

    @Test fun `upload url wires request path and body`() {
        val fake = FakePost(HttpResult(200, """{"uploadUrl":"u","s3Key":"k"}"""))
        val client = VoiceApiClient("https://h/prod/api", http = fake)
        val req = VoiceUploadReq("site1", "clip.wav", "audio/wav", "2026-07-18T00:00:00Z", 3, 96044L)
        client.uploadUrl("ID", req)
        assertEquals("https://h/prod/api/org/voice/upload-url", fake.lastUrl)
        val body = org.json.JSONObject(fake.lastBody!!)
        assertEquals("site1", body.getString("siteId"))
        assertEquals("clip.wav", body.getString("fileName"))
        assertEquals("audio/wav", body.getString("contentType"))
        assertEquals(3, body.getInt("durationS"))
        assertEquals(96044L, body.getLong("sizeBytes"))
    }

    @Test fun `parse download url`() {
        assertEquals("https://s3/get", VoiceApiClient.parseDownloadUrl(HttpResult(200, """{"url":"https://s3/get"}""")))
        assertNull(VoiceApiClient.parseDownloadUrl(HttpResult(200, """{}""")))
        assertNull(VoiceApiClient.parseDownloadUrl(HttpResult(404, "")))
    }

    @Test fun `download url wires query and returns url`() {
        val fake = FakeGet(HttpResult(200, """{"url":"https://s3/get"}"""))
        val client = VoiceApiClient("https://h/prod/api", getHttp = fake)
        assertEquals("https://s3/get", client.downloadUrl("ID", "voice/co/s/x.wav"))
        assertEquals("https://h/prod/api/org/voice/asset-url?key=voice%2Fco%2Fs%2Fx.wav", fake.lastUrl)
    }

    @Test fun `parse backfill items`() {
        val b = """{"items":[{"s3Key":"a","senderUserId":"u1","createdAt":"t1","durationS":3},{"s3Key":"b","senderUserId":"u2","createdAt":"t2"},{"s3Key":"c","senderUserId":"u3","createdAt":"t3","durationS":null}]}"""
        val items = VoiceApiClient.parseBackfill(HttpResult(200, b))
        assertEquals(3, items.size)
        assertEquals("a", items[0].s3Key)
        assertEquals(3, items[0].durationS)
        assertNull(items[1].durationS)   // durationS absent
        assertNull(items[2].durationS)   // durationS explicitly null (unknown, NOT 0)
    }

    @Test fun `backfill non-2xx is empty`() {
        assertTrue(VoiceApiClient.parseBackfill(HttpResult(500, "boom")).isEmpty())
    }

    @Test fun `backfill wires since query`() {
        val fake = FakeGet(HttpResult(200, """{"items":[]}"""))
        val client = VoiceApiClient("https://h/prod/api", getHttp = fake)
        client.backfill("ID", "site1", "2026-07-18T00:00:00Z")
        assertEquals("https://h/prod/api/org/sites/site1/voice?since=2026-07-18T00%3A00%3A00Z", fake.lastUrl)
    }

    @Test fun `backfill without since omits query`() {
        val fake = FakeGet(HttpResult(200, """{"items":[]}"""))
        VoiceApiClient("https://h/prod/api", getHttp = fake).backfill("ID", "site1", null)
        assertEquals("https://h/prod/api/org/sites/site1/voice", fake.lastUrl)
    }
}
