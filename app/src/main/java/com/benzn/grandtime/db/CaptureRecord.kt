package com.benzn.grandtime.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地元数据行,字段与未来 RDS recordings 表 1:1(spec §3.5)。
 * kind: video/audio/photo;codec: h264/aac/jpeg/frame-grab/unknown。
 */
@Entity(tableName = "capture_records")
data class CaptureRecord(
    @PrimaryKey val id: String,
    val kind: String,
    val filePath: String,
    val fileName: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long = 0,
    val codec: String,
    val resolution: String? = null,
    val segmentIndex: Int? = null,
    val sessionId: String,
    val authorSub: String? = null,
    val siteSlug: String? = null,
    val uploadStatus: String = "pending",
    val createdAt: Long,
    val missing: Boolean = false,
)
