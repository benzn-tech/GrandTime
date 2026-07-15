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

    // T13: complete() 可选携带 gpsTrack —— 字符串还原成 JSONArray 放进 body,后端得真数组(非二次转义字符串)。
    @Test fun `complete includes gpsTrack as JSON array when present`() {
        var sentBody = ""
        val fake = object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
                sentBody = jsonBody
                return HttpResult(200, """{"ok":true}""")
            }
            override fun putFile(url: String, contentType: String, file: java.io.File): Int = 200
        }
        val client = RecordingsApiClient("https://api.example.com/prod/api", fake)
        val ok = client.complete(
            "idtok", "r1", 1234L,
            gpsTrack = """[{"t":1,"lat":-36.85,"lon":174.76}]""",
        )
        assertTrue(ok)
        val json = org.json.JSONObject(sentBody)
        val arr = json.getJSONArray("gpsTrack")
        assertEquals(1, arr.length())
        val point = arr.getJSONObject(0)
        assertEquals(1, point.getInt("t"))
        assertEquals(174.76, point.getDouble("lon"), 0.0001)
    }

    // gpsTrack=null(默认参数,老调用点不传)—— body 不带该 key,后端无感知。
    @Test fun `complete omits gpsTrack key when null`() {
        var sentBody = ""
        val fake = object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
                sentBody = jsonBody
                return HttpResult(200, """{"ok":true}""")
            }
            override fun putFile(url: String, contentType: String, file: java.io.File): Int = 200
        }
        val client = RecordingsApiClient("https://api.example.com/prod/api", fake)
        assertTrue(client.complete("idtok", "r1", 1234L))
        assertTrue(!org.json.JSONObject(sentBody).has("gpsTrack"))
    }

    // 脏数据防御:DB 行里 gpsTrack 字符串损坏(非合法 JSON)时,complete 仍应成功——只是不带 gpsTrack。
    @Test fun `complete succeeds and drops gpsTrack key when track string is corrupt JSON`() {
        var sentBody = ""
        val fake = object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
                sentBody = jsonBody
                return HttpResult(200, """{"ok":true}""")
            }
            override fun putFile(url: String, contentType: String, file: java.io.File): Int = 200
        }
        val client = RecordingsApiClient("https://api.example.com/prod/api", fake)
        val ok = client.complete("idtok", "r1", 1234L, gpsTrack = "not valid json{{{")
        assertTrue(ok)
        assertTrue(!org.json.JSONObject(sentBody).has("gpsTrack"))
    }
}
