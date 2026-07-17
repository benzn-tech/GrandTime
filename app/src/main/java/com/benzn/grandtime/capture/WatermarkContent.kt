package com.benzn.grandtime.capture

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 水印内容:若干行文本(缺项已省略)。 */
data class WatermarkContent(val lines: List<String>)

object Watermark {
    private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Assembles up to 4 lines: username (if present) / timestamp / lat-lon or no-fix placeholder /
     * address-or-site line. The address/site line is appended whenever non-blank, independent of
     * fix availability (indoor anchor) — indoors with no GPS fix it is the only location evidence;
     * outdoors it complements the coordinates.
     *
     * fixAgeLabel: when non-null, the coords are a stale fallback fix (indoor GPS fallback) and
     * the label ("~Nm ago") is appended in parentheses so a stale fix is never mistaken for a
     * current one — evidence integrity for the watermark.
     */
    fun build(
        userName: String?,
        epochMillis: Long,
        lat: Double?,
        lon: Double?,
        address: String?,
        zone: ZoneId,
        noFixText: String = "Locating…",
        fixAgeLabel: String? = null,
    ): WatermarkContent {
        val lines = ArrayList<String>(4)
        if (!userName.isNullOrBlank()) lines += userName
        lines += TIME_FMT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
        if (lat != null && lon != null) {
            val coords = "%.4f, %.4f".format(Locale.US, lat, lon)
            lines += if (fixAgeLabel != null) "$coords ($fixAgeLabel)" else coords
        } else {
            lines += noFixText
        }
        if (!address.isNullOrBlank()) lines += address
        return WatermarkContent(lines)
    }
}
