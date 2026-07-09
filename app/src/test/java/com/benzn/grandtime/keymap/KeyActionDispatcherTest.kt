package com.benzn.grandtime.keymap

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyActionDispatcherTest {

    @Test
    fun `dispatch resolves via defaults and calls handler`() {
        val calls = mutableListOf<Pair<KeyAction, KeyPress>>()
        val dispatcher = KeyActionDispatcher({ emptyMap() }) { a, p -> calls.add(a to p) }
        val press = KeyPress(HardKey.PHOTO, PressType.SHORT)
        dispatcher.dispatch(press)
        assertEquals(listOf(KeyAction.TAKE_PHOTO to press), calls)
    }

    @Test
    fun `dispatch respects live overrides`() {
        var overrides = mapOf("PHOTO_SHORT" to KeyAction.SEND_SOS)
        val calls = mutableListOf<KeyAction>()
        val dispatcher = KeyActionDispatcher({ overrides }) { a, _ -> calls.add(a) }
        dispatcher.dispatch(KeyPress(HardKey.PHOTO, PressType.SHORT))
        overrides = emptyMap()
        dispatcher.dispatch(KeyPress(HardKey.PHOTO, PressType.SHORT))
        assertEquals(listOf(KeyAction.SEND_SOS, KeyAction.TAKE_PHOTO), calls)
    }

    @Test
    fun `NONE action does not call handler`() {
        val calls = mutableListOf<KeyAction>()
        val dispatcher = KeyActionDispatcher({ mapOf("SOS_SHORT" to KeyAction.NONE) }) { a, _ -> calls.add(a) }
        dispatcher.dispatch(KeyPress(HardKey.SOS, PressType.SHORT))
        assertTrue(calls.isEmpty())
    }
}
