package com.benzn.grandtime.ui

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.PressType
import com.benzn.grandtime.keymap.KeyAction

fun keyLabel(key: HardKey): String = when (key) {
    HardKey.VIDEO -> "Video key"
    HardKey.PHOTO -> "Photo key"
    HardKey.AUDIO -> "Audio key"
    HardKey.SOS -> "SOS key"
}

fun shortKeyLabel(key: HardKey): String = when (key) {
    HardKey.VIDEO -> "Video"
    HardKey.PHOTO -> "Photo"
    HardKey.AUDIO -> "Audio"
    HardKey.SOS -> "SOS"
}

fun pressLabel(pressType: PressType): String = when (pressType) {
    PressType.SHORT -> "Short press"
    PressType.LONG -> "Long press"
}

fun shortPressLabel(pressType: PressType): String = when (pressType) {
    PressType.SHORT -> "short"
    PressType.LONG -> "long"
}

fun actionLabel(action: KeyAction): String = when (action) {
    KeyAction.START_STOP_VIDEO -> "Start/stop video"
    KeyAction.TOGGLE_VIDEO_UPLOAD -> "Toggle video upload"
    KeyAction.TAKE_PHOTO -> "Take photo"
    KeyAction.TOGGLE_TORCH -> "Torch on/off"
    KeyAction.ADJUST_VOLUME -> "Adjust volume"
    KeyAction.START_STOP_AUDIO -> "Start/stop audio"
    KeyAction.SEND_SOS -> "Send SOS"
    KeyAction.TOGGLE_WARNING_LIGHT -> "Toggle warning light"
    KeyAction.ASK_AGENT -> "Ask agent"
    KeyAction.NONE -> "None"
}
