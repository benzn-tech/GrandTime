package com.benzn.grandtime.service

import android.content.Context
import android.provider.Settings
import com.benzn.grandtime.core.screenOffTimeoutMillis

/**
 * Writes the system screen-off timeout to match the app's "Auto screen-off" setting, so the OS
 * sleeps the display naturally (recording and idle). Requires the special WRITE_SETTINGS
 * permission; when it isn't granted this is a no-op returning false (the UI surfaces the grant).
 */
object ScreenTimeoutController {
    fun apply(context: Context, minutes: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                screenOffTimeoutMillis(minutes),
            )
        }.getOrDefault(false)
    }
}
