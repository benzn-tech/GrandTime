package com.benzn.grandtime.keymap

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMappingTest {

    @Test
    fun `default table matches reverse-engineered mapping`() {
        assertEquals(KeyAction.START_STOP_VIDEO,
            KeyMapping.resolve(KeyPress(HardKey.VIDEO, PressType.SHORT), emptyMap()))
        assertEquals(KeyAction.START_STOP_VIDEO,
            KeyMapping.resolve(KeyPress(HardKey.VIDEO, PressType.LONG), emptyMap()))
        assertEquals(KeyAction.TAKE_PHOTO,
            KeyMapping.resolve(KeyPress(HardKey.PHOTO, PressType.SHORT), emptyMap()))
        assertEquals(KeyAction.TOGGLE_TORCH,
            KeyMapping.resolve(KeyPress(HardKey.PHOTO, PressType.LONG), emptyMap()))
        assertEquals(KeyAction.ADJUST_VOLUME,
            KeyMapping.resolve(KeyPress(HardKey.AUDIO, PressType.SHORT), emptyMap()))
        assertEquals(KeyAction.START_STOP_AUDIO,
            KeyMapping.resolve(KeyPress(HardKey.AUDIO, PressType.LONG), emptyMap()))
        assertEquals(KeyAction.SEND_SOS,
            KeyMapping.resolve(KeyPress(HardKey.SOS, PressType.SHORT), emptyMap()))
        assertEquals(KeyAction.TOGGLE_WARNING_LIGHT,
            KeyMapping.resolve(KeyPress(HardKey.SOS, PressType.LONG), emptyMap()))
    }

    @Test
    fun `override wins over default`() {
        val overrides = mapOf("VIDEO_SHORT" to KeyAction.TAKE_PHOTO)
        assertEquals(KeyAction.TAKE_PHOTO,
            KeyMapping.resolve(KeyPress(HardKey.VIDEO, PressType.SHORT), overrides))
        assertEquals(KeyAction.START_STOP_VIDEO,
            KeyMapping.resolve(KeyPress(HardKey.VIDEO, PressType.LONG), overrides))
    }

    @Test
    fun `overrideKeyOf format`() {
        assertEquals("SOS_LONG", KeyMapping.overrideKeyOf(HardKey.SOS, PressType.LONG))
    }
}
