package com.benzn.grandtime.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benzn.grandtime.service.CoreService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 开机自动进 Home:标记 from_boot,由 CoreService 在前台化后延时拉起 MainActivity。
            // 不在此 Receiver 里直接 startActivity——开机早期广播上下文拉 Activity 会被系统静默丢弃
            // (与桌面启动抢窗口的竞态);前台服务延时拉起更可靠。
            context.startForegroundService(
                Intent(context, CoreService::class.java)
                    .putExtra(CoreService.EXTRA_FROM_BOOT, true),
            )
        }
    }
}
