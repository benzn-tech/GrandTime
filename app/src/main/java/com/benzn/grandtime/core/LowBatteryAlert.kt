package com.benzn.grandtime.core

/**
 * When the low-battery sound plays: once on reaching [ResourceThresholds.BATTERY_WARN_PCT], once
 * more on reaching [ResourceThresholds.BATTERY_CRIT_PCT], never while plugged in.
 *
 * WHY A SOUND. The only warning used to be a banner on the home screen, and the device is worn on a
 * chest harness with the screen off -- a battery running out mid-shift took the recording with it
 * and nobody saw it coming.
 *
 * WHY ONCE PER LEVEL. The battery broadcast fires on every percent and on every charging-state
 * flicker, and the level itself wobbles by a percent under load. Re-arming on "rose above the line"
 * would replay the warning each time it wobbles back, into whatever is being recorded. Only
 * plugging in re-arms it: that is the one event that means this warning was dealt with.
 *
 * Pure, so every rule is unit-tested; CoreService feeds it the battery broadcast.
 */
class LowBatteryAlert {

    /** The lowest level already warned about since the last time the device was plugged in. */
    private var warnedAt: Int? = null

    /** @return true when the sound should play for this reading. */
    fun onReading(percent: Int, pluggedIn: Boolean): Boolean {
        if (pluggedIn) {
            warnedAt = null
            return false
        }
        if (percent < 0) return false // unknown level: never warn on a reading we could not take
        val level = when {
            percent <= ResourceThresholds.BATTERY_CRIT_PCT -> ResourceThresholds.BATTERY_CRIT_PCT
            percent <= ResourceThresholds.BATTERY_WARN_PCT -> ResourceThresholds.BATTERY_WARN_PCT
            else -> return false
        }
        val previous = warnedAt
        if (previous != null && previous <= level) return false
        warnedAt = level
        return true
    }
}
