package com.benzn.grandtime.capture

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** 纯录音:AAC/M4A 128kbps 44.1kHz 单声道(spec §3)。 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    val isRecording: Boolean get() = recorder != null

    fun start(file: File): Boolean = try {
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        recorder?.release()
        recorder = null
        false
    }

    fun stop(): Boolean = try {
        recorder?.apply { stop(); release() }
        recorder = null
        true
    } catch (e: Exception) {
        recorder?.release()
        recorder = null
        false
    }
}
