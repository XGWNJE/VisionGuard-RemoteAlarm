package com.xgwnje.visionguard_android.service

// ┌─────────────────────────────────────────────────────────┐
// │ AlertForegroundService.kt                               │
// │ 角色：前台服务，持有 WS 连接，接收报警并发送通知           │
// │ 生命周期：START_STICKY，被杀后自动重启                    │
// │ 对外：通过 StateFlow/SharedFlow 暴露状态给 ViewModel     │
// │ Binder：AlertServiceBinder 供 Activity 绑定              │
// │ 服务器地址/API Key 从 AppConstants 读取（硬编码）          │
// └─────────────────────────────────────────────────────────┘

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xgwnje.visionguard_android.AppConstants
import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import com.xgwnje.visionguard_android.data.remote.WebSocketClient
import com.xgwnje.visionguard_android.data.remote.WsState
import com.xgwnje.visionguard_android.data.repository.SettingsRepository
import com.xgwnje.visionguard_android.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "VG_FgService"

class AlertForegroundService : LifecycleService() {

    // ── Binder ────────────────────────────────────────────────
    inner class AlertServiceBinder : Binder() {
        fun getService(): AlertForegroundService = this@AlertForegroundService
    }
    private val binder = AlertServiceBinder()

    // ── 对外状态 ──────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(WsState.DISCONNECTED)
    val connectionState: StateFlow<WsState> = _connectionState

    private val _alerts = MutableStateFlow<List<AlertMessage>>(emptyList())
    val alerts: StateFlow<List<AlertMessage>> = _alerts

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices

    private val _commandAck = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)
    val commandAck: SharedFlow<Pair<String, Boolean>> = _commandAck

    // ── 内部状态 ──────────────────────────────────────────────
    private lateinit var wsClient: WebSocketClient
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var nm: NotificationManager
    private val notifIdCounter = AtomicInteger(1000)

    // ── 生命周期 ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        NotificationHelper.createChannels(this)
        settingsRepo = SettingsRepository(applicationContext)
        wsClient = WebSocketClient()

        // 启动前台通知
        startForeground(
            NotificationHelper.FOREGROUND_NOTIF_ID,
            NotificationHelper.buildForegroundNotification(this, "正在连接...")
        )

        // 订阅 WS 状态 → 更新前台通知
        lifecycleScope.launch {
            wsClient.connectionState.collect { state ->
                _connectionState.value = state
                val stateText = when (state) {
                    WsState.CONNECTED    -> "已连接"
                    WsState.CONNECTING   -> "连接中..."
                    WsState.AUTH_FAILED  -> "API Key 错误"
                    WsState.DISCONNECTED -> "未连接"
                }
                nm.notify(
                    NotificationHelper.FOREGROUND_NOTIF_ID,
                    NotificationHelper.buildForegroundNotification(this@AlertForegroundService, stateText)
                )
            }
        }

        // 订阅报警
        lifecycleScope.launch {
            wsClient.onAlert.collect { alert ->
                Log.i(TAG, "收到报警: ${alert.deviceName} - ${alert.detections.size} 个目标")
                _alerts.value = (_alerts.value + alert).takeLast(200)
                sendAlertNotification(alert)
            }
        }

        // 订阅设备列表
        lifecycleScope.launch {
            wsClient.onDeviceList.collect { devices ->
                _devices.value = devices
            }
        }

        // 订阅命令回执
        lifecycleScope.launch {
            wsClient.onCommandAck.collect { ack ->
                _commandAck.emit(ack)
            }
        }

        // 读取持久化 deviceId 后连接（使用硬编码的 URL 和 Key）
        lifecycleScope.launch {
            val deviceId = settingsRepo.ensureDeviceId()
            wsClient.connect(AppConstants.SERVER_URL, AppConstants.API_KEY, deviceId)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        wsClient.disconnect()
    }

    // ── 公开 API ──────────────────────────────────────────────

    fun sendCommand(targetDeviceId: String, command: String) {
        wsClient.sendCommand(targetDeviceId, command)
    }

    fun sendSetConfig(targetDeviceId: String, key: String, value: String) {
        wsClient.sendSetConfig(targetDeviceId, key, value)
    }

    /** 手动重试连接（UI 重试按钮调用） */
    fun reconnect() {
        lifecycleScope.launch {
            val deviceId = settingsRepo.ensureDeviceId()
            wsClient.connect(AppConstants.SERVER_URL, AppConstants.API_KEY, deviceId)
        }
    }

    fun clearAlerts() {
        _alerts.value = emptyList()
    }

    // ── 通知发送 ──────────────────────────────────────────────

    private fun sendAlertNotification(alert: AlertMessage) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = tryLoadThumbnail(alert)
            val notifId = notifIdCounter.incrementAndGet()
            val notif = NotificationHelper.buildAlertNotification(
                this@AlertForegroundService, alert, notifId, bitmap
            )
            nm.notify(notifId, notif)
        }
    }

    private suspend fun tryLoadThumbnail(alert: AlertMessage): Bitmap? {
        if (alert.screenshotUrl.isEmpty()) return null
        return try {
            val url = AppConstants.SERVER_URL + alert.screenshotUrl + "?key=" + AppConstants.API_KEY
            val req = Request.Builder().url(url).build()
            val client = OkHttpClient()
            val bytes = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { it.body?.bytes() }
            } ?: return null
            val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val ratio = 256.0 / full.width
            val h = (full.height * ratio).toInt()
            Bitmap.createScaledBitmap(full, 256, h, true)
        } catch (e: Exception) {
            Log.w(TAG, "缩略图加载失败: ${e.message}")
            null
        }
    }
}
