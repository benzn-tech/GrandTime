package com.benzn.grandtime.keymap

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.KeyPress
import com.benzn.grandtime.hardware.PressType

object KeyMapping {

    val DEFAULTS: Map<Pair<HardKey, PressType>, KeyAction> = mapOf(
        (HardKey.VIDEO to PressType.SHORT) to KeyAction.START_STOP_VIDEO,
        (HardKey.VIDEO to PressType.LONG) to KeyAction.TOGGLE_VIDEO_UPLOAD,
        (HardKey.PHOTO to PressType.SHORT) to KeyAction.TAKE_PHOTO,
        (HardKey.PHOTO to PressType.LONG) to KeyAction.TOGGLE_TORCH,
        (HardKey.AUDIO to PressType.SHORT) to KeyAction.ADJUST_VOLUME,
        (HardKey.AUDIO to PressType.LONG) to KeyAction.START_STOP_AUDIO,
        (HardKey.SOS to PressType.SHORT) to KeyAction.SEND_SOS,
        (HardKey.SOS to PressType.LONG) to KeyAction.TOGGLE_WARNING_LIGHT,
    )

    fun overrideKeyOf(key: HardKey, pressType: PressType): String =
        "${key.name}_${pressType.name}"

    fun resolve(press: KeyPress, overrides: Map<String, KeyAction>): KeyAction =
        overrides[overrideKeyOf(press.key, press.pressType)]
            ?: DEFAULTS[press.key to press.pressType]
            ?: KeyAction.NONE
}
