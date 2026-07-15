package com.benzn.grandtime.capture.camera2

/**
 * 水印叠加带的高度分数(纯函数,JVM 可测)。
 *
 * 背景:WatermarkRenderer 按内容行数输出高度自适应的位图(如 640×81),若 GL 侧固定用一个
 * 常数带高比例去画,位图会被非等比拉伸变形(横纵轴缩放系数不一致)。这里反过来:按位图自身
 * 宽高比,结合播放画面尺寸,反推出应占的带高比例,使横纵轴缩放系数相等。
 */
object WatermarkGeometry {
    /**
     * @param bmpW/bmpH 水印位图像素尺寸(WatermarkRenderer 输出)
     * @param encW/encH 编码帧尺寸(GlRecordPipeline.start 的 cameraW/cameraH)
     * @param rotationDeg 播放旋转提示(WM_ROTATION_DEG);90/270 时播放画面宽高相对编码帧对调
     * @return 带高度占播放画面高度的比例,∈(0f, 1f]
     */
    fun bandFraction(bmpW: Int, bmpH: Int, encW: Int, encH: Int, rotationDeg: Int): Float {
        val normalizedRot = ((rotationDeg % 360) + 360) % 360
        val swapped = normalizedRot == 90 || normalizedRot == 270
        val playW = if (swapped) encH else encW
        val playH = if (swapped) encW else encH
        if (bmpW <= 0 || bmpH <= 0 || playW <= 0 || playH <= 0) return 0.01f
        // 带铺满播放画面整宽,带高按位图纵横比换算(playW * bmpH / bmpW),再转成占播放高的比例。
        val bandHeightPlay = playW.toFloat() * bmpH / bmpW
        val b = bandHeightPlay / playH
        return b.coerceAtMost(1f).coerceAtLeast(0.0001f)
    }
}
