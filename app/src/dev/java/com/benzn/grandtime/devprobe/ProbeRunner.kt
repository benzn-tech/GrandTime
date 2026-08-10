package com.benzn.grandtime.devprobe

import android.content.Context
import android.media.AudioManager
import com.benzn.grandtime.capture.MicCapabilities
import com.benzn.grandtime.capture.WavHeader
import com.benzn.grandtime.capture.jsonObject
import com.benzn.grandtime.capture.jsonString
import com.benzn.grandtime.capture.openMic
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
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

        for (take in takesFor(block)) {
            runCatching { recordTake(block, take, seconds, dir, onProgress) }
                .onFailure { e ->
                    // A configuration this board refuses is a result, not a crash. Record why and
                    // carry on, so one unsupported take cannot cost the whole block. Same base name
                    // as a successful take (rate included) so the analysis finds it — a refusal
                    // that never reaches the table would read as "not tested".
                    val base = "probe_${block.label}_%02d_%s_%d".format(
                        Locale.US, take.index, take.name, take.config.sampleRate
                    )
                    // recordTake renames any partially captured PCM to "$base.partial.pcm" before
                    // rethrowing, rather than deleting it — for a run that cannot be repeated,
                    // partial audio is worth more than a clean directory. Surface it if present.
                    val partial = File(dir, "$base.partial.pcm")
                    val fields = mutableListOf(
                        "block" to jsonString(block.label),
                        "index" to "${take.index}",
                        "name" to jsonString(take.name),
                        "rate" to "${take.config.sampleRate}",
                        "error" to jsonString(e.message ?: e.javaClass.simpleName),
                        // Mic contention is otherwise only checked once per block (at open time);
                        // stamping it on every take's own JSON means a take that failed or came back
                        // silent because the main app grabbed the mic mid-block is attributable
                        // instead of being misread as a configuration that simply captured nothing.
                        "micBusy" to "${isMicBusy()}",
                    )
                    if (partial.exists()) fields.add("partialPcm" to jsonString(partial.name))
                    File(dir, "$base.json").writeText(jsonObject(fields))
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
            Locale.US, take.index, take.name, take.config.sampleRate
        )
        val pcmFile = File(dir, "$base.pcm")
        try {
            val om = openMic(context, take.config)
            var reportJson = "{}"
            val stats: PcmStats
            var written = 0L
            try {
                om.record.startRecording()
                reportJson = om.reportJson() // routing is only true once recording has started
                val buf = ByteArray(om.bufferBytes)
                val target = take.config.sampleRate.toLong() * 2 * seconds
                var lastN = 0
                var peakAll = -120.0
                var sumSquares = 0.0
                var samples = 0L
                var clipped = 0L
                pcmFile.outputStream().buffered().use { out ->
                    while (written < target) {
                        lastN = om.record.read(buf, 0, buf.size)
                        if (lastN <= 0) break
                        out.write(buf, 0, lastN)
                        written += lastN
                        val s = pcmStats(buf, lastN)
                        if (s.peakDbfs > peakAll) peakAll = s.peakDbfs
                        // Accumulate energy so the whole-take RMS is exact rather than an average of
                        // per-buffer dB values, which would be wrong.
                        val chunkSamples = lastN / 2
                        sumSquares += 10.0.pow(s.rmsDbfs / 10.0) * chunkSamples
                        samples += chunkSamples
                        // Round rather than truncate: clippedFraction * chunkSamples is an integer
                        // perturbed only by float error, so truncating a value like 1.0 - epsilon
                        // silently drops isolated clipped samples and can only ever flatter a
                        // configuration against the < 0.1% pass gate.
                        clipped += Math.round(s.clippedFraction * chunkSamples)
                        onProgress(take, s.rmsDbfs)
                    }
                }
                // A negative AudioRecord.read return (ERROR_DEAD_OBJECT, ERROR_INVALID_OPERATION,
                // ERROR_BAD_VALUE) looks identical to a clean end-of-take here, so a take that dies
                // 3s in would otherwise still produce a plausible-looking level over its ~1s of real
                // audio. Fail loudly instead: this data cannot be recaptured, so a truncated take
                // must land on the error path, not the results table.
                check(written >= target / 2) {
                    "AudioRecord.read returned $lastN after $written of $target bytes"
                }
                stats = PcmStats(
                    peakDbfs = peakAll,
                    rmsDbfs = if (samples == 0L) -120.0
                              else maxOf(-120.0, 10.0 * log10(sumSquares / samples)),
                    clippedFraction = if (samples == 0L) 0.0 else clipped.toDouble() / samples,
                )
            } finally {
                om.stopAndRelease()
            }

            // Header from the take's own config: AudioAssembly's header rate is welded to 16 kHz
            // and would mislabel every 44.1 kHz take.
            val wav = File(dir, "$base.wav")
            FileOutputStream(wav).use { fos ->
                fos.write(WavHeader.riffWav(
                    pcmFile.length().toInt(), take.config.sampleRate,
                    take.config.channelCount, 16))
                pcmFile.inputStream().buffered().use { it.copyTo(fos) }
                // This device may lose power before the files are pulled; a plain `use` close only
                // flushes to the page cache, so force the WAV to durable storage before moving on.
                fos.flush()
                fos.fd.sync()
            }
            pcmFile.delete()

            File(dir, "$base.json").writeText(jsonObject(listOf(
                "block" to jsonString(block.label),
                "index" to "${take.index}",
                "name" to jsonString(take.name),
                "wav" to jsonString(wav.name),
                "seconds" to "$seconds",
                "bytes" to "$written",
                "peakDbfs" to "${stats.peakDbfs}",
                "rmsDbfs" to "${stats.rmsDbfs}",
                "clippedFraction" to "${stats.clippedFraction}",
                "micBusy" to "${isMicBusy()}",
                "mic" to reportJson,
            )))
        } catch (t: Throwable) {
            // Preserve whatever audio was captured rather than leaving (or deleting) an orphaned
            // temp file: for a run that cannot be repeated, partial audio is worth more than a
            // clean directory. This must catch every failing path in the function above, so the
            // temp PCM never survives un-renamed.
            if (pcmFile.exists()) pcmFile.renameTo(File(dir, "$base.partial.pcm"))
            throw t
        }
    }
}
