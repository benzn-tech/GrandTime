package com.benzn.grandtime.capture

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
 * CameraX 绑定管理。480P 时 Video+Image 双用例并绑(录像中拍照可用);
 * 720P/1080P 只绑 Video(本机双绑会被压到预览档,实测见 spec §2 v2)。
 * 所有方法须在主线程调用。
 */
class CameraSession(
    private val context: Context,
    private val lifecycleOwner: ServiceLifecycleOwner,
) {
    companion object {
        private const val TAG = "CameraSession"
    }

    var camera: Camera? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    var imageCapture: ImageCapture? = null
        private set

    private var previewUseCase: Preview? = null

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
        // 真机(F2SP,FULL 级 HAL,屏幕 480x640)实测矩阵,进程均为全新启动:
        //   Video+Image 双用例并绑,JPEG=MAXIMUM(2592x1944) → 视频被压到 640x480
        //   双用例,JPEG 限 RECORD 档(1280x960)            → 仍 640x480
        //   双用例,JPEG 限 PREVIEW 档(640x480)            → 仍 640x480
        //   只绑 Video                                       → 1920x1080 ✓
        // 即:任何双用例组合都让 CameraX 把视频钉在 PREVIEW 档(=min(屏幕,1080p)=640x480),
        // 画质设置随之失效(场景 8 无法过)。故按画质分流:
        //   480P → 双用例(视频本来就是 SD,录像中拍照可用,JPEG 限 1080p 档保画质);
        //   720P/1080P → 只绑 Video(画质优先,录像中拍照走既有降级提示)。
        // 独立拍照(bindForPhoto)不受影响,仍用 MAXIMUM。
        if (quality == VideoQuality.P480) {
            val image = ImageCapture.Builder()
                .setJpegQuality(jpegQuality)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            )
                        )
                        .build()
                )
                .build()
            try {
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, video, image)
                imageCapture = image
            } catch (e: IllegalArgumentException) {
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, video)
                imageCapture = null
            }
        } else {
            camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, video)
            imageCapture = null
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

    /**
     * 追加 Preview 用例到当前绑定,不解绑 VideoCapture/ImageCapture(录像中调用不中断录像)。
     * bindToLifecycle 对已绑用例是幂等追加,因此这里不调用 unbindAll。
     * 若设备三流(Video+Image+Preview)超限抛 IllegalArgumentException,则放弃 imageCapture,
     * 仅保留 Video+Preview 重绑。前台可见时调用。
     */
    suspend fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        val p = provider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
        try {
            camera = p.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                *listOfNotNull(videoCapture, imageCapture, preview).toTypedArray(),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "attachPreview: 三流超限,放弃 imageCapture,仅绑 Video+Preview", e)
            camera = p.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                *listOfNotNull(videoCapture, preview).toTypedArray(),
            )
            imageCapture = null
        }
        previewUseCase = preview
    }

    /** 仅解绑 Preview 用例,不影响正在进行的录像/拍照绑定。 */
    fun detachPreview() {
        val preview = previewUseCase ?: return
        runCatching { provider?.unbind(preview) }
        previewUseCase = null
    }

    suspend fun unbind() {
        provider().unbindAll()
        camera = null
        videoCapture = null
        imageCapture = null
        previewUseCase = null
    }
}
