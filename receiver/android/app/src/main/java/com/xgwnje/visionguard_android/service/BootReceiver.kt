package com.xgwnje.visionguard_android.service

// ┌─────────────────────────────────────────────────────────┐
// │ BootReceiver.kt                                         │
// │ 角色：开机/更新后自动启动前台服务                          │
// └─────────────────────────────────────────────────────────┘

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val svc = Intent(context, AlertForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }
}
