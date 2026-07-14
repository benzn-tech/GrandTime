package com.benzn.grandtime.capture.camera2

import com.benzn.grandtime.core.AspectRatio
import com.benzn.grandtime.core.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSizeSelectorTest {
    // P1 实测 F2SP 后置 cam0 支持的视频/预览尺寸(节选,含各宽高比)
    private val supported = listOf(
        VideoSize(2592, 1944), VideoSize(1920, 1440), VideoSize(1440, 1080), VideoSize(1280, 960),
        VideoSize(960, 720), VideoSize(640, 480),                       // 4:3
        VideoSize(2560, 1440), VideoSize(1920, 1080), VideoSize(1280, 720), VideoSize(640, 360), // 16:9
    )

    @Test fun `4-3 1080 picks 1440x1080 within encoder cap`() {
        assertEquals(VideoSize(1440, 1080), VideoSizeSelector.pickSize(AspectRatio.RATIO_4_3, VideoQuality.P1080, supported))
    }

    @Test fun `16-9 1080 picks 1920x1080`() {
        assertEquals(VideoSize(1920, 1080), VideoSizeSelector.pickSize(AspectRatio.RATIO_16_9, VideoQuality.P1080, supported))
    }

    @Test fun `4-3 720 picks 960x720`() {
        assertEquals(VideoSize(960, 720), VideoSizeSelector.pickSize(AspectRatio.RATIO_4_3, VideoQuality.P720, supported))
    }

    @Test fun `16-9 720 picks 1280x720`() {
        assertEquals(VideoSize(1280, 720), VideoSizeSelector.pickSize(AspectRatio.RATIO_16_9, VideoQuality.P720, supported))
    }

    @Test fun `4-3 480 picks 640x480`() {
        assertEquals(VideoSize(640, 480), VideoSizeSelector.pickSize(AspectRatio.RATIO_4_3, VideoQuality.P480, supported))
    }

    @Test fun `never exceeds encoder cap 1920x1088`() {
        val s = VideoSizeSelector.pickSize(AspectRatio.RATIO_4_3, VideoQuality.P1080, supported)
        assertTrue(s.width <= 1920 && s.height <= 1088)
    }

    @Test fun `bitrate decreases with lower quality`() {
        assertTrue(VideoSizeSelector.bitRateFor(VideoQuality.P1080) > VideoSizeSelector.bitRateFor(VideoQuality.P720))
        assertTrue(VideoSizeSelector.bitRateFor(VideoQuality.P720) > VideoSizeSelector.bitRateFor(VideoQuality.P480))
    }
}
