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
    val isReady: Boolean,           // 选区是否已设定
    val lastSeen: String            // ISO 8601
)
