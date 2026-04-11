package com.xgwnje.visionguard_android

// ┌─────────────────────────────────────────────────────────┐
// │ VisionGuardApp.kt                                       │
// │ 角色：Application 类，初始化通知渠道                      │
// └─────────────────────────────────────────────────────────┘

import android.app.Application
import com.xgwnje.visionguard_android.util.NotificationHelper

class VisionGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}
