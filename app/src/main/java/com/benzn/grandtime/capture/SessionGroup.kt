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

    sealed interface Scan {
        data class Ok(val groupId: String) : Scan

        /**
         * A meeting code, but from a device on the other build.
         *
         * Kept distinct from [NotAMeetingCode] because the two need opposite
         * advice: scan again, versus match the builds. Without the distinction
         * the user would keep re-scanning a code that can never work.
         */
        data object WrongEnvironment : Scan

        data object NotAMeetingCode : Scan
    }

    /** What the lead device renders as a QR code. */
    fun format(sessionId: String, env: String): String = "$PREFIX$env:$sessionId"

    /**
     * Read a scanned code, refusing one from a different environment.
     *
     * The environment tag is not decoration. dev and prod builds talk to
     * different gateways and different DATABASES, so without it a cross-build
     * scan succeeds, both devices record happily, the two halves of the group
     * land in two separate databases, and nothing ever merges — with no error
     * anywhere to explain it. That is the worst shape a failure can take here,
     * and it costs one token to make impossible. The login QR already refuses a
     * cross-environment code for the same reason.
     */
    fun parse(raw: String?, env: String): Scan {
        // Some scanners append a newline; trimming is not leniency about the
        // format, only about the transport.
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(PREFIX)) return Scan.NotAMeetingCode
        val rest = text.removePrefix(PREFIX)
        val codeEnv = rest.substringBefore(':', missingDelimiterValue = "")
        val sid = rest.substringAfter(':', missingDelimiterValue = "").trim()
        if (codeEnv.isEmpty() || !SID.matches(sid)) return Scan.NotAMeetingCode
        // Shape checked BEFORE the environment, so a corrupt code reads as
        // corrupt rather than as someone else's build.
        return if (codeEnv == env) Scan.Ok(sid) else Scan.WrongEnvironment
    }
}
