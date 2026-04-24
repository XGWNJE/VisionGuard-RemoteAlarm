package com.xgwnje.visionguard_android.data.model

// ┌─────────────────────────────────────────────────────────┐
// │ AlertMessage.kt                                         │
// │ 角色：报警事件数据类                                      │
// │ 来源：服务器 WS "alert" 消息 / 内存历史列表               │
// └─────────────────────────────────────────────────────────┘

data class BoundingBox(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f
)

data class Detection(
    val label: String = "",
    val confidence: Double = 0.0,
    val bbox: BoundingBox = BoundingBox()
)

data class AlertMessage(
    val alertId: String = "",        // Gson 解析缺失字段时防 NPE
    val deviceId: String = "",
    val deviceName: String = "",
    val timestamp: String = "",      // ISO 8601
    val detections: List<Detection> = emptyList(),
    val screenshotUrl: String = "",  // 已废弃，保留兼容（Windows 不再发此字段）
    val screenshotData: ScreenshotData? = null,
    val timings: Map<String, Long>? = null,
    val wsSentAt: String? = null,
    val serverReceivedAt: String? = null,
    val serverRelayedAt: String? = null,
    @Transient var receivedAt: Long = 0L,
    @Transient var notifiedAt: Long = 0L
)

data class ScreenshotData(
    val alertId: String,
    val imageBase64: String,
    val width: Int,
    val height: Int
)
