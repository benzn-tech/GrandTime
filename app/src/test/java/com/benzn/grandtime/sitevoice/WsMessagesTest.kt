package com.benzn.grandtime.sitevoice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WsMessagesTest {
    @Test fun parses_inbound_frame() {
        val text = """{"s3Key":"voice/co/site1/abc.wav","senderUserId":"u9","siteId":"site1","createdAt":"2026-07-18T02:03:04Z"}"""
        val m = WsMessages.parseInbound(text)!!
        assertEquals("voice/co/site1/abc.wav", m.s3Key)
        assertEquals("u9", m.senderUserId)
        assertEquals("site1", m.siteId)
        assertEquals("2026-07-18T02:03:04Z", m.createdAt)
        assertNull(m.durationS)
    }

    @Test fun parses_inbound_with_optional_duration() {
        val text = """{"s3Key":"k","senderUserId":"u","createdAt":"t","durationS":7}"""
        assertEquals(7, WsMessages.parseInbound(text)!!.durationS)
    }

    @Test fun inbound_missing_s3key_is_null() {
        assertNull(WsMessages.parseInbound("""{"senderUserId":"u","createdAt":"t"}"""))
    }

    @Test fun malformed_inbound_is_null() {
        assertNull(WsMessages.parseInbound("not json"))
    }

    @Test fun builds_send_voice_frame() {
        val body = JSONObject(WsMessages.sendVoiceFrame("site1", "voice/x.wav", 4))
        assertEquals("sendVoice", body.getString("action"))
        assertEquals("site1", body.getString("siteId"))
        assertEquals("voice/x.wav", body.getString("s3Key"))
        assertEquals(4, body.getInt("durationS"))
    }

    @Test fun wav_duration_from_bytes() {
        // 44-byte header + 2s of 16kHz mono 16-bit = 44 + 64000
        assertEquals(2, WsMessages.wavDurationSeconds(44 + 64_000L))
        assertEquals(0, WsMessages.wavDurationSeconds(44))
        assertEquals(0, WsMessages.wavDurationSeconds(10)) // shorter than header -> 0, no negative
    }
}
