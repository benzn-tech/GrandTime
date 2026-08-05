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

    @Test fun `5xx maps to Busy — server backpressure, not a broken upload`() {
        val r = RecordingsApiClient.parseUploadUrl(HttpResult(503, "throttled"))
        assertTrue(r is RecordingsApiClient.UploadUrlResult.Busy)
        assertEquals(503, (r as RecordingsApiClient.UploadUrlResult.Busy).code)
    }

    @Test fun `429 maps to Busy`() {
        assertTrue(
            RecordingsApiClient.parseUploadUrl(HttpResult(429, "slow down"))
                is RecordingsApiClient.UploadUrlResult.Busy
        )
    }

    @Test fun `client 4xx maps to Error`() {
        assertTrue(
            RecordingsApiClient.parseUploadUrl(HttpResult(403, "forbidden"))
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
        assertTrue(client.completeStatus("idtok", "r1", 1234L).code in 200..299)
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
        val ok = client.completeStatus(
            "idtok", "r1", 1234L,
            gpsTrack = """[{"t":1,"lat":-36.85,"lon":174.76}]""",
        )
        assertTrue(ok.code in 200..299)
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
        assertTrue(client.completeStatus("idtok", "r1", 1234L).code in 200..299)
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
        val ok = client.completeStatus("idtok", "r1", 1234L, gpsTrack = "not valid json{{{").code in 200..299
        assertTrue(ok)
        assertTrue(!org.json.JSONObject(sentBody).has("gpsTrack"))
    }

    // The line between "the server is busy" and "this request is wrong" decides whether a
    // failure spends the record's 8-attempt permanent-failure budget. Getting it wrong is
    // how good recordings were abandoned on 2026-08-03 while the backend was merely full.
    @Test fun `isTransient covers no-response, 429 and 5xx only`() {
        assertTrue(RecordingsApiClient.isTransient(RecordingsApiClient.NO_RESPONSE))
        assertTrue(RecordingsApiClient.isTransient(429))
        assertTrue(RecordingsApiClient.isTransient(500))
        assertTrue(RecordingsApiClient.isTransient(502))
        assertTrue(RecordingsApiClient.isTransient(503))
        assertTrue(RecordingsApiClient.isTransient(504))
        // Success and genuine client errors are NOT transient — retrying them unchanged
        // can never help.
        assertTrue(!RecordingsApiClient.isTransient(200))
        assertTrue(!RecordingsApiClient.isTransient(400))
        assertTrue(!RecordingsApiClient.isTransient(401))
        assertTrue(!RecordingsApiClient.isTransient(403))
        assertTrue(!RecordingsApiClient.isTransient(409))
    }

    // ---- the group-ended signal riding on the upload (spec 2026-08-04) ------
    //
    // This is the only channel back to a device with no open connection. What
    // matters most here is that it can never affect the upload verdict itself:
    // a misread body costs one missed prompt, whereas an exception would turn a
    // successful upload into a retry of the whole file.

    private fun clientReturning(code: Int, body: String) = RecordingsApiClient(
        "https://api.example.com/prod/api",
        object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String) =
                HttpResult(code, body)
            override fun putFile(url: String, contentType: String, file: java.io.File): Int = 200
        },
    )

    @Test fun `complete reports groupEnded when the server says so`() {
        val r = clientReturning(200, """{"ok":true,"groupEnded":true}""")
            .completeStatus("idtok", "r1", 1234L)
        assertTrue(r.groupEnded)
        assertEquals(200, r.code)
    }

    @Test fun `a solo complete is not group-ended`() {
        // The overwhelmingly common response, unchanged by this feature.
        assertTrue(!clientReturning(200, """{"ok":true}""").completeStatus("idtok", "r1", 1L).groupEnded)
    }

    @Test fun `a malformed body still yields the status code`() {
        // A body the client cannot parse must not cost the upload its verdict.
        val r = clientReturning(200, "<html>gateway</html>").completeStatus("idtok", "r1", 1L)
        assertEquals(200, r.code)
        assertTrue(!r.groupEnded)
    }

    @Test fun `an empty body still yields the status code`() {
        val r = clientReturning(200, "").completeStatus("idtok", "r1", 1L)
        assertEquals(200, r.code)
        assertTrue(!r.groupEnded)
    }

    @Test fun `a throttled complete stays transient and not group-ended`() {
        // 429/5xx is the BUG-43 path: it must keep spending retry budget the way
        // it always did, and never be read as a meeting instruction.
        val r = clientReturning(429, "").completeStatus("idtok", "r1", 1L)
        assertTrue(RecordingsApiClient.isTransient(r.code))
        assertTrue(!r.groupEnded)
    }

    // ---- the group riding on the upload -------------------------------------

    @Test fun `upload-url carries the group when the recording has one`() {
        var sent = ""
        val client = RecordingsApiClient("https://api.example.com/prod/api", object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
                sent = jsonBody
                return HttpResult(200, """{"recordingId":"r","uploadUrl":"u","s3Key":"k"}""")
            }
            override fun putFile(url: String, contentType: String, file: java.io.File): Int = 200
        })
        client.uploadUrl("idtok", UploadUrlReq(
            kind = "audio", clientUuid = "c", fileName = "f.wav", contentType = "audio/wav",
            startedAt = "2026-08-06T09:00:00+12:00", groupId = "b".repeat(32)))
        assertEquals("b".repeat(32), org.json.JSONObject(sent).getString("groupId"))
    }

    @Test fun `a solo upload-url body is unchanged`() {
        // Most uploads. They have no part in this feature and their request
        // must not gain a field.
        var sent = ""
        val client = RecordingsApiClient("https://api.example.com/prod/api", object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
                sent = jsonBody
                return HttpResult(200, """{"recordingId":"r","uploadUrl":"u","s3Key":"k"}""")
            }
            override fun putFile(url: String, contentType: String, file: java.io.File): Int = 200
        })
        client.uploadUrl("idtok", UploadUrlReq(
            kind = "audio", clientUuid = "c", fileName = "f.wav", contentType = "audio/wav",
            startedAt = "2026-08-06T09:00:00+12:00"))
        assertTrue(!org.json.JSONObject(sent).has("groupId"))
    }
}
