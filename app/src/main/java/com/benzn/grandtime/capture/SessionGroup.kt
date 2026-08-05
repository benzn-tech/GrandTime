package com.benzn.grandtime.capture

/**
 * The join payload for a multi-device meeting.
 *
 * The group's identity IS the lead device's session id. Nothing has to be
 * allocated, so a group can form with **no network at all** — which is the whole
 * reason joining is a QR scan rather than a server-issued token. Site
 * connectivity is unreliable, and a design that made group formation require a
 * round-trip would throw that away.
 *
 * The `fs1:` prefix is a namespace guard, and it earns its place: this app also
 * scans a LOGIN qr, parsed by [com.benzn.grandtime.auth.QrLoginParser]. Scanning
 * the wrong code must fail cleanly rather than produce a nonsense group, because
 * a wrong group is worse than no group — it merges two unrelated meetings into
 * one report and delivers it to both sets of people.
 *
 * The id must be exactly the backend's shape (32 lowercase hex, `_SID_RE` in
 * lambda_org_api). Validating here means a mis-scan fails on the device, where
 * the user can simply scan again, instead of becoming a 400 after recording has
 * already started.
 */
object SessionGroup {
    private const val PREFIX = "fs1:"
    private val SID = Regex("^[0-9a-f]{32}$")

    /** What the lead device renders as a QR code. */
    fun format(sessionId: String): String = PREFIX + sessionId

    /** The group id carried by [raw], or null if this is not one of our join codes. */
    fun parse(raw: String?): String? {
        // Some scanners append a newline; trimming is not leniency about the
        // format, only about the transport.
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(PREFIX)) return null
        val sid = text.removePrefix(PREFIX).trim()
        return if (SID.matches(sid)) sid else null
    }
}
