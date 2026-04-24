package com.xgwnje.visionguard

import android.app.Application
import com.xgwnje.visionguard.util.NotificationHelper

class VisionGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}
