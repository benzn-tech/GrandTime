package com.benzn.grandtime.capture.camera2

import android.util.Size
import com.benzn.grandtime.core.AspectRatio
import com.benzn.grandtime.core.VideoQuality

/** 一段录制的编码参数(尺寸/码率/方向)。 */
data class VideoSpec(
    val width: Int,
    val height: Int,
    val bitRate: Int,
    val orientationHint: Int,
)

/**
 * 从相机支持尺寸里选编码尺寸:精确匹配宽高比、≤ 硬编上限(1920×1088)、
 * 高度就近 quality 目标(优先 ≤ 目标的最大档,无则取 > 目标里最小档)。纯函数,JVM 可测。
 */
object VideoSizeSelector {
    const val ENCODER_MAX_W = 1920
    const val ENCODER_MAX_H = 1088

    private fun targetHeight(q: VideoQuality) = when (q) {
        VideoQuality.P1080 -> 1080
        VideoQuality.P720 -> 720
        VideoQuality.P480 -> 480
    }

    fun bitRateFor(q: VideoQuality) = when (q) {
        VideoQuality.P1080 -> 20_000_000
        VideoQuality.P720 -> 10_000_000
        VideoQuality.P480 -> 6_000_000
    }

    fun pickSize(aspect: AspectRatio, quality: VideoQuality, supported: List<Size>): Size {
        val (aw, ah) = when (aspect) {
            AspectRatio.RATIO_4_3 -> 4 to 3
            AspectRatio.RATIO_16_9 -> 16 to 9
        }
        val target = targetHeight(quality)
        val inCap = supported.filter { it.width <= ENCODER_MAX_W && it.height <= ENCODER_MAX_H }
        val exact = inCap.filter { it.width * ah == it.height * aw }.sortedByDescending { it.height }
        if (exact.isNotEmpty()) {
            return exact.firstOrNull { it.height <= target } ?: exact.last()
        }
        // 兜底:无精确宽高比档时,取上限内、高度最接近目标的任意档(理论上不该发生)。
        return inCap.minByOrNull { kotlin.math.abs(it.height - target) } ?: Size(1280, 720)
    }
}
