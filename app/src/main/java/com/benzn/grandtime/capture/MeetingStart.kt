package com.benzn.grandtime.capture

/**
 * The "Start meeting" button's intent (VizField C1).
 *
 * The button lives in the UI process; the session opens in the capture service. The intent
 * crosses over the same way a meeting group does — an AppState value read once at session
 * open — but unlike a group it is a claim about ONE start, so it is timestamped and expires:
 * the button sets the flag and then asks the service to start, and if the service was not
 * running that start never happens. An unexpired flag sitting armed forever would mislabel
 * whatever hardware-key recording comes next, hours later, as a meeting.
 */
object MeetingStart {
    const val SESSION_TYPE_MEETING = "meeting"

    /** How long a pressed-but-unconsumed intent stays valid. Button→start is sub-second. */
    const val TTL_MILLIS = 10_000L

    /**
     * @return [SESSION_TYPE_MEETING] iff the button was pressed within [TTL_MILLIS] of the
     * session actually starting; null otherwise (no press, stale press, clock went backwards).
     */
    fun claim(pendingSinceMillis: Long?, nowMillis: Long): String? {
        if (pendingSinceMillis == null) return null
        val age = nowMillis - pendingSinceMillis
        return if (age in 0..TTL_MILLIS) SESSION_TYPE_MEETING else null
    }
}
