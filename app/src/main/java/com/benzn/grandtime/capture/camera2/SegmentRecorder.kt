package com.benzn.grandtime.capture.camera2

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * 单段视频编码:相机/GL 帧经输入 Surface → MediaCodec(HEVC 优先,失败降 AVC)→ MediaMuxer(mp4)。
 * 分段调度由 CaptureManager 定时器驱动(每段一个新 SegmentRecorder 实例)。
 */
class SegmentRecorder(private val probe: (String) -> Unit = {}) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var draining = false
    private var trackIndex = -1
    private var muxerStarted = false

    var actualCodec: String = "hevc"
        private set

    /** 配置编码器 + muxer,返回编码器输入 Surface。未启动 drain(留给 start())。 */
    fun prepare(file: File, spec: VideoSpec, hevcPreferred: Boolean, location: Pair<Float, Float>?): Surface {
        val enc = createEncoder(spec, hevcPreferred)
        codec = enc
        val surface = enc.createInputSurface()
        inputSurface = surface
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(spec.orientationHint)
            if (location != null) runCatching { setLocation(location.first, location.second) }
        }
        return surface
    }

    /** 尝试 HEVC;configure 失败(不支持/尺寸越界)→ 降 AVC。设 actualCodec。 */
    private fun createEncoder(spec: VideoSpec, hevcPreferred: Boolean): MediaCodec {
        fun build(mime: String): MediaCodec {
            val fmt = MediaFormat.createVideoFormat(mime, spec.width, spec.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(mime)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return c
        }
        if (hevcPreferred) {
            try {
                val c = build("video/hevc"); actualCodec = "hevc"; return c
            } catch (e: Exception) {
                probe("HEVC 编码器建失败降 AVC: ${e.message}")
            }
        }
        val c = build("video/avc"); actualCodec = "h264"; return c
    }

    fun start() {
        val c = codec ?: error("prepare() first")
        c.start()
        draining = true
        drainThread = Thread { drainLoop() }.apply { name = "seg-drain"; start() }
    }

    private fun drainLoop() {
        val c = codec ?: return
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = m.addTrack(c.outputFormat); m.start(); muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && muxerStarted &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0
                    ) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        runCatching { m.writeSampleData(trackIndex, buf, info) }
                    }
                    runCatching { c.releaseOutputBuffer(idx, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> { if (!draining) break }
            }
        }
    }

    /** 结束段:标记 EOS → drain 收尾 → 关 muxer/codec。幂等。 */
    fun stop() {
        if (codec == null) return
        draining = false
        runCatching { codec?.signalEndOfInputStream() }
        drainThread?.join(2500)
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null; muxer = null; inputSurface = null; drainThread = null
        muxerStarted = false; trackIndex = -1
    }
}
