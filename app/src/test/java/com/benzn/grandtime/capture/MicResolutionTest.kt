package com.benzn.grandtime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicResolutionTest {

    // TYPE_BUILTIN_MIC is 15; TYPE_WIRED_HEADSET is 3. Literals so the test does not depend on
    // android.jar stubs.
    private val builtinMic = 15
    private val wiredHeadset = 3

    private val board = listOf(
        InputDevice(id = 13, type = builtinMic, address = "bottom", productName = "SDJW-F2S"),
        InputDevice(id = 14, type = builtinMic, address = "back", productName = "SDJW-F2S"),
        InputDevice(id = 21, type = wiredHeadset, address = "", productName = "headset"),
    )

    @Test fun `front resolves to the bottom-addressed built-in mic`() {
        assertEquals(13, resolvePreferredMic(board, MicChoice.FRONT)?.id)
    }

    @Test fun `back resolves to the back-addressed built-in mic`() {
        assertEquals(14, resolvePreferredMic(board, MicChoice.BACK)?.id)
    }

    @Test fun `a non-built-in device is never chosen even if its address matches`() {
        val decoys = listOf(InputDevice(id = 21, type = wiredHeadset, address = "back", productName = "headset"))
        assertNull(resolvePreferredMic(decoys, MicChoice.BACK))
    }

    @Test fun `address matching ignores case`() {
        val upper = listOf(InputDevice(id = 14, type = builtinMic, address = "BACK", productName = "x"))
        assertEquals(14, resolvePreferredMic(upper, MicChoice.BACK)?.id)
    }

    @Test fun `an unresolvable choice returns null rather than falling back to another mic`() {
        val onlyFront = listOf(InputDevice(id = 13, type = builtinMic, address = "bottom", productName = "x"))
        assertNull(resolvePreferredMic(onlyFront, MicChoice.BACK))
    }

    @Test fun `json escapes quotes backslashes and newlines`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", jsonString("a\"b\\c\nd"))
    }

    @Test fun `json object renders fields in the order given`() {
        val s = jsonObject(listOf("a" to "1", "b" to jsonString("x")))
        assertEquals("{\"a\":1,\"b\":\"x\"}", s)
    }

    @Test fun `json array renders items in the order given`() {
        assertEquals("[1,2]", jsonArray(listOf("1", "2")))
    }
}
