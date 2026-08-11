package com.benzn.grandtime.ui

/** What one analysed camera frame turned out to contain. */
enum class ScanFrame {
    /** No QR located — the finder patterns were not found. */
    NOTHING,

    /** A QR was located and sampled, but could not be read: too small, too blurry, or damaged.
     *  ZXing reports this as a checksum or format error rather than not-found. */
    LOCATED_UNREADABLE,

    DECODED,
}

/**
 * Turns a stream of frame outcomes into the one line of advice the operator can act on.
 *
 * The screen used to say "Scanning…" for all of these, and they call for opposite actions: aim at
 * the code, move closer to it, or go and generate a new one. The device owner worked through two
 * wrong theories — expired code, then broken autofocus — before discovering the code was simply
 * too small on screen. Both guesses were invited by a message that claimed progress in a
 * situation that had none.
 *
 * Deliberately knows nothing about sign-in. A redeem failure ("expired — generate a new one") is
 * the attempt's message and always outranks a frame hint; that precedence lives in the caller,
 * which shows `attemptMessage ?: hint`, so a hint cannot overwrite the one message that names a
 * cause the operator could not otherwise discover.
 */
class ScanHints(
    /** Consecutive located-but-unreadable frames before saying anything. ZXing's finder-pattern
     *  search accepts any three 1:1:3:1:1 blobs, so text and texture produce false positives; a
     *  single frame changing the advice would send the operator closer to a doorframe. */
    private val locatedFramesToSpeak: Int = 3,
    private val quietMillisBeforeGivingUp: Long = 15_000,
) {
    var hint: String? = null
        private set

    private var locatedRun = 0
    private var lastProgressMs = 0L
    private var seenAFrame = false

    /** Scanning started over (surface rebuilt): forget the run and restart the clock. */
    fun reset() {
        hint = null
        locatedRun = 0
        seenAFrame = false
    }

    fun onFrame(frame: ScanFrame, elapsedMs: Long = 0) {
        if (!seenAFrame) { seenAFrame = true; lastProgressMs = elapsedMs }
        when (frame) {
            ScanFrame.DECODED -> {
                // Progress. Whatever happens next belongs to the sign-in attempt.
                locatedRun = 0
                lastProgressMs = elapsedMs
                hint = null
            }
            ScanFrame.LOCATED_UNREADABLE -> {
                locatedRun++
                lastProgressMs = elapsedMs
                if (locatedRun >= locatedFramesToSpeak) hint = TOO_SMALL
            }
            ScanFrame.NOTHING -> {
                locatedRun = 0
                hint = if (elapsedMs - lastProgressMs > quietMillisBeforeGivingUp) {
                    NOTHING_FOR_A_WHILE
                } else {
                    null
                }
            }
        }
    }

    companion object {
        /** Actionable: this is the state the operator can fix by moving or by enlarging the code. */
        const val TOO_SMALL = "QR code found but not readable - move closer, or enlarge it"

        /** Vague on purpose. The client cannot know whether a code it never read has expired, so
         *  this offers the possibilities rather than asserting one. */
        const val NOTHING_FOR_A_WHILE = "Still nothing - check the code is on screen, or make a new one"
    }
}
