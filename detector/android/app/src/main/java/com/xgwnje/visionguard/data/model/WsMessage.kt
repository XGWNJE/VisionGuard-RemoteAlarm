package com.xgwnje.visionguard.data.model

// ┌─────────────────────────────────────────────────────────┐
// │ WsMessage.kt                                            │
// │ 角色：WebSocket 消息数据类                               │
// │ 用途：Gson 序列化/反序列化                               │
// └─────────────────────────────────────────────────────────┘

import com.google.gson.JsonObject

/** 所有 WS 消息的原始容器；先按 type 字段决定具体类型 */
data class RawWsMessage(
    val type: String = ""
)

/** Android-detector → 服务器：认证 */
data class WsAuthMessage(
    val type: String = "auth",
    val apiKey: String,
    val role: String = "android-detector",
    val deviceId: String,
    val deviceName: String = "Android-Detector",
    val version: String = "3.5.3"
)

/** 服务器 → Android-detector：认证结果 */
data class WsAuthResult(
    val type: String = "auth-result",
    val success: Boolean = false,
    val reason: String = ""
)

/** Android-receiver → 服务器：发送控制命令（pause / resume / stop-alarm） */
data class WsCommandMessage(
    val type: String = "command",
    val targetDeviceId: String = "",
    val command: String             // "pause" | "resume" | "stop-alarm"
)

/** Android-receiver → 服务器：下发参数调整（set-config） */
data class WsSetConfigMessage(
    val type: String = "set-config",
    val targetDeviceId: String = "",
    val key: String = "",    // "cooldown" | "confidence" | "targets"
    val value: String = ""   // 字符串形式的值
)

/** 服务器 → Android-detector：命令回执 */
data class WsCommandAck(
    val type: String = "command-ack",
    val targetDeviceId: String = "",
    val command: String = "",
    val success: Boolean = false,
    val reason: String = ""
)

/** Android-receiver → 服务器：请求指定设备的截图 */
data class WsScreenshotDataMessage(
    val type: String = "request-screenshot",
    val alertId: String,
    val targetDeviceId: String = "",
    val imageBase64: String = "",   // 服务器回传时才有值
    val width: Int = 0,
    val height: Int = 0
)

/** Android-detector → 服务器：心跳（含运行状态） */
data class WsHeartbeatMessage(
    val type: String = "heartbeat",
    val deviceId: String,
    val isMonitoring: Boolean = false,
    val isReady: Boolean = false,
    val cooldown: Int = 5,
    val confidence: Double = 0.45,
    val targets: String = ""
)

/** Android-detector / Windows → 服务器：轻量报警通知（无截图数据） */
data class WsAlertMessage(
    val type: String = "alert",
    val alertId: String,
    val deviceId: String,
    val deviceName: String,
    val timestamp: String,
    val detections: List<JsonObject> = emptyList(),
    val timings: Map<String, Long> = emptyMap()
)

/** Android-detector → 服务器：日志上报 */
data class WsLogReportMessage(
    val type: String = "log-report",
    val level: String,
    val tag: String,
    val message: String,
    val timestamp: String
)
