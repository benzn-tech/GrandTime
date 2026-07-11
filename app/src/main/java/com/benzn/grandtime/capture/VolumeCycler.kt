package com.benzn.grandtime.capture

import android.content.Context
import android.media.AudioManager

/** 媒体音量循环调档:25% → 50% → 75% → 100% → 25%(SMART-PTT 惯例)。 */
class VolumeCycler(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun cycle(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val steps = listOf(max / 4, max / 2, max * 3 / 4, max).filter { it > 0 }.distinct()
        val next = steps.firstOrNull { it > current } ?: steps.first()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
        return next * 100 / max
    }
}
