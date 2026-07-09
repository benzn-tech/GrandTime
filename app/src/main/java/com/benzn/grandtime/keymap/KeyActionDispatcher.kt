package com.benzn.grandtime.keymap

import com.benzn.grandtime.hardware.KeyPress

class KeyActionDispatcher(
    private val currentOverrides: () -> Map<String, KeyAction>,
    private val handler: (KeyAction, KeyPress) -> Unit,
) {
    fun dispatch(press: KeyPress) {
        val action = KeyMapping.resolve(press, currentOverrides())
        if (action != KeyAction.NONE) handler(action, press)
    }
}
