package com.benzn.grandtime.capture.camera2

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.view.Surface
import java.io.File

/**
 * 单段音视频编码:相机/GL 帧经输入 Surface → MediaCodec(HEVC 优先,失败降 AVC);
 * 麦克风 AudioRecord → AAC MediaCodec;两轨合入同一 MediaMuxer(mp4)。muxerLock 串行化两线程写。
 * 分段调度由 CaptureManager 定时器驱动(每段一个新 SegmentRecorder 实例)。
 */
class SegmentRecorder(private val probe: (String) -> Unit = {}) {

    // 视频
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var videoThread: Thread? = null
    @Volatile private var draining = false
    private var videoTrack = -1

    // 音频
    private var audioEnabled = false
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var audioThread: Thread? = null
    @Volatile private var audioStopRequested = false
    private var audioTrack = -1

    // muxer(视频/音频线程共享,muxerLock 串行化 addTrack/start/writeSampleData/stop)
    private var muxer: MediaMuxer? = null
    private val muxerLock = Any()
    private var videoFormatReady = false
    private var audioFormatReady = false
    private var muxerStarted = false

    var actualCodec: String = ""
        private set

    companion object {
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BIT_RATE = 128_000
    }

    /** 配置视频编码器(+可选音频)+ muxer,返回视频编码器输入 Surface。未启动 drain(留给 start())。 */
    @SuppressLint("MissingPermission") // 调用方(CaptureManager.preflight)已确保 RECORD_AUDIO
    fun prepare(file: File, spec: VideoSpec, hevcPreferred: Boolean, location: Pair<Float, Float>?, recordAudio: Boolean): Surface {
        val enc = createEncoder(spec, hevcPreferred)
        codec = enc
        val surface = enc.createInputSurface()
        inputSurface = surface
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(spec.orientationHint)
            if (location != null) runCatching { setLocation(location.first, location.second) }
        }
        audioEnabled = recordAudio && runCatching { setupAudio() }.getOrElse {
            probe("音频初始化失败,本段仅视频: ${it.message}"); false
        }
        return surface
    }

    /** 建 AAC 编码器 + AudioRecord。任一步失败抛异常(prepare catch → 降纯视频)。 */
    private fun setupAudio(): Boolean {
        val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, 4096 * 2)
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); audioCodec?.release(); audioCodec = null
            throw IllegalStateException("AudioRecord 未初始化")
        }
        audioRecord = ar
        return true
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
            try {
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                runCatching { c.release() } // 释放半配置的编码器,避免泄漏 HW codec
                throw e
            }
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
        videoThread = Thread { videoLoop() }.apply { name = "seg-video"; start() }
        if (audioEnabled) {
            audioStopRequested = false
            runCatching { audioCodec?.start() }
            runCatching { audioRecord?.startRecording() }
            audioThread = Thread { audioLoop() }.apply { name = "seg-audio"; start() }
        }
    }

    /** 需要的轨都就绪(纯视频=仅视频轨)才启动 muxer。两线程都会调,muxerLock 保护。 */
    private fun maybeStartMuxer() {
        synchronized(muxerLock) {
            if (muxerStarted) return
            if (!videoFormatReady) return
            if (audioEnabled && !audioFormatReady) return
            muxer?.start(); muxerStarted = true
        }
    }

    private fun videoLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (videoTrack < 0) { videoTrack = muxer!!.addTrack(c.outputFormat); videoFormatReady = true }
                    }
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        synchronized(muxerLock) { if (muxerStarted) runCatching { muxer!!.writeSampleData(videoTrack, buf, info) } }
                    }
                    runCatching { c.releaseOutputBuffer(idx, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> { if (!draining) break }
            }
        }
    }

    /** 一个循环里既喂 PCM 到 AAC 输入,又把 AAC 输出写 muxer。audioStopRequested 时发 input EOS。 */
    private fun audioLoop() {
        val ar = audioRecord ?: return
        val ac = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        while (true) {
            if (!sawInputEos) {
                val inIdx = try { ac.dequeueInputBuffer(10_000) } catch (e: Exception) { break }
                if (inIdx >= 0) {
                    val inBuf = ac.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val ptsUs = System.nanoTime() / 1000  // 墙钟微秒,与视频输入面帧时戳同基准,近似 A/V 同步
                        val read = if (audioStopRequested) -1 else runCatching { ar.read(inBuf, inBuf.capacity()) }.getOrDefault(-1)
                        if (read > 0) {
                            ac.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                        } else {
                            ac.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        }
                    }
                }
            }
            val outIdx = try { ac.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxerLock) {
                    if (audioTrack < 0) { audioTrack = muxer!!.addTrack(ac.outputFormat); audioFormatReady = true }
                }
                maybeStartMuxer()
            } else if (outIdx >= 0) {
                val outBuf = ac.getOutputBuffer(outIdx)
                if (outBuf != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                    synchronized(muxerLock) { if (muxerStarted) runCatching { muxer!!.writeSampleData(audioTrack, outBuf, info) } }
                }
                runCatching { ac.releaseOutputBuffer(outIdx, false) }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    /** 结束段:音/视频各自 EOS → 两 drain 线程排空 → 关 muxer/codec/audio。幂等。 */
    fun stop() {
        if (codec == null) return
        audioStopRequested = true                        // 音频循环下次读到标志,发 input EOS
        runCatching { codec?.signalEndOfInputStream() }  // 视频 EOS
        videoThread?.join(2500)
        audioThread?.join(2500)
        if (videoThread?.isAlive == true || audioThread?.isAlive == true) {
            draining = false                             // 兜底解开视频循环(EOS 未到)
            videoThread?.join(500); audioThread?.join(500)
            if (videoThread?.isAlive == true || audioThread?.isAlive == true) probe("segment drain 线程未在超时内退出")
        }
        runCatching { audioRecord?.stop() }; runCatching { audioRecord?.release() }
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
        synchronized(muxerLock) {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null; muxer = null; inputSurface = null
        audioRecord = null; audioCodec = null
        videoThread = null; audioThread = null
        muxerStarted = false; videoTrack = -1; audioTrack = -1
        videoFormatReady = false; audioFormatReady = false
    }
}
