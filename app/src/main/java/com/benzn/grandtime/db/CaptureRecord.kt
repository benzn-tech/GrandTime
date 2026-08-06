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

    /**
     * Upload failure classification — the name of a [com.benzn.grandtime.upload.FailureClass],
     * or null when the last attempt did not fail. Stored rather than derived because the
     * decision of whether to retry is made in a fresh worker process that has no memory of
     * the attempt that failed.
     */
    val failureClass: String? = null,

    /** Fingerprint the device ledger groups on, e.g. `uploadurl_403`. Null when not failed. */
    val failureCode: String? = null,

    val lastAttemptAt: Long? = null,

    /**
     * The server build in force when this record froze. The freeze happens on the upload
     * path, which carries no build, so this is the last build a status probe reported — and
     * null if none ever has. Null must NOT be read as "different from the current build":
     * that would thaw, refail and refreeze on every probe, forever.
     */
    val frozenAtBuild: String? = null,

    /** When the current freeze began; null when not frozen. */
    val frozenSinceMs: Long? = null,

    /**
     * Time already spent frozen, in ms. Credited against the give-up age so that a record
     * frozen on day 2 and thawed on day 9 still gets its retry budget. Without this,
     * freezing quietly becomes deleting — the exact loss the freeze exists to prevent.
     */
    val frozenCreditMs: Long = 0,
)
