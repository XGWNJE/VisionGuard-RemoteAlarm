package com.xgwnje.visionguard.data.remote

// ┌─────────────────────────────────────────────────────────┐
// │ WebSocketClient.kt                                      │
// │ 架构：单状态源 + 单事件循环 + Session 隔离              │
// │   • 所有状态变更通过 events Channel 串行处理            │
// │   • 每次连接一个独立 Session，cancel 后所有子任务退出    │
// │   • lastMessageAt 只由真实消息更新，心跳不自我喂食      │
// └─────────────────────────────────────────────────────────┘

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xgwnje.visionguard.data.model.DeviceInfo
import com.xgwnje.visionguard.data.model.WsAuthMessage
import com.xgwnje.visionguard.data.model.WsCommandAck
import com.xgwnje.visionguard.data.model.WsCommandMessage
import com.xgwnje.visionguard.data.model.WsHeartbeatMessage
import com.xgwnje.visionguard.data.model.WsLogReportMessage
import com.xgwnje.visionguard.data.model.WsScreenshotDataMessage
import com.xgwnje.visionguard.data.model.WsSetConfigMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "VG_WsClient"

// 重连退避：1 → 2 → 3 → 5 → 10 秒
private val BACKOFF_SECONDS = longArrayOf(1, 2, 3, 5, 10)

// 心跳间隔：15 秒（与 Windows 端一致，服务端 75 秒幽灵阈值配合）
private const val HEARTBEAT_INTERVAL_MS = 15_000L

// 幽灵检测：45 秒无任何消息视为连接已死
private const val GHOST_THRESHOLD_MS = 45_000L

// 认证等待：12 秒内必须拿到 auth-result
private const val AUTH_TIMEOUT_MS = 12_000L

enum class WsState { DISCONNECTED, CONNECTING, CONNECTED, AUTH_FAILED }

class WebSocketClient {

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 对外状态 ─────────────────────────────────────────────
    private val _state = MutableStateFlow(WsState.DISCONNECTED)
    val connectionState: StateFlow<WsState> = _state.asStateFlow()

    private val _onDeviceList = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val onDeviceList: StateFlow<List<DeviceInfo>> = _onDeviceList.asStateFlow()

    private val _onCommandAck = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)
    val onCommandAck: SharedFlow<Pair<String, Boolean>> = _onCommandAck

    private val _onScreenshotData = MutableSharedFlow<ScreenshotData>(extraBufferCapacity = 8)
    val onScreenshotData: SharedFlow<ScreenshotData> = _onScreenshotData

    // detector 端新增：接收远程命令和配置变更
    private val _onCommand = MutableSharedFlow<WsCommandMessage>(extraBufferCapacity = 8)
    val onCommand: SharedFlow<WsCommandMessage> = _onCommand

    private val _onSetConfig = MutableSharedFlow<WsSetConfigMessage>(extraBufferCapacity = 8)
    val onSetConfig: SharedFlow<WsSetConfigMessage> = _onSetConfig

    // detector 端新增：接收截图请求（接收端 request-screenshot 经服务器转发）
    private val _onRequestScreenshot = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val onRequestScreenshot: SharedFlow<String> = _onRequestScreenshot

    // ── 运行状态（供心跳上报使用） ───────────────────────────
    @Volatile
    var isMonitoring: Boolean = false

    @Volatile
    var isAlarming: Boolean = false

    @Volatile
    var isReady: Boolean = false

    @Volatile
    var heartbeatCooldown: Int = 5

    @Volatile
    var heartbeatConfidence: Double = 0.45

    @Volatile
    var heartbeatTargets: String = ""

    // ── 事件定义 ─────────────────────────────────────────────
    private sealed class Event {
        data class Connect(val url: String, val apiKey: String, val deviceId: String, val deviceName: String) : Event()
        object Disconnect : Event()
        object NetworkAvailable : Event()
        data class WsOpened(val session: Session) : Event()
        data class AuthResult(val session: Session, val success: Boolean, val reason: String?) : Event()
        data class WsFailed(val session: Session, val code: Int, val reason: String) : Event()
        data class GhostDetected(val session: Session) : Event()
        data class Kicked(val session: Session, val reason: String) : Event()
        object NetworkLost : Event()
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    // ── 配置（事件循环内独占访问） ───────────────────────────
    private var serverUrl: String = ""
    private var apiKey: String = ""
    private var deviceId: String = ""
    private var deviceName: String = ""
    private var shouldReconnect = false
    private var attempt = 0
    private var pendingBackoffJob: Job? = null

    // ── 当前会话 ─────────────────────────────────────────────
    private var session: Session? = null

    @Volatile
    var networkChecker: (() -> Boolean)? = null

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 网络切换时清除旧连接池，避免 OkHttp 复用已失效的 TCP socket */
    private fun evictConnectionPool() {
        try { http.connectionPool.evictAll() } catch (_: Exception) {}
    }

    init {
        startEventLoop()
    }

    // ═════════════════════════════════════════════════════════
    // 对外 API
    // ═════════════════════════════════════════════════════════

    fun connect(serverUrl: String, apiKey: String, deviceId: String, deviceName: String = "Android-Detector") {
        events.trySend(Event.Connect(serverUrl.trimEnd('/'), apiKey, deviceId, deviceName))
    }

    fun disconnect() {
        events.trySend(Event.Disconnect)
    }

    fun onNetworkAvailable() {
        events.trySend(Event.NetworkAvailable)
    }

    fun onNetworkLost() {
        events.trySend(Event.NetworkLost)
    }

    /** 立即发送一次心跳（用于状态突变后快速同步） */
    fun sendHeartbeatNow(): Boolean {
        val s = session ?: return false
        val hb = WsHeartbeatMessage(
            deviceId = deviceId,
            isMonitoring = isMonitoring,
            isAlarming = isAlarming,
            isReady = isReady,
            cooldown = heartbeatCooldown,
            confidence = heartbeatConfidence,
            targets = heartbeatTargets
        )
        return s.sendNow(gson.toJson(hb))
    }

    // ── receiver 端原有 API（detector 端作为命令接收方，通常不主动发送命令） ──
    fun sendCommand(targetDeviceId: String, command: String) {
        val msg = WsCommandMessage(targetDeviceId = targetDeviceId, command = command)
        session?.ws?.send(gson.toJson(msg))
    }

    fun sendSetConfig(targetDeviceId: String, key: String, value: String) {
        val msg = WsSetConfigMessage(targetDeviceId = targetDeviceId, key = key, value = value)
        session?.ws?.send(gson.toJson(msg))
    }

    fun requestScreenshot(alertId: String, targetDeviceId: String): Boolean {
        val msg = WsScreenshotDataMessage(alertId = alertId, targetDeviceId = targetDeviceId)
        return session?.ws?.send(gson.toJson(msg)) ?: false
    }

    // ── detector 端新增 API ──────────────────────────────────

    /** 发送命令回执 */
    fun sendCommandAck(command: String, success: Boolean, reason: String = ""): Boolean {
        val msg = WsCommandAck(
            targetDeviceId = deviceId,
            command = command,
            success = success,
            reason = reason
        )
        return session?.ws?.send(gson.toJson(msg)) ?: false
    }

    /** 发送截图数据 */
    fun sendScreenshotData(alertId: String, imageBase64: String, width: Int, height: Int): Boolean {
        val msg = WsScreenshotDataMessage(
            type = "screenshot-data",
            alertId = alertId,
            targetDeviceId = deviceId,
            imageBase64 = imageBase64,
            width = width,
            height = height
        )
        return session?.ws?.send(gson.toJson(msg)) ?: false
    }

    /** 发送日志上报 */
    fun sendLogReport(level: String, tag: String, message: String): Boolean {
        val msg = WsLogReportMessage(
            level = level,
            tag = tag,
            message = message,
            timestamp = isoNow()
        )
        return session?.ws?.send(gson.toJson(msg)) ?: false
    }

    /** 发送原始 JSON（用于 pushAlert 等自定义消息） */
    fun sendRawJson(json: String): Boolean {
        return session?.ws?.send(json) ?: false
    }

    // ═════════════════════════════════════════════════════════
    // 事件循环 — 所有状态变更的唯一入口
    // ═════════════════════════════════════════════════════════

    private fun startEventLoop() = scope.launch {
        for (event in events) {
            try {
                handle(event)
            } catch (e: Exception) {
                Log.e(TAG, "事件处理异常 event=${event::class.simpleName}: ${e.message}", e)
            }
        }
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.Connect -> onConnect(event)
            is Event.Disconnect -> onDisconnect()
            is Event.NetworkAvailable -> onNetworkAvailableEvent()
            is Event.WsOpened -> onWsOpened(event)
            is Event.AuthResult -> onAuthResult(event)
            is Event.WsFailed -> onWsFailed(event)
            is Event.GhostDetected -> onGhostDetected(event)
            is Event.Kicked -> onKicked(event)
            is Event.NetworkLost -> onNetworkLostEvent()
            is InternalStartSession -> onInternalStartSession()
        }
    }

    private fun onInternalStartSession() {
        if (!shouldReconnect) return
        if (_state.value == WsState.CONNECTED || _state.value == WsState.CONNECTING) return
        startNewSession()
    }

    private fun onConnect(e: Event.Connect) {
        // 参数相同且已连接 → 跳过
        if (e.url == serverUrl && e.apiKey == apiKey && e.deviceId == deviceId && e.deviceName == deviceName
            && _state.value == WsState.CONNECTED) {
            Log.i(TAG, "connect 忽略：参数未变且已连接")
            return
        }
        serverUrl = e.url
        apiKey = e.apiKey
        deviceId = e.deviceId
        deviceName = e.deviceName
        shouldReconnect = true
        attempt = 0

        // 有会话先终止
        session?.shutdown("reconfigure")
        session = null
        pendingBackoffJob?.cancel()
        pendingBackoffJob = null

        Log.i(TAG, "connect → $serverUrl deviceId=$deviceId")
        startNewSession()
    }

    private fun onDisconnect() {
        Log.i(TAG, "disconnect 用户主动断开")
        shouldReconnect = false
        pendingBackoffJob?.cancel()
        pendingBackoffJob = null
        session?.shutdown("user-close")
        session = null
        _state.value = WsState.DISCONNECTED
        _onDeviceList.value = emptyList()
    }

    private fun onNetworkAvailableEvent() {
        if (!shouldReconnect) return

        when (_state.value) {
            WsState.CONNECTED -> {
                Log.i(TAG, "网络变化且当前已连接 → 强制断开旧连接并立即重连")
                session?.shutdown("network-changed")
                session = null
                _onDeviceList.value = emptyList()
            }
            WsState.CONNECTING -> {
                Log.i(TAG, "网络变化且正在连接 → 中断并在新网络上重试")
                session?.shutdown("network-changed")
                session = null
            }
            else -> {}
        }

        evictConnectionPool()
        pendingBackoffJob?.cancel()
        pendingBackoffJob = null
        attempt = 0
        Log.i(TAG, "网络恢复 → 立即重连")
        startNewSession()
    }

    private fun onWsOpened(e: Event.WsOpened) {
        if (e.session != session) return
        Log.i(TAG, "WS onOpen → 发送认证")
        val auth = WsAuthMessage(apiKey = apiKey, deviceId = deviceId, deviceName = deviceName)
        e.session.ws.send(gson.toJson(auth))
    }

    private fun onAuthResult(e: Event.AuthResult) {
        if (e.session != session) return
        if (e.success) {
            Log.i(TAG, "WS 认证成功")
            attempt = 0
            _state.value = WsState.CONNECTED
            e.session.startHeartbeat()
        } else {
            Log.w(TAG, "WS 认证失败: ${e.reason}")
            _state.value = WsState.AUTH_FAILED
            _onDeviceList.value = emptyList()
            e.session.shutdown("auth-failed")
            session = null
            // 认证失败也继续重连（Key 可能临时错误）
            if (shouldReconnect) scheduleReconnect()
        }
    }

    private fun onWsFailed(e: Event.WsFailed) {
        if (e.session != session) return
        Log.w(TAG, "WS 失败 code=${e.code} reason=${e.reason}")
        e.session.shutdown("failed")
        session = null
        _state.value = WsState.DISCONNECTED
        _onDeviceList.value = emptyList()
        if (shouldReconnect) scheduleReconnect()
    }

    private fun onGhostDetected(e: Event.GhostDetected) {
        if (e.session != session) return
        Log.w(TAG, "幽灵检测触发，关闭连接")
        e.session.shutdown("ghost")
        session = null
        _state.value = WsState.DISCONNECTED
        _onDeviceList.value = emptyList()
        if (shouldReconnect) scheduleReconnect()
    }

    private fun onKicked(e: Event.Kicked) {
        if (e.session != session) return
        Log.w(TAG, "WS 被踢: ${e.reason}")
        e.session.shutdown("kicked")
        session = null
        _state.value = WsState.DISCONNECTED
        _onDeviceList.value = emptyList()
        if (shouldReconnect) scheduleReconnect()
    }

    private fun onNetworkLostEvent() {
        if (session == null) return
        Log.i(TAG, "默认网络断开 → 主动关闭当前会话，等待网络恢复后重连")
        session?.shutdown("network-lost")
        session = null
        evictConnectionPool()
        pendingBackoffJob?.cancel()
        pendingBackoffJob = null
        _state.value = WsState.DISCONNECTED
        _onDeviceList.value = emptyList()
    }

    // ═════════════════════════════════════════════════════════
    // 会话管理
    // ═════════════════════════════════════════════════════════

    private fun startNewSession() {
        pendingBackoffJob?.cancel()
        pendingBackoffJob = null

        _state.value = WsState.CONNECTING
        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/ws"
        Log.i(TAG, "启动新会话 attempt=${attempt + 1} url=$wsUrl")

        val newSession = Session(wsUrl)
        session = newSession
        newSession.connect()
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val waitSec = BACKOFF_SECONDS[minOf(attempt, BACKOFF_SECONDS.size - 1)]
        attempt++
        Log.i(TAG, "${waitSec}s 后重连（第 $attempt 次）")
        pendingBackoffJob?.cancel()
        pendingBackoffJob = scope.launch {
            delay(waitSec * 1000)
            if (!shouldReconnect) return@launch
            events.trySend(InternalStartSession)
        }
    }

    // 内部事件：退避到期后触发新会话
    private object InternalStartSession : Event()

    // ═════════════════════════════════════════════════════════
    // Session — 每次连接对应一个实例，彻底隔离
    // ═════════════════════════════════════════════════════════

    inner class Session(val wsUrl: String) {
        lateinit var ws: WebSocket
            private set

        private val sessionScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        private val lastMessageAt = AtomicLong(System.currentTimeMillis())
        private var heartbeatJob: Job? = null
        private var authTimeoutJob: Job? = null
        @Volatile private var shutdown = false

        fun connect() {
            val req = Request.Builder().url(wsUrl).build()
            val self = this
            ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (shutdown) return
                    lastMessageAt.set(System.currentTimeMillis())
                    events.trySend(Event.WsOpened(self))
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (shutdown) return
                    lastMessageAt.set(System.currentTimeMillis())
                    handleMessage(self, text)
                }

                override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                    if (shutdown) return
                    lastMessageAt.set(System.currentTimeMillis())
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (shutdown) return
                    val code = response?.code ?: -1
                    val msg = t.message ?: t.javaClass.simpleName
                    events.trySend(Event.WsFailed(self, code, msg))
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (shutdown) return
                    events.trySend(Event.WsFailed(self, code, reason))
                }
            })

            // 认证超时看门狗
            authTimeoutJob = sessionScope.launch {
                delay(AUTH_TIMEOUT_MS)
                if (!shutdown && _state.value != WsState.CONNECTED) {
                    events.trySend(Event.WsFailed(this@Session, -1, "auth-timeout"))
                }
            }
        }

        fun startHeartbeat() {
            authTimeoutJob?.cancel()
            lastMessageAt.set(System.currentTimeMillis())
            heartbeatJob = sessionScope.launch {
                while (isActive && !shutdown) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (shutdown) break

                    // 主动网络检测：ConnectivityManager 报告无网络 → 立即断开
                    val checker = networkChecker
                    if (checker != null && !checker()) {
                        Log.w(TAG, "网络不可用（ConnectivityManager）→ 主动断开")
                        events.trySend(Event.GhostDetected(this@Session))
                        break
                    }

                    // 幽灵检测：只看 lastMessageAt，不被心跳自己刷新
                    val silentMs = System.currentTimeMillis() - lastMessageAt.get()
                    if (silentMs > GHOST_THRESHOLD_MS) {
                        events.trySend(Event.GhostDetected(this@Session))
                        break
                    }

                    // 发送应用层心跳（使用 WsHeartbeatMessage 结构）
                    val hb = WsHeartbeatMessage(
                        deviceId = deviceId,
                        isMonitoring = isMonitoring,
                        isAlarming = isAlarming,
                        isReady = isReady,
                        cooldown = heartbeatCooldown,
                        confidence = heartbeatConfidence,
                        targets = heartbeatTargets
                    )
                    val sent = ws.send(gson.toJson(hb))
                    if (!sent) {
                        Log.w(TAG, "心跳发送失败 → 连接已死")
                        events.trySend(Event.GhostDetected(this@Session))
                        break
                    }
                }
            }
        }

        fun sendNow(json: String): Boolean {
            if (shutdown) return false
            return try {
                ws.send(json)
            } catch (_: Exception) {
                false
            }
        }

        fun shutdown(reason: String) {
            if (shutdown) return
            shutdown = true
            heartbeatJob?.cancel()
            authTimeoutJob?.cancel()
            try {
                if (::ws.isInitialized) ws.cancel()
            } catch (_: Exception) {
            }
            sessionScope.cancel()
            Log.d(TAG, "Session shutdown: $reason")
        }
    }

    // ═════════════════════════════════════════════════════════
    // 消息处理
    // ═════════════════════════════════════════════════════════

    private fun handleMessage(currentSession: Session, text: String) {
        if (currentSession != session) return
        try {
            val obj: JsonObject = JsonParser.parseString(text).asJsonObject
            val type = obj.get("type")?.asString ?: ""
            when (type) {
                "auth-result" -> {
                    val success = obj.get("success")?.asBoolean ?: false
                    val reason = obj.get("reason")?.asString
                    events.trySend(Event.AuthResult(currentSession, success, reason))
                }
                "kicked" -> {
                    val reason = obj.get("reason")?.asString ?: "duplicate"
                    events.trySend(Event.Kicked(currentSession, reason))
                }
                "device-list" -> {
                    val devicesArr = obj.getAsJsonArray("devices")
                    val devices = devicesArr?.map { gson.fromJson(it, DeviceInfo::class.java) } ?: emptyList()
                    _onDeviceList.value = devices
                }
                "command-ack" -> {
                    val cmd = obj.get("command")?.asString ?: ""
                    val success = obj.get("success")?.asBoolean ?: false
                    val reason = obj.get("reason")?.asString ?: ""
                    if (reason != "relayed") {
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
                // detector 端新增：接收远程命令和配置变更
                "command" -> {
                    val command = gson.fromJson(text, WsCommandMessage::class.java)
                    scope.launch { _onCommand.emit(command) }
                }
                "set-config" -> {
                    val config = gson.fromJson(text, WsSetConfigMessage::class.java)
                    scope.launch { _onSetConfig.emit(config) }
                }
                // detector 端新增：接收截图请求（接收端 request-screenshot 经服务器转发）
                "request-screenshot" -> {
                    val alertId = obj.get("alertId")?.asString ?: ""
                    if (alertId.isNotEmpty()) {
                        scope.launch { _onRequestScreenshot.emit(alertId) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "消息解析失败: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════
    // 工具
    // ═════════════════════════════════════════════════════════

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return sdf.format(Date())
    }
}

/** 截图数据包装 */
data class ScreenshotData(
    val alertId: String,
    val imageBase64: String,
    val width: Int,
    val height: Int
)
