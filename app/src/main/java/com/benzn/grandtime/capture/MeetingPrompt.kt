package com.benzn.grandtime.capture

/**
 * The backup for the case the whole group mechanism otherwise fails on: nobody
 * remembers to end the meeting.
 *
 * Twenty seconds after recording stops, the device that stopped asks — out loud,
 * because by then it is back on a chest harness with the screen off — whether
 * the meeting has ended. The question is asked on the stopping device only: it
 * is the one in someone's hand, and asking every device would mean four people
 * answering the same question differently.
 */
object MeetingPrompt {

    /**
     * How long after a stop to ask.
     *
     * Long enough that an immediate restart (wrong button, resuming after a
     * false stop) cancels it before it ever speaks; short enough that the person
     * is still standing where they stopped, rather than in a truck.
     */
    const val DELAY_MILLIS = 20_000L

    /**
     * Whether a prompt scheduled earlier should still be asked now.
     *
     * Evaluated at fire time rather than schedule time, because everything that
     * makes the question wrong happens during those twenty seconds:
     *
     * - recording restarted → they paused, they did not finish; asking would
     *   interrupt live audio with a question about ending
     * - group already cleared → someone answered on another device, or the group
     *   lapsed; asking would be about a meeting that no longer exists
     * - group expired → same, on this device's own clock
     */
    fun shouldAsk(
        group: GroupExit.PendingGroup?,
        isRecording: Boolean,
        nowMillis: Long,
    ): Boolean = !isRecording && GroupExit.activeGroupId(group, nowMillis) != null
}
