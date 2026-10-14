package com.benzn.grandtime.ask

import com.benzn.grandtime.auth.HttpResult
import com.benzn.grandtime.net.HttpFns
import com.benzn.grandtime.net.awaitHttpResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Records the async seam calls (start/result/cancel) and replays canned responses. */
private class FakeAsyncHttp(var response: HttpResult = HttpResult(200, "{}")) : HttpFns {
    var posts = 0
    var gets = 0
    var lastUrl: String? = null
    var lastToken: String? = null
    var lastBody: String? = null
    var lastReadTimeoutMs: Long? = null
    var cancelUrl: String? = null
    var cancelBody: String? = null
    var throwOnCall = false
    override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult =
        error("blocking postJson must not be used by the new calls")
    override fun putFile(url: String, contentType: String, file: File): Int = 200
    override suspend fun postJsonSuspending(url: String, authToken: String, jsonBody: String): HttpResult {
        if (throwOnCall) throw java.io.IOException("network")
        posts++; lastUrl = url; lastToken = authToken; lastBody = jsonBody
        return response
    }
    override suspend fun getSuspending(url: String, authToken: String, readTimeoutMs: Long): HttpResult {
        if (throwOnCall) throw java.io.IOException("network")
        gets++; lastUrl = url; lastToken = authToken; lastReadTimeoutMs = readTimeoutMs
        return response
    }
    override fun postJsonDetached(url: String, authToken: String, jsonBody: String) {
        cancelUrl = url; cancelBody = jsonBody; lastToken = authToken
    }
}

private class FakeCall : okhttp3.Call {
    var callback: okhttp3.Callback? = null
    var cancelled = false
    private val req = okhttp3.Request.Builder().url("https://h/x").build()
    override fun request() = req
    override fun execute(): okhttp3.Response = error("must not block")
    override fun enqueue(responseCallback: okhttp3.Callback) { callback = responseCallback }
    override fun cancel() { cancelled = true }
    override fun isExecuted() = callback != null
    override fun isCanceled() = cancelled
    override fun timeout(): okio.Timeout = okio.Timeout.NONE
    override fun clone(): okhttp3.Call = FakeCall()
}

class AskApiClientTwoPhaseTest {
    private val turn = "0123456789abcdef0123456789abcdef"
    private val base = "https://h/prod/api"
    private val clip = byteArrayOf(1, 2, 3)

    private fun client(http: HttpFns, now: () -> Long = { 0L }, zone: String = "Pacific/Auckland") =
        AskApiClient(base, http, zoneId = { zone }, now = now)

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)

    private fun startBody(restatement: JSONObject? = null) = JSONObject()
        .put("turn_id", turn).put("transcript", "what is on today").put("web_intent", true)
        .put("restatement", restatement ?: JSONObject.NULL).toString()

    // ---- start: request shape ----

    @Test fun start_posts_base64_no_wrap_to_start_route_with_tz() = runTest {
        val http = FakeAsyncHttp(HttpResult(200, startBody()))
        client(http).start("ID", ByteArray(100) { it.toByte() })
        assertEquals("$base/ask/voice/start", http.lastUrl)
        assertEquals("ID", http.lastToken)
        val body = JSONObject(http.lastBody!!)
        assertEquals("wav", body.getString("format"))
        assertEquals("Pacific/Auckland", body.getString("tz"))
        assertTrue(!body.has("mode"))
        val audio = body.getString("audio")
        assertTrue("NO_WRAP: no newlines", !audio.contains("\n"))
        assertEquals(100, java.util.Base64.getDecoder().decode(audio).size)
    }

    @Test fun start_omits_blank_tz() = runTest {
        val http = FakeAsyncHttp(HttpResult(200, startBody()))
        client(http, zone = "").start("ID", clip)
        assertTrue(!JSONObject(http.lastBody!!).has("tz"))
    }

    // ---- start: response parse ----

    @Test fun start_parses_started_with_restatement() = runTest {
        val wav = byteArrayOf(9, 8, 7)
        val r = JSONObject().put("text", "Looking at today").put("audioBase64", b64(wav)).put("audioFormat", "wav")
        val res = client(FakeAsyncHttp(HttpResult(200, startBody(r)))).start("ID", clip)
            as AskApiClient.StartResult.Started
        assertEquals(turn, res.turnId)
        assertEquals("what is on today", res.transcript)
        assertTrue(res.webIntent)
        assertTrue(wav.contentEquals(res.restatementWav!!))
    }

    @Test fun start_parses_null_restatement() = runTest {
        val res = client(FakeAsyncHttp(HttpResult(200, startBody()))).start("ID", clip)
            as AskApiClient.StartResult.Started
        assertNull(res.restatementWav)
    }

    @Test fun start_restatement_without_audio_is_null() = runTest {
        val r = JSONObject().put("text", "x").put("audioBase64", "").put("audioFormat", "wav")
        val res = client(FakeAsyncHttp(HttpResult(200, startBody(r)))).start("ID", clip)
            as AskApiClient.StartResult.Started
        assertNull(res.restatementWav)
    }

    @Test fun start_stage_error_carries_transcript() = runTest {
        val body = JSONObject().put("error", "Empty transcript").put("transcript", "").toString()
        val res = client(FakeAsyncHttp(HttpResult(200, body))).start("ID", clip) as AskApiClient.StartResult.StageError
        assertEquals("Empty transcript", res.message)
        assertEquals("", res.transcript)
    }

    @Test fun start_stage_error_without_transcript() = runTest {
        val body = JSONObject().put("error", "Speech recognition failed").toString()
        val res = client(FakeAsyncHttp(HttpResult(200, body))).start("ID", clip) as AskApiClient.StartResult.StageError
        assertNull(res.transcript)
    }

    @Test fun start_401_is_auth_expired() = runTest {
        assertTrue(client(FakeAsyncHttp(HttpResult(401, ""))).start("ID", clip) is AskApiClient.StartResult.AuthExpired)
    }

    @Test fun start_5xx_malformed_and_network_are_error() = runTest {
        assertTrue(client(FakeAsyncHttp(HttpResult(500, "boom"))).start("ID", clip) is AskApiClient.StartResult.Error)
        assertTrue(client(FakeAsyncHttp(HttpResult(200, "not json"))).start("ID", clip) is AskApiClient.StartResult.Error)
        // 200 with neither turn_id nor error
        assertTrue(client(FakeAsyncHttp(HttpResult(200, "{}"))).start("ID", clip) is AskApiClient.StartResult.Error)
        val down = FakeAsyncHttp().apply { throwOnCall = true }
        assertTrue(client(down).start("ID", clip) is AskApiClient.StartResult.Error)
    }

    // ---- NoRoute and its TTL ----

    @Test fun start_404_and_403_without_json_error_are_no_route() = runTest {
        for (code in listOf(404, 403)) {
            for (body in listOf("", "<html>Missing Authentication Token</html>", """{"message":"Forbidden"}""")) {
                val res = client(FakeAsyncHttp(HttpResult(code, body))).start("ID", clip)
                assertTrue("$code / $body", res is AskApiClient.StartResult.NoRoute)
            }
        }
    }

    @Test fun start_404_with_json_error_is_a_real_error_not_no_route() = runTest {
        val res = client(FakeAsyncHttp(HttpResult(404, """{"error":"not found"}"""))).start("ID", clip)
        assertTrue(res is AskApiClient.StartResult.Error)
    }

    @Test fun no_route_is_cached_for_ten_minutes_then_retried() = runTest {
        var t = 1_000L
        val http = FakeAsyncHttp(HttpResult(404, ""))
        val c = client(http, now = { t })
        assertTrue(c.start("ID", clip) is AskApiClient.StartResult.NoRoute)
        assertEquals(1, http.posts)

        t += 10 * 60_000L - 1
        assertTrue(c.start("ID", clip) is AskApiClient.StartResult.NoRoute)
        assertEquals("no call while the memo is live", 1, http.posts)

        // The backend got deployed in the meantime.
        http.response = HttpResult(200, startBody())
        t += 1
        assertTrue(c.start("ID", clip) is AskApiClient.StartResult.Started)
        assertEquals(2, http.posts)
        // And the memo is gone for good.
        assertTrue(c.start("ID", clip) is AskApiClient.StartResult.Started)
    }

    @Test fun no_route_memo_is_renewed_when_it_404s_again() = runTest {
        var t = 0L
        val http = FakeAsyncHttp(HttpResult(404, ""))
        val c = client(http, now = { t })
        c.start("ID", clip)
        t = 10 * 60_000L
        assertTrue(c.start("ID", clip) is AskApiClient.StartResult.NoRoute)
        assertEquals(2, http.posts)
        t += 5 * 60_000L
        c.start("ID", clip)
        assertEquals(2, http.posts)
    }

    // ---- result ----

    @Test fun result_requests_turn_and_since_with_30s_read_timeout() = runTest {
        val http = FakeAsyncHttp(HttpResult(200, """{"status":"pending"}"""))
        client(http).result("ID", turn, "searching_web")
        assertEquals("$base/ask/voice/result?turn=$turn&since=searching_web", http.lastUrl)
        assertEquals("ID", http.lastToken)
        assertEquals(35_000L, http.lastReadTimeoutMs)
    }

    @Test fun result_parses_every_status() {
        fun r(code: Int, body: String) = runBlocking {
            client(FakeAsyncHttp(HttpResult(code, body))).result("ID", turn, "pending")
        }
        assertTrue(r(200, """{"status":"pending"}""") is AskApiClient.TurnStatus.Pending)
        assertTrue(r(200, """{"status":"searching_web"}""") is AskApiClient.TurnStatus.SearchingWeb)
        assertTrue(r(200, """{"status":"cancelled"}""") is AskApiClient.TurnStatus.Cancelled)
        val failed = r(200, """{"status":"error","error":"No answer"}""") as AskApiClient.TurnStatus.Failed
        assertEquals("No answer", failed.message)
        assertTrue(r(404, """{"error":"not found"}""") is AskApiClient.TurnStatus.NotFound)
        assertTrue(r(401, "") is AskApiClient.TurnStatus.AuthExpired)
        assertTrue(r(400, """{"error":"bad turn"}""") is AskApiClient.TurnStatus.Failed)
        assertTrue(r(502, "") is AskApiClient.TurnStatus.Failed)
        assertTrue(r(200, "garbage") is AskApiClient.TurnStatus.Failed)
        assertTrue(r(200, """{"status":"weird"}""") is AskApiClient.TurnStatus.Failed)
    }

    @Test fun result_parses_done() = runTest {
        val wav = byteArrayOf(5, 4, 3, 2)
        val body = JSONObject().put("status", "done").put("transcript", "q").put("answerText", "a")
            .put("basis", "records").put("audioBase64", b64(wav)).put("audioFormat", "wav").put("web", false).toString()
        val d = client(FakeAsyncHttp(HttpResult(200, body))).result("ID", turn, "pending") as AskApiClient.TurnStatus.Done
        assertEquals("q", d.transcript)
        assertEquals("a", d.answerText)
        assertTrue(wav.contentEquals(d.wav))
    }

    @Test fun result_done_without_audio_is_failed() = runTest {
        val body = JSONObject().put("status", "done").put("transcript", "q").put("answerText", "a").toString()
        assertTrue(client(FakeAsyncHttp(HttpResult(200, body))).result("ID", turn, "pending") is AskApiClient.TurnStatus.Failed)
    }

    @Test fun result_network_exception_is_failed() = runTest {
        val down = FakeAsyncHttp().apply { throwOnCall = true }
        assertTrue(client(down).result("ID", turn, "pending") is AskApiClient.TurnStatus.Failed)
    }

    // ---- cancel ----

    @Test fun cancel_posts_turn_id_fire_and_forget() {
        val http = FakeAsyncHttp()
        client(http).cancel("ID", turn)
        assertEquals("$base/ask/voice/cancel", http.cancelUrl)
        assertEquals(turn, JSONObject(http.cancelBody!!).getString("turn_id"))
        assertEquals("ID", http.lastToken)
    }

    // ---- Call cancellation ----

    @Test fun cancelling_the_coroutine_cancels_the_okhttp_call() = runTest {
        val call = FakeCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitHttpResult() }
        assertTrue("enqueued", call.callback != null)
        assertTrue(!call.cancelled)
        job.cancel()
        job.join()
        assertTrue("OkHttp Call.cancel() must run", call.cancelled)
    }

    @Test fun await_returns_code_and_body_and_maps_failure() = runTest {
        val ok = FakeCall()
        val d = async(start = CoroutineStart.UNDISPATCHED) { ok.awaitHttpResult() }
        ok.callback!!.onResponse(ok, fakeResponse(ok, 202, "hi"))
        val r = d.await()
        assertEquals(202, r.code)
        assertEquals("hi", r.body)

        val bad = FakeCall()
        val d2 = async(start = CoroutineStart.UNDISPATCHED) { runCatching { bad.awaitHttpResult() } }
        bad.callback!!.onFailure(bad, java.io.IOException("x"))
        assertTrue(d2.await().exceptionOrNull() is java.io.IOException)
    }

    private fun fakeResponse(call: okhttp3.Call, code: Int, body: String) = okhttp3.Response.Builder()
        .request(call.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("m")
        .body(body.toResponseBody("text/plain".toMediaType())).build()
}
