package com.benzn.grandtime.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benzn.grandtime.service.CoreService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, CoreService::class.java))
        }
    }
}
