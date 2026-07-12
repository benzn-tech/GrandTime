package com.benzn.grandtime.capture

import android.media.MediaActionSound

/** 系统内置录制/快门提示音(spec §2.7)。懒加载,主线程调用安全。 */
class CaptureSounds {
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
        load(MediaActionSound.SHUTTER_CLICK)
    }

    fun startRecording() = sound.play(MediaActionSound.START_VIDEO_RECORDING)
    fun stopRecording() = sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
    fun shutter() = sound.play(MediaActionSound.SHUTTER_CLICK)
    fun release() = sound.release()
}
