package com.benzn.grandtime.ui

/**
 * Where to put the lens next, when the camera will not focus itself.
 *
 * Measured on F2S202503103060 (MediaTek F2SP, camera 0): the camera advertises AUTO, MACRO and
 * both CONTINUOUS autofocus modes and a minimum focus distance of 20 dioptres (5cm), and none of
 * it runs. With CONTINUOUS_PICTURE requested, `CONTROL_AF_STATE` stayed INACTIVE and
 * `LENS_FOCUS_DISTANCE` stayed 0.0 — infinity — through four explicit AF_TRIGGER_START calls. The
 * HAL simply does not drive the lens on this path. Hyperfocal is 3m, so everything nearer than
 * about 1.5m is soft, which is exactly the reported symptom: a code held close is blurred, and by
 * the distance it looks sharp there are too few pixels per module to read it.
 *
 * So the lens is placed by hand. `focusDistanceCalibration` is UNCALIBRATED, meaning the dioptre
 * numbers are not trustworthy as distances — only their order is — so a single "correct" value
 * cannot be computed. Stepping through a ladder and stopping on the first frame that decodes needs
 * no calibration at all.
 *
 * Infinity is first because it is where the lens already sits: a code that works today keeps
 * working, and the sweep only costs anything when the old behaviour was going to fail.
 */
class FocusSweep(
    /** Dioptres: 0 is infinity, 20 is this lens's 5cm limit. Roughly infinity, 50cm, 25cm, 15cm,
     *  10cm, 7cm — spaced so a code is within depth of field of at least one rung. */
    private val ladder: List<Float> = DEFAULT_LADDER,
    /** Long enough for the lens to settle and for a frame to be analysed at the new position.
     *  Too short and the sweep outruns the pipeline, decoding every frame mid-travel. */
    private val dwellMs: Long = 700,
    /** Frames of CONTROL_AF_STATE == INACTIVE before giving up on the camera's own autofocus.
     *  At ~16fps this is about a second — long enough that a camera merely between hunts is not
     *  mistaken for one that never hunts. */
    private val takeOverAfterInactiveFrames: Int = 16,
) {
    private var index = 0
    private var inactiveRun = 0
    private var lastStepMs = 0L
    // Not `lastStepMs == 0L`: a frame can legitimately arrive at time zero, and the sentinel
    // would then swallow the first step. A test caught exactly that.
    private var started = false
    private var settled = false

    /** The position the capture request should currently ask for. */
    val position: Float get() = ladder[index]

    /** True once a frame has decoded — the sweep holds there and stops moving. */
    val isSettled: Boolean get() = settled

    /**
     * Whether the lens is ours to place.
     *
     * This is measured, never assumed. On F2S202503103060 autofocus never left INACTIVE, but this
     * is a fleet of twenty terminals and the next one's camera may work perfectly. Switching to
     * manual focus unconditionally would break the ones that were fine — a fix for one device
     * shipped as a regression for the rest.
     */
    val hasTakenOver: Boolean get() = inactiveRun >= takeOverAfterInactiveFrames

    /**
     * Report one autofocus state. Returns true on the single frame where control passes to us, so
     * the caller can switch the capture request to AF_MODE_OFF exactly once.
     */
    fun noteAf(inactive: Boolean): Boolean {
        if (!inactive) {
            // The camera is doing its job. Anything it does counts as evidence, so one good state
            // undoes the whole run rather than decrementing.
            inactiveRun = 0
            return false
        }
        if (hasTakenOver) return false
        inactiveRun++
        return hasTakenOver
    }

    /**
     * Report one analysed frame. Returns true when the lens should be moved, i.e. when the caller
     * needs to submit a new repeating request.
     */
    fun onFrame(decoded: Boolean, nowMs: Long): Boolean {
        if (decoded) {
            // Hold. Re-scanning a second code from the same distance should not start over.
            settled = true
            return false
        }
        if (settled) return false
        // Nothing to do while the camera is still focusing itself.
        if (!hasTakenOver) return false
        if (!started) { started = true; lastStepMs = nowMs; return false }
        if (nowMs - lastStepMs < dwellMs) return false
        lastStepMs = nowMs
        index = (index + 1) % ladder.size
        return true
    }

    /** A new scanning session: start from infinity again. */
    fun reset() {
        index = 0
        inactiveRun = 0
        lastStepMs = 0L
        started = false
        settled = false
    }

    companion object {
        val DEFAULT_LADDER = listOf(0f, 2f, 4f, 6.5f, 10f, 14f)
    }
}
