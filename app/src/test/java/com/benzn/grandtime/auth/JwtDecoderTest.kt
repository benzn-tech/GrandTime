package com.benzn.grandtime.auth

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JwtDecoderTest {

    private fun jwt(payloadJson: String): String {
        val header = b64("""{"alg":"RS256","typ":"JWT"}""")
        val payload = b64(payloadJson)
        return "$header.$payload.sigsigsig"
    }

    private fun b64(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    // Builds an unsigned JWT carrying only `exp`, for isExpired()/exp-decoding tests. Encoded with
    // java.util.Base64.getUrlEncoder() to match JwtDecoder.decode(), which reads with
    // java.util.Base64.getUrlDecoder() — android.util.Base64 is unavailable/stubbed under the JVM
    // unit test runner, so it cannot be used here.
    private fun tokenWithExp(expSeconds: Long): String {
        val header = b64("""{"alg":"none"}""")
        val payload = b64("""{"sub":"u1","exp":$expSeconds}""")
        return "$header.$payload.sig"
    }

    @Test
    fun `decodes sub email name`() {
        val c = JwtDecoder.decode(jwt("""{"sub":"abc-123","email":"a@b.com","name":"Jane Doe"}"""))!!
        assertEquals("abc-123", c.sub)
        assertEquals("a@b.com", c.email)
        assertEquals("Jane Doe", c.name)
    }

    @Test
    fun `missing name and email are null but sub present`() {
        val c = JwtDecoder.decode(jwt("""{"sub":"only-sub"}"""))!!
        assertEquals("only-sub", c.sub)
        assertNull(c.email)
        assertNull(c.name)
    }

    @Test
    fun `no sub returns null`() {
        assertNull(JwtDecoder.decode(jwt("""{"email":"a@b.com"}""")))
    }

    @Test
    fun `malformed token returns null not crash`() {
        assertNull(JwtDecoder.decode("not.a.jwt"))
        assertNull(JwtDecoder.decode("garbage"))
        assertNull(JwtDecoder.decode(""))
    }

    @Test
    fun `escaped quote in name is preserved not corrupted`() {
        val c = JwtDecoder.decode(jwt("""{"sub":"u-1","name":"Jane \"JJ\" Doe"}"""))!!
        assertEquals("u-1", c.sub)
        assertEquals("Jane \"JJ\" Doe", c.name)
    }

    @Test
    fun `real org json optString returns null for absent key not literal string null`() {
        // Proves the contract JwtDecoder relies on for email/name(optString(key, null)) against
        // the real org.json:json dependency (some versions coerce absent keys to the string "null").
        val obj = JSONObject("""{"sub":"only-sub"}""")
        assertNull(obj.optString("email", null))
        assertNull(obj.optString("name", null))
        assertNull(obj.optString("cognito:username", null))
    }

    @Test
    fun `decode reads exp`() {
        val t = tokenWithExp(1_800_000_000L)
        assertEquals(1_800_000_000L, JwtDecoder.decode(t)!!.exp)
    }

    @Test
    fun `isExpired true for past token`() =
        assertTrue(JwtDecoder.isExpired(tokenWithExp(1_000_000_000L), nowMillis = 2_000_000_000_000L))

    @Test
    fun `isExpired false for future token`() =
        assertFalse(JwtDecoder.isExpired(tokenWithExp(2_000_000_000L), nowMillis = 1_000_000_000_000L))

    @Test
    fun `isExpired true within skew window`() =
        // exp = now+30s, skew 60s -> treated expired
        assertTrue(JwtDecoder.isExpired(tokenWithExp(1_000_000_030L), nowMillis = 1_000_000_000_000L, skewSeconds = 60))

    @Test
    fun `isExpired true for unparseable token`() =
        assertTrue(JwtDecoder.isExpired("not-a-jwt", nowMillis = 1_000_000_000_000L))
}
