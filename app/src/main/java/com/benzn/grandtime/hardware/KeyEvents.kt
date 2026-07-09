package com.benzn.grandtime.hardware

enum class HardKey { VIDEO, PHOTO, AUDIO, SOS }

enum class PressType { SHORT, LONG }

enum class RawDirection { DOWN, UP }

data class KeyPress(val key: HardKey, val pressType: PressType)
