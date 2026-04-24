package com.xgwnje.visionguard.data.model

import androidx.compose.runtime.Immutable

// ┌─────────────────────────────────────────────────────────┐
// │ DeviceInfo.kt                                           │
// │ 角色：检测端设备状态数据类（Android 推理端）              │
// │ @Immutable：保证 Compose 跳过重组的稳定性               │
// └─────────────────────────────────────────────────────────┘

@Immutable
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
    val targets: String = "",
    /** 客户端类型："android-detector"，供 UI 差异化展示使用 */
    val clientType: String = "android-detector"
)

/** 记录每台设备最后下发的参数配置（Android 端本地缓存） */
data class DeviceConfig(
    val cooldown: Int = 5,           // 秒
    val confidence: Double = 0.45,   // 0.10–0.95
    val targets: String = ""         // 英文逗号分隔，如 "person,car"
)
