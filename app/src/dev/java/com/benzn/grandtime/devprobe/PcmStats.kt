package com.benzn.grandtime.devprobe

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Level summary of a PCM16 mono buffer. dBFS relative to full scale (32768). */
data class PcmStats(val peakDbfs: Double, val rmsDbfs: Double, val clippedFraction: Double)

private const val FULL_SCALE = 32768.0
private const val FLOOR_DBFS = -120.0
private const val CLIP_THRESHOLD = 0.98

/** Computes peak, RMS and clipping over the first [len] bytes of little-endian PCM16 mono.
 *  Digital silence returns [FLOOR_DBFS] rather than -Infinity so the JSON stays parseable. */
fun pcmStats(pcm: ByteArray, len: Int): PcmStats {
    val samples = minOf(len, pcm.size) / 2
    if (samples == 0) return PcmStats(FLOOR_DBFS, FLOOR_DBFS, 0.0)
    var peak = 0.0
    var sumSquares = 0.0
    var clipped = 0
    for (i in 0 until samples) {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()          // sign-extends, giving the signed 16-bit value
        val v = ((hi shl 8) or lo).toShort().toDouble() / FULL_SCALE
        val a = abs(v)
        if (a > peak) peak = a
        if (a >= CLIP_THRESHOLD) clipped++
        sumSquares += v * v
    }
    val rms = sqrt(sumSquares / samples)
    return PcmStats(
        peakDbfs = toDbfs(peak),
        rmsDbfs = toDbfs(rms),
        clippedFraction = clipped.toDouble() / samples,
    )
}

private fun toDbfs(linear: Double): Double =
    if (linear <= 0.0) FLOOR_DBFS else maxOf(FLOOR_DBFS, 20.0 * log10(linear))
