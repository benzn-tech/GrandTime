package com.benzn.grandtime.ui

import com.benzn.grandtime.hardware.HardKey
import com.benzn.grandtime.hardware.PressType
import com.benzn.grandtime.keymap.KeyAction

fun keyLabel(key: HardKey): String = when (key) {
    HardKey.VIDEO -> "录像键"
    HardKey.PHOTO -> "拍照键"
    HardKey.AUDIO -> "录音键"
    HardKey.SOS -> "SOS键"
}

fun pressLabel(pressType: PressType): String = when (pressType) {
    PressType.SHORT -> "短按"
    PressType.LONG -> "长按"
}

fun actionLabel(action: KeyAction): String = when (action) {
    KeyAction.START_STOP_VIDEO -> "开始/停止录像"
    KeyAction.TOGGLE_VIDEO_UPLOAD -> "切换视频上传"
    KeyAction.TAKE_PHOTO -> "拍照"
    KeyAction.TOGGLE_TORCH -> "开/关手电筒"
    KeyAction.ADJUST_VOLUME -> "调节音量"
    KeyAction.START_STOP_AUDIO -> "开始/停止录音"
    KeyAction.SEND_SOS -> "发送SOS报警"
    KeyAction.TOGGLE_WARNING_LIGHT -> "切换警灯闪烁"
    KeyAction.NONE -> "无"
}
