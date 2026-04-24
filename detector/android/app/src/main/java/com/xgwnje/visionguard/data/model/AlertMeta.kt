package com.xgwnje.visionguard.data.model

// ┌─────────────────────────────────────────────────────────┐
// │ AlertMeta.kt                                            │
// │ 角色：HTTP POST /api/alert 的 meta 字段 JSON 结构         │
// │ 与服务器 AlertMeta 接口对齐                                │
// └─────────────────────────────────────────────────────────┘

data class AlertMeta(
    val deviceId: String,
    val deviceName: String,
    val timestamp: String,
    val detections: List<ServerDetection>
)

data class ServerDetection(
    val label: String,
    val confidence: Float,
    val bbox: Bbox
)

data class Bbox(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)
