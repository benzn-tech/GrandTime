package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrLoginPayloadTest {
    @Test fun `parses a valid v1 payload`() {
        val p = QrLoginParser.parse("""{"v":1,"u":"a@b.com","c":"CODE123","env":"prod"}""")
        assertEquals(QrLoginPayload("a@b.com", "CODE123", "prod"), p)
    }

    @Test fun `rejects wrong version`() {
        assertNull(QrLoginParser.parse("""{"v":2,"u":"a@b.com","c":"C","env":"prod"}"""))
    }

    @Test fun `rejects missing code`() {
        assertNull(QrLoginParser.parse("""{"v":1,"u":"a@b.com","env":"prod"}"""))
    }

    @Test fun `rejects missing username`() {
        assertNull(QrLoginParser.parse("""{"v":1,"c":"C","env":"prod"}"""))
    }

    @Test fun `rejects non-JSON (eg a random website QR)`() {
        assertNull(QrLoginParser.parse("https://example.com"))
    }

    @Test fun `rejects blank`() {
        assertNull(QrLoginParser.parse(""))
    }
}
