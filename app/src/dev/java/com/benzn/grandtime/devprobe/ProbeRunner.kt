package com.benzn.grandtime.devprobe

import android.content.Context
import android.media.AudioManager
import com.benzn.grandtime.capture.MicCapabilities
import com.benzn.grandtime.capture.WavHeader
import com.benzn.grandtime.capture.jsonObject
import com.benzn.grandtime.capture.jsonString
import com.benzn.grandtime.capture.openMic
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs one block of the probe: every configuration in PROBE_TAKES, back to back, with no
 * operator input between takes so posture cannot drift mid-block.
 *
 * Writes only to app-specific external storage. It never inserts a Room row, enqueues a
 * WorkManager job, or calls the recordings API — the dev flavor points at the test stack, and
 * pushing deliberate noise into the test lake would pollute real data.
 */
class ProbeRunner(private val context: Context) {

    /** A second AudioRecord initializes fine and returns silence under concurrent-capture policy,
     *  so AudioRecord state cannot detect contention. Ask the framework who is recording. */
    fun isMicBusy(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return runCatching { am.activeRecordingConfigurations.isNotEmpty() }.getOrDefault(false)
    }

    suspend fun runBlock(
        block: ProbeBlock,
        seconds: Int = 10,
        gapMs: Long = 3000,
        onProgress: (ProbeTake, Double) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "audioprobe/${stamp}_block${block.label}")
        dir.mkdirs()
        File(dir, "capabilities.json").writeText(MicCapabilities.snapshotJson(context))

        for (take in PROBE_TAKES) {
            runCatching { recordTake(block, take, seconds, dir, onProgress) }
                .onFailure { e ->
                    // A configuration this board refuses is a result, not a crash. Record why and
                    // carry on, so one unsupported take cannot cost the whole block. Same base name
                    // as a successful take (rate included) so the analysis finds it — a refusal
                    // that never reaches the table would read as "not tested".
                    val base = "probe_${block.label}_%02d_%s_%d".format(
                        take.index, take.name, take.config.sampleRate
                    )
                    File(dir, "$base.json").writeText(jsonObject(listOf(
                        "block" to jsonString(block.label),
                        "index" to "${take.index}",
                        "name" to jsonString(take.name),
                        "rate" to "${take.config.sampleRate}",
                        "error" to jsonString(e.message ?: e.javaClass.simpleName),
                    )))
                }
            delay(gapMs)
        }
        dir
    }

    private fun recordTake(
        block: ProbeBlock,
        take: ProbeTake,
        seconds: Int,
        dir: File,
        onProgress: (ProbeTake, Double) -> Unit,
    ) {
        val base = "probe_${block.label}_%02d_%s_%d".format(
            take.index, take.name, take.config.sampleRate
        )
        val pcmFile = File(dir, "$base.pcm")
        val om = openMic(context, take.config)
        var reportJson = "{}"
        var stats: PcmStats
        try {
            om.record.startRecording()
            reportJson = om.reportJson() // routing is only true once recording has started
            val buf = ByteArray(om.bufferBytes)
            val target = take.config.sampleRate.toLong() * 2 * seconds
            var written = 0L
            var peakAll = -120.0
            var sumSquares = 0.0
            var samples = 0L
            var clipped = 0L
            pcmFile.outputStream().buffered().use { out ->
                while (written < target) {
                    val n = om.record.read(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    val s = pcmStats(buf, n)
                    if (s.peakDbfs > peakAll) peakAll = s.peakDbfs
                    // Accumulate energy so the whole-take RMS is exact rather than an average of
                    // per-buffer dB values, which would be wrong.
                    val chunkSamples = n / 2
                    sumSquares += Math.pow(10.0, s.rmsDbfs / 10.0) * chunkSamples
                    samples += chunkSamples
                    clipped += (s.clippedFraction * chunkSamples).toLong()
                    onProgress(take, s.rmsDbfs)
                }
            }
            stats = PcmStats(
                peakDbfs = peakAll,
                rmsDbfs = if (samples == 0L) -120.0
                          else maxOf(-120.0, 10.0 * Math.log10(sumSquares / samples)),
                clippedFraction = if (samples == 0L) 0.0 else clipped.toDouble() / samples,
            )
        } finally {
            om.stopAndRelease()
        }

        // Header from the take's own config: AudioAssembly's header rate is welded to 16 kHz and
        // would mislabel every 44.1 kHz take.
        val wav = File(dir, "$base.wav")
        wav.outputStream().buffered().use { out ->
            out.write(WavHeader.riffWav(pcmFile.length().toInt(), take.config.sampleRate, 1, 16))
            pcmFile.inputStream().buffered().use { it.copyTo(out) }
        }
        pcmFile.delete()

        File(dir, "$base.json").writeText(jsonObject(listOf(
            "block" to jsonString(block.label),
            "index" to "${take.index}",
            "name" to jsonString(take.name),
            "wav" to jsonString(wav.name),
            "seconds" to "$seconds",
            "peakDbfs" to "${stats.peakDbfs}",
            "rmsDbfs" to "${stats.rmsDbfs}",
            "clippedFraction" to "${stats.clippedFraction}",
            "mic" to reportJson,
        )))
    }
}
