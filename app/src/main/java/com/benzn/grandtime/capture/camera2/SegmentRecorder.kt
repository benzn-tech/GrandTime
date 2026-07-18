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
    // Non-terminal handover flag: when true the audio loop zero-fills (paced silence) instead of
    // reading the mic. DISTINCT from the terminal audioStopRequested (which queues EOS + ends the
    // track). Only the AudioRecord is released/reopened; the AAC codec + muxer track stay alive.
    @Volatile private var audioHandover = false
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

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
        // Silence pacing: feed exactly ONE AAC frame of PCM16 mono silence per sleep (1024 samples ×
        // 2 bytes). The loop drains one encoder output buffer per iteration, so a one-frame chunk
        // keeps at most ~1 frame undrained per sleep — robust on devices with a small AAC output
        // pool (≤4) that a full mic-buffer (~4 frames) per sleep could stall. Real-time paced: one
        // frame of silence per frame-duration.
        private const val SILENCE_CHUNK_BYTES = 2048
        private const val SILENCE_SLEEP_MS = SILENCE_CHUNK_BYTES / 2 * 1000L / AUDIO_SAMPLE_RATE // 1024/44100 s ≈ 23 ms
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
        val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            ac.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            runCatching { ac.release() } // 释放半配置的 AAC 编码器,避免泄漏
            throw e
        }
        audioCodec = ac
        audioRecord = try {
            buildMic()
        } catch (e: Exception) {
            runCatching { audioCodec?.release() }; audioCodec = null // 释放已建 AAC,避免泄漏
            throw e
        }
        return true
    }

    /** Build a MIC AudioRecord with the fixed capture params. Throws if not INITIALIZED. Silence
     *  pacing uses a fixed one-AAC-frame chunk (SILENCE_CHUNK_BYTES), so the mic buffer size is only
     *  used to size the AudioRecord itself. */
    @SuppressLint("MissingPermission") // 调用方(prepare/resumeAudio)已确保 RECORD_AUDIO
    private fun buildMic(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, 4096 * 2)
        val ar = AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); throw IllegalStateException("AudioRecord 未初始化")
        }
        return ar
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

    fun start(startAudioPaused: Boolean = false) {
        val c = codec ?: error("prepare() first")
        c.start()
        draining = true
        videoThread = Thread { videoLoop() }.apply { name = "seg-video"; start() }
        if (!audioEnabled) return
        audioStopRequested = false
        if (startAudioPaused) {
            // Segment rollover happened mid-handover: keep the AAC codec + muxer audio track alive
            // but do NOT open the mic. The loop zero-fills (silent) until resumeAudio() opens one.
            audioHandover = true
            val started = runCatching {
                audioCodec?.start()
                runCatching { audioRecord?.release() } // free the mic setupAudio() built; loop is silent
                audioRecord = null
                true
            }.getOrDefault(false)
            if (started) {
                audioThread = Thread { audioLoop() }.apply { name = "seg-audio"; start() }
            } else {
                probe("音频启动失败(paused),本段降为纯视频")
                audioEnabled = false
                runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
                audioRecord = null; audioCodec = null
            }
            return
        }
        val audioStarted = runCatching {
            audioCodec?.start()
            audioRecord?.startRecording()
            audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrDefault(false)
        if (audioStarted) {
            audioThread = Thread { audioLoop() }.apply { name = "seg-audio"; start() }
        } else {
            probe("音频启动失败,本段降为纯视频")
            audioEnabled = false   // maybeStartMuxer 改为仅凭视频轨启动,避免整段无输出
            runCatching { audioRecord?.stop() }; runCatching { audioRecord?.release() }
            runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
            audioRecord = null; audioCodec = null
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

    /** 一个循环里既喂 PCM 到 AAC 输入,又把 AAC 输出写 muxer。仅 audioStopRequested 发 input EOS;
     *  audioHandover / 麦克风缺失 时喂静音(非终态)。麦克风句柄每轮从字段现取,故 resumeAudio 重开后被拾起。 */
    private fun audioLoop() {
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
                        if (audioStopRequested) {             // terminal only: end the AAC input stream
                            ac.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val ar = audioRecord              // read field fresh: may be null mid-handover
                            val n = if (audioHandover || ar == null) {
                                fillSilence(inBuf)            // paced silence, non-terminal
                            } else {
                                val r = runCatching { ar.read(inBuf, inBuf.capacity()) }.getOrDefault(0)
                                if (r > 0) r else fillSilence(inBuf) // transient <=0 → silence, NOT EOS
                            }
                            ac.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
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

    /** Write one AAC frame of PCM silence into [buf] (reused codec buffers may hold stale audio) and
     *  pace the loop ~one frame-duration so PTS advances at the real-time cadence and the encoder is
     *  neither flooded with near-identical timestamps nor stalled by an undrained backlog. Returns
     *  the byte count written. */
    private fun fillSilence(buf: java.nio.ByteBuffer): Int {
        val n = minOf(SILENCE_CHUNK_BYTES, buf.capacity()).coerceAtLeast(2)
        buf.position(0)
        repeat(n) { buf.put(0.toByte()) }
        runCatching { Thread.sleep(SILENCE_SLEEP_MS) }
        return n
    }

    /** Site-voice is borrowing the mic: go silent (non-terminal) then free the mic hardware. The
     *  AAC codec + muxer audio track stay alive; the loop zero-fills until resumeAudio(). The flag
     *  is set BEFORE the release so the loop takes the silence branch and never touches a released
     *  handle. Idempotent; no-op if audio was never enabled. */
    fun pauseAudioForHandover() {
        if (!audioEnabled) return
        audioHandover = true
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    /** End the handover: open a fresh mic (same params) and resume real audio. Publishes the new
     *  handle to the field BEFORE clearing the flag, so the loop never sees handover=false with a
     *  null mic. If the rebuild fails, leave audioHandover=true (track stays silent for the rest of
     *  the segment), log, and return false — NEVER crash the recording. No-op/true if not enabled. */
    fun resumeAudio(): Boolean {
        if (!audioEnabled) return true
        return runCatching {
            val ar = buildMic()
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                ar.release(); throw IllegalStateException("AudioRecord 未进入录制")
            }
            audioRecord = ar
            audioHandover = false
            true
        }.getOrElse {
            probe("resumeAudio 失败,本段音频保持静音: ${it.message}")
            audioHandover = true
            false
        }
    }

    /** 结束段:音/视频各自 EOS → 两 drain 线程排空 → 关 muxer/codec/audio。幂等。 */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        if (codec == null) return
        audioStopRequested = true                        // 音频循环下次读到标志,发 input EOS
        runCatching { audioRecord?.stop() }              // 先停录音,解开可能阻塞的 read
        runCatching { codec?.signalEndOfInputStream() }  // 视频 EOS
        videoThread?.join(2500)
        audioThread?.join(2500)
        if (videoThread?.isAlive == true || audioThread?.isAlive == true) {
            draining = false                             // 兜底解开视频循环(EOS 未到)
            videoThread?.join(500); audioThread?.join(500)
            if (videoThread?.isAlive == true || audioThread?.isAlive == true) probe("segment drain 线程未在超时内退出")
        }
        runCatching { audioRecord?.release() }            // release 放到 join 之后,避免 use-after-release
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
