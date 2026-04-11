package com.xgwnje.visionguard_android.data.model

// ┌─────────────────────────────────────────────────────────┐
// │ AlertMessage.kt                                         │
// │ 角色：报警事件数据类                                      │
// │ 来源：服务器 WS "alert" 消息 / 内存历史列表               │
// └─────────────────────────────────────────────────────────┘

data class BoundingBox(
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0
)

data class Detection(
    val label: String = "",
    val confidence: Double = 0.0,
    val bbox: BoundingBox = BoundingBox()
)

data class AlertMessage(
    val alertId: String,
    val deviceId: String,
    val deviceName: String,
    val timestamp: String,           // ISO 8601
    val detections: List<Detection>,
    val screenshotUrl: String = "",  // 已废弃，保留兼容（Windows 不再发此字段）
    val screenshotData: ScreenshotData? = null
)

data class ScreenshotData(
    val alertId: String,
    val imageBase64: String,
    val width: Int,
    val height: Int
)
