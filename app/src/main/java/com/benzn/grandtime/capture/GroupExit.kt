package com.benzn.grandtime.capture

/**
 * How a device leaves a multi-device meeting group.
 *
 * The failure this exists to prevent: an inspector joins a meeting, walks off
 * still holding the group, records at another site the next day, and that audio
 * merges into yesterday's meeting. The report reads perfectly fluently, so
 * nobody notices — which is why the whole design is biased towards
 * under-merging.
 *
 * TWO actions, not one. An inspector who finishes early while the meeting
 * continues needs [Decision.I_AM_LEAVING]; if [Decision.MEETING_ENDED] were the
 * only option, using it would stop everybody else's recording. They are
 * identical for content already captured — both stay in the meeting — and
 * differ only in whether the rest of the group is told to stop.
 *
 * Both exits ask about resuming, because ending a meeting is not the same as
 * finishing work: the person may be walking to the next task, or done for the
 * day. There is no safe default, so it is asked — and asked the same way for
 * both, so this stays one behaviour instead of a special case per exit.
 */
object GroupExit {

    enum class Decision {
        /** The meeting is over — every member device should stop. */
        MEETING_ENDED,

        /** I am done here; the meeting continues without me. */
        I_AM_LEAVING,

        /** Not over — keep the group, recording resumes shortly. */
        NOT_YET,
    }

    data class Outcome(
        /** Drop this device's group membership. */
        val clearsGroup: Boolean,
        /** Tell the other member devices to stop recording. */
        val notifiesOthers: Boolean,
        /** Ask whether to start a fresh (solo) recording. */
        val asksToResume: Boolean,
    )

    fun resolve(decision: Decision): Outcome = when (decision) {
        Decision.MEETING_ENDED -> Outcome(
            clearsGroup = true, notifiesOthers = true, asksToResume = true,
        )
        Decision.I_AM_LEAVING -> Outcome(
            clearsGroup = true, notifiesOthers = false, asksToResume = true,
        )
        Decision.NOT_YET -> Outcome(
            clearsGroup = false, notifiesOthers = false, asksToResume = false,
        )
    }

    /**
     * How long a group survives with no activity when the user never answers
     * the prompt.
     *
     * Matches the backend's `SESSION_GAP_MINUTES` (15 min), that codebase's
     * definition of "one session" — deliberately NOT `STOP_GRACE_SECONDS`
     * (30 s), which is the mis-touch window. Thirty seconds would expire the
     * group during a battery swap or a walk to the next building, splitting one
     * meeting into two reports and forcing a pointless re-scan.
     */
    const val EXPIRY_MILLIS = 15L * 60_000L

    /**
     * Whether a group held since [lastStopAtMillis] should be dropped by now.
     *
     * This is a **backstop, not the mechanism**: the user's explicit answer is
     * the primary path and wins immediately. This only covers "the device went
     * into a bag and nobody answered".
     *
     * It is also **not the safety guarantee**. That lives on the server, which
     * refuses to merge a group whose members span beyond the window using its
     * OWN timestamps — because everything here trusts the device clock, and
     * these ROMs have been observed 12 hours out. A clock that jumped backwards
     * yields a negative elapsed time, which reads as "not expired" here; that is
     * acceptable precisely because the server, not this function, is what makes
     * "yesterday never merges into today" unconditional.
     */
    fun hasExpired(lastStopAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - lastStopAtMillis > EXPIRY_MILLIS
}
