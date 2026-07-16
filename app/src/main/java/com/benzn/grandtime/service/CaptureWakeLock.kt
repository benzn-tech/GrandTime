package com.benzn.grandtime.service

import android.content.Context
import android.os.PowerManager

/**
 * A PARTIAL_WAKE_LOCK held while any capture is active, so recording keeps running when the
 * display sleeps (the screen-off timeout no longer keeps the CPU awake for us). Idempotent:
 * acquire()/release() guard on isHeld so repeated calls from the state collector are safe.
 */
class CaptureWakeLock(context: Context) {
    private val wl = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GrandTime:capture")

    fun acquire() {
        if (!wl.isHeld) wl.acquire()
    }

    fun release() {
        if (wl.isHeld) runCatching { wl.release() }
    }
}
