package com.benzn.grandtime.core

/**
 * Maps the app's "Auto screen-off" setting (minutes; 0 = Never) to the value written to the
 * system Settings.System.SCREEN_OFF_TIMEOUT (milliseconds). 0/negative -> Int.MAX_VALUE, which
 * the OS treats as effectively never sleeping.
 */
fun screenOffTimeoutMillis(minutes: Int): Int =
    if (minutes <= 0) Int.MAX_VALUE else minutes * 60_000
