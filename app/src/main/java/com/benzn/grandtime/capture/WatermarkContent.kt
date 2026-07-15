package com.benzn.grandtime.capture

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 水印内容:若干行文本(缺项已省略)。 */
data class WatermarkContent(val lines: List<String>)

object Watermark {
    private val TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 组装 4 行:用户名(有才加)/ 时间戳 / 经纬度(无定位=占位)/ 街道地址(有才加)。 */
    fun build(
        userName: String?,
        epochMillis: Long,
        lat: Double?,
        lon: Double?,
        address: String?,
        zone: ZoneId,
        noFixText: String = "Locating…",
    ): WatermarkContent {
        val lines = ArrayList<String>(4)
        if (!userName.isNullOrBlank()) lines += userName
        lines += TIME_FMT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
        if (lat != null && lon != null) {
            lines += "%.4f, %.4f".format(Locale.US, lat, lon)
            if (!address.isNullOrBlank()) lines += address
        } else {
            lines += noFixText
        }
        return WatermarkContent(lines)
    }
}
