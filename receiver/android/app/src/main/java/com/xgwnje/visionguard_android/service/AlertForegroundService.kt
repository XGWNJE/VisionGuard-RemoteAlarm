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
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xgwnje.visionguard_android.AppConstants
import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import com.xgwnje.visionguard_android.data.model.ScreenshotData
import com.xgwnje.visionguard_android.data.cache.ScreenshotCache
import com.xgwnje.visionguard_android.data.model.DeviceConfig
import com.xgwnje.visionguard_android.data.remote.WebSocketClient
import com.xgwnje.visionguard_android.data.remote.WsState
import com.xgwnje.visionguard_android.data.repository.SettingsRepository
import com.xgwnje.visionguard_android.util.NotificationHelper
import android.util.Base64
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "VG_FgService"

class AlertForegroundService : LifecycleService() {

    // ── Binder ────────────────────────────────────────────────
    inner class AlertServiceBinder : Binder() {
        fun getService(): AlertForegroundService = this@AlertForegroundService
    }
    private val binder = AlertServiceBinder()

    // ── 对外状态 ──────────────────────────────────────────────
    // 连接状态直接暴露 wsClient 的 StateFlow，避免双状态源
    val connectionState: StateFlow<WsState> get() = wsClient.connectionState

    private val _alerts = MutableStateFlow<List<AlertMessage>>(emptyList())
    val alerts: StateFlow<List<AlertMessage>> = _alerts

    // 设备列表直接转发 wsClient.onDeviceList
    val devices: StateFlow<List<DeviceInfo>> get() = wsClient.onDeviceList

    private val _commandAck = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)
    val commandAck: SharedFlow<Pair<String, Boolean>> = _commandAck

    // ── 内部状态 ──────────────────────────────────────────────
    private val wsClient = WebSocketClient()
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var nm: NotificationManager
    private val notifIdCounter = AtomicInteger(1000)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var screenshotCache: ScreenshotCache
    private lateinit var networkMonitor: NetworkMonitor  // 网络状态监听

    // ── 设备参数缓存（记录每台设备最后下发的值）──────────────
    private val deviceConfigs = mutableMapOf<String, DeviceConfig>()

    // ── 截图数据（拦截缓存后转发）────────────────────────────
    private val _onScreenshotData = MutableSharedFlow<ScreenshotData>(extraBufferCapacity = 8)
    val onScreenshotData: SharedFlow<ScreenshotData> = _onScreenshotData

    fun requestScreenshot(alertId: String, deviceId: String): Boolean =
        wsClient.requestScreenshot(alertId, deviceId)

    // ── 生命周期 ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        NotificationHelper.createChannels(this)
        settingsRepo = SettingsRepository(applicationContext)
        screenshotCache = ScreenshotCache(applicationContext)
        networkMonitor = NetworkMonitor(applicationContext)

        // 注入网络检测器：心跳循环中主动检测网络可达性
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        wsClient.networkChecker = { cm.activeNetwork != null }

        // 启动前台通知
        startForeground(
            NotificationHelper.FOREGROUND_NOTIF_ID,
            NotificationHelper.buildForegroundNotification(this, "正在连接...")
        )

        // 获取 WakeLock 防止后台时被 Doze 挂起
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VisionGuard:WebSocket"
        )
        wakeLock?.acquire(30 * 60 * 1000L)

        // 订阅 WS 状态 → 更新前台通知 + 续期 WakeLock
        lifecycleScope.launch {
            wsClient.connectionState.collect { state ->
                // 每次状态变化时续期 WakeLock（防止 30 分钟超时释放）
                wakeLock?.let { wl ->
                    if (wl.isHeld) wl.release()
                    wl.acquire(30 * 60 * 1000L)
                }
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

        // 订阅设备列表（仅日志，实际状态由 wsClient.onDeviceList 代理）
        lifecycleScope.launch {
            wsClient.onDeviceList.collect { devices ->
                Log.d(TAG, "设备列表更新: ${devices.size} 台 [${
                    devices.joinToString { "${it.deviceName}(${if (it.online) "在" else "离"})" }
                }]")
            }
        }

        // 订阅命令回执
        lifecycleScope.launch {
            wsClient.onCommandAck.collect { ack ->
                _commandAck.emit(ack)
            }
        }

        // 订阅截图数据：缓存到磁盘后转发给 UI
        lifecycleScope.launch {
            wsClient.onScreenshotData.collect { data ->
                try {
                    val bytes = Base64.decode(data.imageBase64, Base64.DEFAULT)
                    screenshotCache.save(data.alertId, bytes)
                } catch (_: Exception) { }
                _onScreenshotData.emit(data)
            }
        }

        // 读取持久化 deviceId 后连接（使用硬编码的 URL 和 Key）
        lifecycleScope.launch {
            val deviceId = settingsRepo.ensureDeviceId()
            wsClient.connect(AppConstants.SERVER_URL, AppConstants.API_KEY, deviceId)
        }

        networkMonitor.register(
            onAvailable = {
                Log.i(TAG, "网络恢复 → 尝试立即重连")
                wsClient.onNetworkAvailable()
            },
            onLost = {
                Log.i(TAG, "网络断开 → 通知 WS 客户端")
                wsClient.onNetworkLost()
            }
        )
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
        networkMonitor.unregister()  // 注销网络监听
        wsClient.disconnect()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    // ── 公开 API ──────────────────────────────────────────────

    fun sendCommand(targetDeviceId: String, command: String) {
        wsClient.sendCommand(targetDeviceId, command)
    }

    fun sendSetConfig(targetDeviceId: String, key: String, value: String) {
        wsClient.sendSetConfig(targetDeviceId, key, value)
        // 同步更新本地参数缓存
        val current = deviceConfigs.getOrPut(targetDeviceId) { DeviceConfig() }
        deviceConfigs[targetDeviceId] = when (key) {
            "cooldown" -> current.copy(cooldown = value.toIntOrNull() ?: current.cooldown)
            "confidence" -> current.copy(confidence = value.toDoubleOrNull() ?: current.confidence)
            "targets" -> current.copy(targets = value)
            else -> current
        }
    }

    /** 查询设备最后下发的参数配置 */
    fun getDeviceConfig(deviceId: String): DeviceConfig? = deviceConfigs[deviceId]

    /** 手动重试连接（UI 重试按钮调用） */
    fun reconnect() {
        lifecycleScope.launch {
            val deviceId = settingsRepo.ensureDeviceId()
            wsClient.connect(AppConstants.SERVER_URL, AppConstants.API_KEY, deviceId)
        }
    }

    fun clearAlerts() {
        _alerts.value = emptyList()
        screenshotCache.clearAll()
    }

    /** 查询截图缓存文件，未缓存返回 null */
    fun getScreenshotFile(alertId: String): File? = screenshotCache.getFile(alertId)

    // ── 通知发送 ──────────────────────────────────────────────

    private fun sendAlertNotification(alert: AlertMessage) {
        val notifId = notifIdCounter.incrementAndGet()
        val notif = NotificationHelper.buildAlertNotification(
            this@AlertForegroundService, alert, notifId, null
        )
        nm.notify(notifId, notif)
        alert.notifiedAt = com.xgwnje.visionguard_android.util.NtpSync.now()
        // 更新分组摘要通知（20 台设备同时告警时防止通知栏泛滥）
        nm.notify(NotificationHelper.ALERT_SUMMARY_NOTIF_ID,
            NotificationHelper.buildAlertSummaryNotification(this@AlertForegroundService))
    }
}
