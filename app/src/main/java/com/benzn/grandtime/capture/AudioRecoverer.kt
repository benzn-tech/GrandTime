package com.benzn.grandtime.capture

import java.io.File

/** Outcome of triaging a single orphan .pcm temp found on disk at startup. */
enum class PcmAction { RECOVER, DISCARD }

/** An orphan .pcm is RECOVERable when its sibling .wav is absent and it holds PCM data;
 *  otherwise DISCARD (empty temp, or a leftover whose .wav already exists). */
fun classifyOrphanPcm(siblingWavExists: Boolean, pcmLength: Long): PcmAction =
    if (!siblingWavExists && pcmLength > 0) PcmAction.RECOVER else PcmAction.DISCARD

/** Crash recovery for [AudioRecorder]: if the process is killed mid-recording, the streaming
 *  .pcm temp survives on disk but AudioRecorder.stop() never ran, so the target .wav was never
 *  assembled and the DB row (inserted up front by CaptureManager.startAudio) points at a file
 *  that doesn't exist. Run once at startup, before the upload rescan, to turn any such orphan
 *  .pcm back into its .wav so the rescan finds it and enqueues the upload. */
object AudioRecoverer {

    /** Scans [root] recursively for orphan .pcm temps and assembles each recoverable one into
     *  its sibling .wav (reusing AudioAssembly, same as the normal AudioRecorder.stop() path).
     *  Returns the count recovered. Empty/leftover temps are deleted, not assembled. */
    fun recover(root: File): Int {
        var recovered = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("pcm", ignoreCase = true) }
            .forEach { pcm ->
                runCatching {
                    val wav = File(pcm.parentFile, pcm.nameWithoutExtension + ".wav")
                    when (classifyOrphanPcm(wav.exists(), pcm.length())) {
                        PcmAction.RECOVER -> {
                            if (AudioAssembly.finish(pcm, wav, captureFailed = false)) recovered++
                        }
                        PcmAction.DISCARD -> pcm.delete()
                    }
                }
            }
        return recovered
    }
}
