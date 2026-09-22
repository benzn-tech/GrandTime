package com.benzn.grandtime.ui

/**
 * One timeline across a recording's segments, so a multi-part recording plays -- and scrubs -- as if
 * it were a single file.
 *
 * Nothing is merged on disk. The player holds the segments as a playlist; this maps between
 * "position inside segment i" and "position in the whole recording", which is what lets one bar run
 * from the start of segment 1 to the end of the last one.
 *
 * Unknown lengths are a real case, not a theoretical one: a row reconciled from disk can have no
 * duration, and a segment the player has not opened yet reports none either. They count as zero for
 * the mapping and [seekable] says the bar cannot be dragged, rather than letting a drag land
 * somewhere arbitrary.
 *
 * Pure, so every rule is unit-tested; RecordingPlayerSheet drives ExoPlayer with it.
 */
object PlaybackTimeline {

    /** Where a whole-recording position lands: which segment, and how far into it. */
    data class Seek(val segmentIndex: Int, val positionMs: Long)

    fun total(durationsMs: List<Long>): Long = durationsMs.sumOf { it.coerceAtLeast(0L) }

    /** Every segment's length is known -- what dragging across the whole recording needs. */
    fun seekable(durationsMs: List<Long>): Boolean = durationsMs.isNotEmpty() && durationsMs.all { it > 0 }

    /** Position in the whole recording, given a position inside one segment. */
    fun globalPosition(segmentIndex: Int, positionMs: Long, durationsMs: List<Long>): Long {
        if (durationsMs.isEmpty()) return 0L
        val index = segmentIndex.coerceIn(0, durationsMs.lastIndex)
        val before = durationsMs.take(index).sumOf { it.coerceAtLeast(0L) }
        val own = durationsMs[index]
        val inside = positionMs.coerceAtLeast(0L).let { if (own > 0) it.coerceAtMost(own) else it }
        return before + inside
    }

    /** The inverse: which segment to seek to, and where inside it. Clamped at both ends. */
    fun locate(globalMs: Long, durationsMs: List<Long>): Seek {
        if (durationsMs.isEmpty()) return Seek(0, 0L)
        var remaining = globalMs.coerceAtLeast(0L)
        for ((index, duration) in durationsMs.withIndex()) {
            val own = duration.coerceAtLeast(0L)
            if (remaining < own || index == durationsMs.lastIndex) {
                return Seek(index, if (own > 0) remaining.coerceAtMost(own) else remaining)
            }
            remaining -= own
        }
        return Seek(durationsMs.lastIndex, 0L)
    }
}
