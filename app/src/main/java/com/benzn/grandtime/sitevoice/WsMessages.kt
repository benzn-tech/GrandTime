package com.benzn.grandtime.sitevoice

import org.json.JSONObject

/** A parsed inbound Site-voice push (before the clip is downloaded). */
data class InboundVoice(
    val s3Key: String,
    val senderUserId: String,
    val siteId: String?,
    val createdAt: String,
    val durationS: Int?,
)

/**
 * Pure (de)serialization for the Site-voice WS channel and clip metadata. All I/O lives in
 * [VoiceWsClient] / [SiteVoiceManager]; this stays JVM-testable.
 *
 * Contract: inbound frame {s3Key, senderUserId, siteId, createdAt[, durationS]};
 * outbound {action:"sendVoice", siteId, s3Key, durationS}.
 */
object WsMessages {
    private const val WAV_HEADER_BYTES = 44L
    private const val BYTES_PER_SAMPLE = 2 // 16-bit mono

    fun parseInbound(text: String): InboundVoice? = runCatching {
        val o = JSONObject(text)
        val s3Key = o.optString("s3Key").takeIf { it.isNotBlank() } ?: return@runCatching null
        InboundVoice(
            s3Key = s3Key,
            senderUserId = o.optString("senderUserId"),
            siteId = o.optString("siteId").takeIf { it.isNotBlank() },
            createdAt = o.optString("createdAt"),
            durationS = if (o.has("durationS") && !o.isNull("durationS")) o.optInt("durationS") else null,
        )
    }.getOrNull()

    fun sendVoiceFrame(siteId: String, s3Key: String, durationS: Int): String =
        JSONObject()
            .put("action", "sendVoice")
            .put("siteId", siteId)
            .put("s3Key", s3Key)
            .put("durationS", durationS)
            .toString()

    /** Whole seconds of a 16 kHz mono 16-bit WAV from its file size (header excluded). */
    fun wavDurationSeconds(sizeBytes: Long, sampleRate: Int = 16000): Int {
        val pcm = (sizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0)
        return (pcm / (sampleRate.toLong() * BYTES_PER_SAMPLE)).toInt()
    }
}
