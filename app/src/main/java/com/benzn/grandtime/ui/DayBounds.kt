package com.benzn.grandtime.ui

import java.time.Instant
import java.time.ZoneId

/** Epoch millis of the most recent local midnight at or before [nowMs], in [zone]. */
fun startOfDayMillis(nowMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
