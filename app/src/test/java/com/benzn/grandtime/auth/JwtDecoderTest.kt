package com.benzn.grandtime.auth

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JwtDecoderTest {

    private fun jwt(payloadJson: String): String {
        val header = b64("""{"alg":"RS256","typ":"JWT"}""")
        val payload = b64(payloadJson)
        return "$header.$payload.sigsigsig"
    }

    private fun b64(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

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
}
