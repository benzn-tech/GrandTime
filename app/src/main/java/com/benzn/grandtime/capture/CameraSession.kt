package com.benzn.grandtime.capture

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import com.benzn.grandtime.core.VideoQuality
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX 绑定管理。录像时尝试 Video+Image 双用例并绑(录像中拍照),
 * 设备拒绝则降级只绑 Video 并记 photoDuringVideoSupported=false(spec §2)。
 * 所有方法须在主线程调用。
 */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: ServiceLifecycleOwner,
) {
    var camera: Camera? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var photoDuringVideoSupported: Boolean = true
        private set

    private var provider: ProcessCameraProvider? = null

    private suspend fun provider(): ProcessCameraProvider =
        provider ?: suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }.also { provider = it }

    private fun qualitySelector(quality: VideoQuality): QualitySelector {
        val target = when (quality) {
            VideoQuality.P1080 -> Quality.FHD
            VideoQuality.P720 -> Quality.HD
            VideoQuality.P480 -> Quality.SD
        }
        return QualitySelector.from(target, FallbackStrategy.higherQualityOrLowerThan(target))
    }

    suspend fun bindForVideo(quality: VideoQuality, jpegQuality: Int): VideoCapture<Recorder> {
        val p = provider()
        p.unbindAll()
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector(quality)).build()
        val video = VideoCapture.withOutput(recorder)
        val image = ImageCapture.Builder().setJpegQuality(jpegQuality).build()
        try {
            camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, video, image)
            imageCapture = image
            photoDuringVideoSupported = true
        } catch (e: IllegalArgumentException) {
            camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, video)
            imageCapture = null
            photoDuringVideoSupported = false
        }
        videoCapture = video
        return video
    }

    suspend fun bindForPhoto(jpegQuality: Int): ImageCapture {
        val p = provider()
        p.unbindAll()
        val image = ImageCapture.Builder().setJpegQuality(jpegQuality).build()
        camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, image)
        videoCapture = null
        imageCapture = image
        return image
    }

    suspend fun unbind() {
        provider().unbindAll()
        camera = null
        videoCapture = null
        imageCapture = null
    }
}
