package com.benzn.grandtime.capture

/**
 * Wire-format naming for the mobile↔backend chunk-session contract
 * (docs/mobile-client-session-contract-design.md). The backend groups a recording session by
 * parsing two tokens out of the uploaded raw-media S3 key's base name:
 *   - `_sid{32 lowercase hex}` — the session id (backend regex `_sid([0-9a-f]{32})`)
 *   - `_c{NNNN}`               — zero-based, zero-padded chunk index within the session
 * Full grammar: `{device}_{YYYY-MM-DD}_{HH-MM-SS}_sid{32hex}_c{NNNN}.{ext}`.
 * Pure + side-effect-free so it is fully unit-testable.
 */
object ChunkNaming {

    private val HEX32 = Regex("^[0-9a-f]{32}$")

    /** Normalizes a UUID (or any id) to the backend's required 32 lowercase hex form, no hyphens. */
    fun sessionId(raw: String): String = raw.replace("-", "").lowercase()

    /**
     * Builds the wire file name sent as `UploadUrlReq.fileName` (the backend uses it as the raw-media
     * S3 key's base name). Appends `_sid{32hex}_c{NNNN}` to the local file's stem, preserving the
     * existing `{prefix}_{YYYY-MM-DD}_{HH-MM-SS}` shape (BUG-01) and the extension.
     *
     * @param localFileName on-disk name, e.g. `ben_ucpk_2026-07-29_11-01-06.mp4`
     * @param sessionId     the recording's session id (hyphens stripped + validated here)
     * @param segmentIndex  1-based capture segment index; chunk token = segmentIndex - 1
     * @return the tokenized wire name, or [localFileName] unchanged when the session id is not valid
     *         32-hex or the segment index is null/< 1 (e.g. photos). The backend treats an absent
     *         token as "no session", so the fallback is safe and back-compatible.
     */
    fun wireFileName(localFileName: String, sessionId: String?, segmentIndex: Int?): String {
        val sid = sessionId?.replace("-", "")?.lowercase()?.takeIf { it.matches(HEX32) } ?: return localFileName
        val idx = segmentIndex?.takeIf { it >= 1 } ?: return localFileName
        val dot = localFileName.lastIndexOf('.')
        val stem = if (dot >= 0) localFileName.substring(0, dot) else localFileName
        val ext = if (dot >= 0) localFileName.substring(dot) else "" // includes the leading '.'
        val chunk = (idx - 1).toString().padStart(4, '0')
        return "${stem}_sid${sid}_c${chunk}${ext}"
    }
}
