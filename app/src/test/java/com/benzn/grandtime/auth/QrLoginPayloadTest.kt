package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrLoginPayloadTest {
    @Test fun `parses a valid v2 payload`() {
        val p = QrLoginParser.parse("""{"v":2,"c":"CODE123","env":"prod"}""")
        assertEquals(QrLoginPayload("CODE123", "prod"), p)
    }

    @Test fun `rejects wrong version`() {
        assertNull(QrLoginParser.parse("""{"v":1,"c":"C","env":"prod"}"""))
    }

    @Test fun `rejects missing code`() {
        assertNull(QrLoginParser.parse("""{"v":2,"env":"prod"}"""))
    }

    @Test fun `rejects missing env`() {
        assertNull(QrLoginParser.parse("""{"v":2,"c":"C"}"""))
    }

    @Test fun `rejects blank env`() {
        assertNull(QrLoginParser.parse("""{"v":2,"c":"C","env":""}"""))
    }

    @Test fun `rejects non-JSON (eg a random website QR)`() {
        assertNull(QrLoginParser.parse("https://example.com"))
    }

    @Test fun `rejects blank`() {
        assertNull(QrLoginParser.parse(""))
    }
}
