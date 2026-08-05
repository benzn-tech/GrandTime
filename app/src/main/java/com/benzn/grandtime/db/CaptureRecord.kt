package com.benzn.grandtime.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地元数据行,字段与未来 RDS recordings 表 1:1(spec §3.5)。
 * kind: video/audio/photo;codec: hevc/h264/jpeg/aac/unknown。
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
    val siteId: String? = null,
    val uploadStatus: String = "pending",
    val createdAt: Long,
    val gpsTrack: String? = null,
    val missing: Boolean = false,
    // 多设备合并(spec 2026-08-04):主设备的 session id。
    // 持久化而不是"只发出去",是因为 /open 是 best-effort、失败即丢,而工地离线是常态。
    // 组关系一旦丢失无法事后重建,所以它必须跟着录音行走。null = 单机录制。
    val groupId: String? = null,
)
