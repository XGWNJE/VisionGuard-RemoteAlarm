package com.xgwnje.visionguard_android.data.remote

// ┌─────────────────────────────────────────────────────────┐
// │ WebSocketClient.kt                                      │
// │ 角色：OkHttp WebSocket 封装，自动重连（指数退避）          │
// │ 线程：回调在 OkHttp 线程池；状态通过 StateFlow 暴露       │
// │ 对外 API：connect(), disconnect(), sendCommand()         │
// │           connectionState, onAlert, onDeviceList         │
// └─────────────────────────────────────────────────────────┘

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import com.xgwnje.visionguard_android.data.model.ScreenshotData
import com.xgwnje.visionguard_android.data.model.WsAuthMessage
import com.xgwnje.visionguard_android.data.model.WsCommandMessage
import com.xgwnje.visionguard_android.data.model.WsScreenshotDataMessage
import com.xgwnje.visionguard_android.data.model.WsSetConfigMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "VG_WsClient"
private val BACKOFF_SECONDS = longArrayOf(1, 2, 4, 8, 16, 30)

enum class WsState {
    DISCONNECTED,   // 未连接 / 连接断开
    CONNECTING,     // TCP 握手 + 等待认证
    CONNECTED,      // 认证成功，正常工作
    AUTH_FAILED     // API Key 错误，不会自动重连
}

class WebSocketClient {

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(WsState.DISCONNECTED)
    val connectionState: StateFlow<WsState> = _connectionState

    private val _onAlert = MutableSharedFlow<AlertMessage>(extraBufferCapacity = 64)
    val onAlert: SharedFlow<AlertMessage> = _onAlert

    private val _onDeviceList = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val onDeviceList: StateFlow<List<DeviceInfo>> = _onDeviceList.asStateFlow()

    private val _onCommandAck = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)
    val onCommandAck: SharedFlow<Pair<String, Boolean>> = _onCommandAck  // <command, success>

    private val _onScreenshotData = MutableSharedFlow<ScreenshotData>(extraBufferCapacity = 8)
    val onScreenshotData: SharedFlow<ScreenshotData> = _onScreenshotData

    private var serverUrl: String = ""
    private var apiKey: String = ""
    private var deviceId: String = ""

    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // WebSocket 长连接无读超时
        .build()

    private var ws: WebSocket? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var shouldReconnect = false

    // 最后收到任意消息的时间戳（用于幽灵连接检测）
    @Volatile private var _lastMessageAt = 0L

    // 心跳计数（用于减少日志频率）
    private var heartbeatCount = 0

    // 上次断开原因（用于诊断）
    private var lastDisconnectReason = "initial"

    // Session 追踪：本次连接开始时间
    @Volatile private var sessionStartTime = 0L

    // Session 追踪：上次连接持续时长（毫秒）
    private var lastSessionDurationMs = -1L

    // ── 断开原因上报 ──────────────────────────────────────────
    private fun sendDisconnectReason(reason: String, detail: String = "") {
        if (ws == null) return
        val msg = mapOf(
            "type" to "disconnect-reason",
            "reason" to reason,
            "detail" to detail
        )
        try {
            ws?.send(gson.toJson(msg))
        } catch (e: Exception) {
            Log.w(TAG, "发送断开原因失败: ${e.message}")
        }
    }

    // ── Session 信息上报（重连时发给服务器，帮助诊断） ──────────
    private fun sendSessionInfo() {
        if (ws == null) return
        // 判断本次是否为重连（上次 Session 持续时长有值）
        val isReconnect = lastSessionDurationMs >= 0
        val msg = mapOf(
            "type" to "session-info",
            "deviceId" to deviceId,
            "lastSessionEndReason" to lastDisconnectReason,
            "lastSessionDurationMs" to lastSessionDurationMs,
            "isReconnect" to isReconnect
        )
        try {
            ws?.send(gson.toJson(msg))
            Log.i(TAG, """WS Session 上报:
                |  isReconnect=$isReconnect
                |  lastSessionEndReason=$lastDisconnectReason
                |  lastSessionDurationMs=$lastSessionDurationMs""".trimMargin())
        } catch (e: Exception) {
            Log.w(TAG, "发送 Session 信息失败: ${e.message}")
        }
    }

    // ── 公开 API ──────────────────────────────────────────────

    fun connect(serverUrl: String, apiKey: String, deviceId: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.apiKey    = apiKey
        this.deviceId  = deviceId
        shouldReconnect = true
        // 重置 Session 开始时间（用于计算本次连接持续时长）
        sessionStartTime = System.currentTimeMillis()
        // 重置认证失败状态，让 UI 显示 CONNECTING
        if (_connectionState.value == WsState.AUTH_FAILED) {
            _connectionState.value = WsState.DISCONNECTED
        }
        Log.i(TAG, """WS connect:
            |  serverUrl: $serverUrl
            |  deviceId: $deviceId
            |  pingInterval: 25s
            |  connectTimeout: 10s""".trimMargin())
        startConnectLoop()
    }

    fun disconnect() {
        shouldReconnect = false
        lastDisconnectReason = "user-close"
        sendDisconnectReason("user-close", "用户主动断开")
        // 记录本次 Session 持续时长
        if (sessionStartTime > 0) {
            lastSessionDurationMs = System.currentTimeMillis() - sessionStartTime
            Log.i(TAG, "WS disconnect，Session 持续 ${lastSessionDurationMs / 1000}s")
        }
        connectJob?.cancel()
        ws?.close(1000, "user disconnect")
        ws = null
        _connectionState.value = WsState.DISCONNECTED
        _onDeviceList.value = emptyList()
        Log.i(TAG, "WS disconnect()，设备列表已清空")
    }

    fun sendCommand(targetDeviceId: String, command: String) {
        val msg = WsCommandMessage(targetDeviceId = targetDeviceId, command = command)
        ws?.send(gson.toJson(msg))
    }

    fun sendSetConfig(targetDeviceId: String, key: String, value: String) {
        val msg = WsSetConfigMessage(targetDeviceId = targetDeviceId, key = key, value = value)
        ws?.send(gson.toJson(msg))
    }

    fun requestScreenshot(alertId: String, targetDeviceId: String): Boolean {
        val msg = WsScreenshotDataMessage(alertId = alertId, targetDeviceId = targetDeviceId)
        return ws?.send(gson.toJson(msg)) ?: false
    }

    // ── 连接循环 ──────────────────────────────────────────────

    private fun startConnectLoop() {
        connectJob?.cancel()
        _onDeviceList.value = emptyList()
        connectJob = scope.launch {
            var attempt = 0
            while (shouldReconnect) {
                _connectionState.value = WsState.CONNECTING
                Log.i(TAG, "WS 连接中 → $serverUrl (第${attempt + 1}次) 上次断开原因=$lastDisconnectReason")

                val wsUrl = serverUrl
                    .replace("https://", "wss://")
                    .replace("http://",  "ws://") + "/ws"

                val req = Request.Builder().url(wsUrl).build()
                var connectedSuccessfully = false

                val listener = object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        Log.i(TAG, "WS onOpen → 发送认证 deviceId=$deviceId")
                        val auth = WsAuthMessage(apiKey = apiKey, deviceId = deviceId)
                        ws.send(gson.toJson(auth))
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        _lastMessageAt = System.currentTimeMillis()
                        handleMessage(text)
                    }

                    override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                        _lastMessageAt = System.currentTimeMillis()
                        Log.d(TAG, "WS 收到二进制 帧: ${bytes.size} bytes")
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        val httpCode = response?.code?.toString() ?: "n/a"
                        val (disconnectType, detail) = when {
                            t.message?.contains("Unable to connect") == true -> Pair("server-unreachable", "无法连接到服务器")
                            t.message?.contains("timeout") == true -> Pair("network-timeout", "连接超时")
                            t.message?.contains("refused") == true -> Pair("connection-refused", "连接被拒绝")
                            t.message?.contains("canceled") == true -> Pair("user-canceled", "用户取消")
                            t.message?.contains("network") == true -> Pair("network-lost", "网络中断")
                            else -> Pair("unknown", t.message ?: "未知错误")
                        }
                        lastDisconnectReason = disconnectType
                        // 记录 Session 结束：onFailure 时计算本次持续时长
                        if (sessionStartTime > 0) {
                            lastSessionDurationMs = System.currentTimeMillis() - sessionStartTime
                            Log.w(TAG, "WS onFailure，Session 持续 ${lastSessionDurationMs / 1000}s 原因=$disconnectType")
                        }
                        Log.w(TAG, """WS onFailure:
                            |  断开类型: $disconnectType
                            |  异常类型: ${t.javaClass.simpleName}
                            |  异常消息: $detail
                            |  HTTP状态码: $httpCode
                            |  当前状态: ${_connectionState.value}""".trimMargin())
                        _connectionState.value = WsState.DISCONNECTED
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        val (disconnectType, codeName) = when (code) {
                            1000 -> Pair("user-close", "正常关闭")
                            1001 -> Pair("server-kick", "服务器关闭")
                            1005 -> Pair("no-status", "无状态码")
                            1006 -> Pair("network-lost", "异常断开 (网络中断)")
                            else -> Pair("unknown", "code=$code")
                        }
                        lastDisconnectReason = disconnectType
                        // 记录 Session 结束：计算本次持续时长
                        if (sessionStartTime > 0) {
                            lastSessionDurationMs = System.currentTimeMillis() - sessionStartTime
                            Log.i(TAG, "WS onClosed，Session 持续 ${lastSessionDurationMs / 1000}s 原因=$disconnectType")
                        }
                        Log.i(TAG, "WS onClosed: type=$disconnectType code=$codeName reason='$reason'")
                        _connectionState.value = WsState.DISCONNECTED
                    }
                }

                ws = http.newWebSocket(req, listener)

                // 等待连接结果（最多 12 秒等认证）
                val deadline = System.currentTimeMillis() + 12_000
                while (System.currentTimeMillis() < deadline && shouldReconnect) {
                    if (_connectionState.value == WsState.CONNECTED) break
                    delay(200)
                }

                if (_connectionState.value != WsState.CONNECTED && shouldReconnect) {
                    ws?.cancel()
                    ws = null
                    _connectionState.value = WsState.DISCONNECTED
                    val waitSec = BACKOFF_SECONDS[minOf(attempt, BACKOFF_SECONDS.size - 1)]
                    Log.i(TAG, "${waitSec}s 后重连...")
                    delay(waitSec * 1000)
                    attempt++
                } else {
                    // 已连接，每 30s 发送应用层心跳并检查消息流动
                    // 阈值 115s = 服务端幽灵清理(75s) + 服务端推送间隔(30s) + 余量(10s)
                    // 心跳同时刷新服务器端的 lastSeen，防止被幽灵清理
                    val GHOST_THRESHOLD_MS = 115_000L
                    _lastMessageAt = System.currentTimeMillis()
                    heartbeatCount = 0
                    while (_connectionState.value == WsState.CONNECTED && shouldReconnect) {
                        delay(30_000)
                        if (_connectionState.value != WsState.CONNECTED || !shouldReconnect) break

                        // 发送应用层心跳
                        val hbMsg = mapOf("type" to "heartbeat-android", "deviceId" to deviceId)
                        ws?.send(gson.toJson(hbMsg))
                        heartbeatCount++
                        if (heartbeatCount == 1 || heartbeatCount % 10 == 0) {
                            Log.d(TAG, "WS 发送心跳 #${heartbeatCount} deviceId=$deviceId")
                        }

                        val silentMs = System.currentTimeMillis() - _lastMessageAt
                        Log.v(TAG, "WS 保活检查: 静默 ${silentMs / 1000}s / 阈值 ${GHOST_THRESHOLD_MS / 1000}s")
                        if (silentMs > GHOST_THRESHOLD_MS) {
                            Log.w(TAG, """WS 幽灵连接检测:
                                |  静默时间: ${silentMs / 1000}s (阈值: ${GHOST_THRESHOLD_MS / 1000}s)
                                |  deviceId: $deviceId
                                |  连接状态: ${_connectionState.value}
                                |  触发主动重连""".trimMargin())
                            ws?.cancel()
                            _connectionState.value = WsState.DISCONNECTED
                            break
                        }
                    }
                    if (shouldReconnect) {
                        delay(BACKOFF_SECONDS[0] * 1000)
                        attempt = 0  // 曾经连接成功，退避归零
                    }
                }
            }
        }
    }

    // ── 消息处理 ──────────────────────────────────────────────

    private fun handleMessage(text: String) {
        try {
            val obj: JsonObject = JsonParser.parseString(text).asJsonObject
            val type = obj.get("type")?.asString ?: ""
            Log.v(TAG, "WS 收到: $type")
            when (type) {
                "auth-result" -> {
                    val success = obj.get("success")?.asBoolean ?: false
                    if (success) {
                        Log.i(TAG, "WS 认证成功")
                        _connectionState.value = WsState.CONNECTED
                        // 认证成功后，上报 Session 信息（帮助服务端诊断上次断开原因）
                        sendSessionInfo()
                    } else {
                        val reason = obj.get("reason")?.asString ?: "unknown"
                        Log.w(TAG, "WS 认证失败: $reason")
                        _connectionState.value = WsState.DISCONNECTED
                        // 继续自动重连，下次可能成功（如服务器重启后 Key 恢复）
                    }
                }
                "alert" -> {
                    val alert = gson.fromJson(text, AlertMessage::class.java)
                    scope.launch { _onAlert.emit(alert) }
                }
                "device-list" -> {
                    val devicesArr = obj.getAsJsonArray("devices")
                    val devices = devicesArr?.map { gson.fromJson(it, DeviceInfo::class.java) } ?: emptyList()
                    Log.d(TAG, "WS device-list: ${devices.size} 台设备")
                    _onDeviceList.value = devices
                }
                "command-ack" -> {
                    val cmd     = obj.get("command")?.asString ?: ""
                    val success = obj.get("success")?.asBoolean ?: false
                    val reason  = obj.get("reason")?.asString ?: ""
                    // 过滤服务器中转确认（reason="relayed"），只处理 Windows 真实执行结果
                    if (reason != "relayed") {
                        // 将 reason 附在 command 后面，DeviceListScreen 显示时可读取
                        val display = if (!success && reason.isNotEmpty()) "$cmd（$reason）" else cmd
                        scope.launch { _onCommandAck.emit(Pair(display, success)) }
                    }
                }
                "screenshot-data" -> {
                    val data = gson.fromJson(text, WsScreenshotDataMessage::class.java)
                    if (data.imageBase64.isNotEmpty()) {
                        scope.launch {
                            _onScreenshotData.emit(
                                ScreenshotData(data.alertId, data.imageBase64, data.width, data.height)
                            )
                        }
                    }
                }
                else -> Log.d(TAG, "未知消息 type=$type")
            }
        } catch (e: Exception) {
            Log.w(TAG, "消息解析失败: ${e.message}")
        }
    }
}
