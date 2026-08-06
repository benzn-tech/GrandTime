package com.benzn.grandtime.net

import com.benzn.grandtime.auth.HttpResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusClientTest {

    @Test fun `vitals serialize as the contract says`() {
        val body = DeviceStatusClient.requestBody(
            DeviceVitals(
                oldestPendingAgeS = 93600, pending = 12, frozen = 3, dead = 1,
                fingerprints = listOf("uploadurl_401", "complete_403"),
            )
        )
        val json = JSONObject(body)
        assertEquals(93600, json.getLong("oldestPendingAgeS"))
        assertEquals(12, json.getInt("pending"))
        assertEquals(3, json.getInt("frozen"))
        assertEquals(1, json.getInt("dead"))
        assertEquals(2, json.getJSONArray("fingerprints").length())
    }

    /** Null, not zero: "nothing is waiting" and "something has waited no time" differ. */
    @Test fun `an idle device reports a null age, not a zero`() {
        val json = JSONObject(
            DeviceStatusClient.requestBody(DeviceVitals(null, 0, 0, 0, emptyList()))
        )
        assertTrue(json.isNull("oldestPendingAgeS"))
    }

    @Test fun `a response is read`() {
        val r = DeviceStatusClient.parse("""{"serverBuild":"9495bcd","thaw":["uploadurl_401"]}""")!!
        assertEquals("9495bcd", r.serverBuild)
        assertEquals(listOf("uploadurl_401"), r.thaw)
    }

    /** The probe is telemetry. It must never be able to change what an upload does. */
    @Test fun `an unreadable response is no response, not a crash`() {
        assertNull(DeviceStatusClient.parse("not json"))
        assertNull(DeviceStatusClient.parse(null))
    }

    @Test fun `a response with no thaw list is an empty one`() {
        assertEquals(emptyList<String>(), DeviceStatusClient.parse("""{"serverBuild":"x"}""")!!.thaw)
    }

    /** Phase 1's server answers exactly this, and it must not be mistaken for a failure. */
    @Test fun `an empty thaw list is a valid answer`() {
        val r = DeviceStatusClient.parse("""{"serverBuild":"x","thaw":[]}""")!!
        assertEquals("x", r.serverBuild)
        assertTrue(r.thaw.isEmpty())
    }

    @Test fun `a blank server build reads as unknown`() {
        assertNull(DeviceStatusClient.parse("""{"serverBuild":"","thaw":[]}""")!!.serverBuild)
    }

    @Test fun `a non-2xx answer is no answer`() {
        val client = DeviceStatusClient("https://api", object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String) = HttpResult(500, "boom")
            override fun putFile(url: String, contentType: String, file: java.io.File) = 200
        })
        assertNull(client.report("token", DeviceVitals(null, 0, 0, 0, emptyList())))
    }

    @Test fun `a thrown request is no answer, not a crash`() {
        val client = DeviceStatusClient("https://api", object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult =
                throw java.io.IOException("offline")
            override fun putFile(url: String, contentType: String, file: java.io.File) = 200
        })
        assertNull(client.report("token", DeviceVitals(null, 0, 0, 0, emptyList())))
    }

    @Test fun `the probe posts to the device status route`() {
        var seen = ""
        val client = DeviceStatusClient("https://api", object : HttpFns {
            override fun postJson(url: String, authToken: String, jsonBody: String): HttpResult {
                seen = url
                return HttpResult(200, """{"serverBuild":"x","thaw":[]}""")
            }
            override fun putFile(url: String, contentType: String, file: java.io.File) = 200
        })
        client.report("token", DeviceVitals(null, 0, 0, 0, emptyList()))
        assertEquals("https://api/org/device/status", seen)
    }
}
