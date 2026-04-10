package com.xgwnje.visionguard_android.data.model

// ┌─────────────────────────────────────────────────────────┐
// │ DeviceInfo.kt                                           │
// │ 角色：Windows 设备状态数据类                              │
// │ 来源：服务器 WS "device-list" 消息                       │
// └─────────────────────────────────────────────────────────┘

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val online: Boolean,
    val isMonitoring: Boolean,
    val isAlarming: Boolean,
    val isReady: Boolean,
    val lastSeen: String,
    val cooldown: Int = 5,
    val confidence: Double = 0.45,
    val targets: String = ""
)

/** 记录每台设备最后下发的参数配置（Android 端本地缓存） */
data class DeviceConfig(
    val cooldown: Int = 5,           // 秒
    val confidence: Double = 0.45,   // 0.10–0.95
    val targets: String = ""         // 英文逗号分隔，如 "person,car"
)
