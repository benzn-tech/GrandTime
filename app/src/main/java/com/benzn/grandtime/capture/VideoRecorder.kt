package com.benzn.grandtime.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File

/** 单段录像的启停。分段调度由 CaptureManager 的定时器驱动。 */
class VideoRecorder(private val context: Context) {

    private var active: Recording? = null
    val isRecording: Boolean get() = active != null

    @SuppressLint("MissingPermission") // 调用前 CaptureManager 已做运行时权限 preflight
    fun startSegment(
        videoCapture: VideoCapture<Recorder>,
        file: File,
        onFinalized: (error: Boolean, message: String?) -> Unit,
    ) {
        val pending = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        val prepared = if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) pending.withAudioEnabled() else pending

        active = prepared.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                active = null
                onFinalized(event.hasError(), if (event.hasError()) "Video error ${event.error}" else null)
            }
        }
    }

    fun stop() {
        active?.stop()
    }
}
