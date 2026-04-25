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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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

        // 订阅 WS 状态 → 更新前台通知 + 续期 WakeLock + 连接成功后拉取历史
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

                // 连接成功后拉取历史报警（取最近 7 天内）
                if (state == WsState.CONNECTED) {
                    val since = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                    fetchHistoryAlerts(since)
                }
            }
        }

        // 订阅报警
        lifecycleScope.launch {
            wsClient.onAlert.collect { alert ->
                Log.i(TAG, "收到报警: ${alert.deviceName} - ${alert.detections.size} 个目标")
                _alerts.value = (listOf(alert) + _alerts.value).take(200)

                // 不再自动下载截图：截图按需从检测端 WS 拉取
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

    /** 从服务器拉取历史报警列表 */
    suspend fun fetchHistoryAlerts(since: Long = 0): Boolean {
        val url = "${AppConstants.SERVER_URL}/api/alerts?since=$since&limit=200"
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", AppConstants.API_KEY)
            .build()

        return try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                            if (json.get("ok")?.asBoolean == true) {
                                val alertsArr = json.getAsJsonArray("alerts")
                                val gson = com.google.gson.Gson()
                                val history = alertsArr?.map {
                                    gson.fromJson(it, AlertMessage::class.java)
                                } ?: emptyList()
                                // 合并到现有列表，去重（按 alertId）
                                val existingIds = _alerts.value.map { it.alertId }.toSet()
                                val newAlerts = history.filter { it.alertId !in existingIds }
                                if (newAlerts.isNotEmpty()) {
                                    val merged = (newAlerts + _alerts.value).distinctBy { it.alertId }
                                    _alerts.value = merged.take(200)
                                    Log.i(TAG, "历史报警已同步: ${newAlerts.size} 条")
                                }
                                true
                            } else false
                        } else false
                    } else {
                        Log.w(TAG, "历史报警拉取失败: ${response.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "历史报警拉取异常: ${e.message}")
            false
        }
    }

    /** 从服务器 HTTP 下载截图并缓存（保留作为 fallback） */
    private suspend fun downloadScreenshot(alertId: String, screenshotUrl: String) {
        val url = if (screenshotUrl.startsWith("http")) screenshotUrl
        else "${AppConstants.SERVER_URL}$screenshotUrl"
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", AppConstants.API_KEY)
            .build()

        Log.d(TAG, "开始下载截图: alertId=$alertId url=$url")
        try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            screenshotCache.save(alertId, bytes)
                            // 同时推送到 onScreenshotData，供 AlertDetailScreen 即时显示
                            val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                            _onScreenshotData.emit(
                                ScreenshotData(alertId, base64, 0, 0)
                            )
                            Log.i(TAG, "截图已下载: alertId=$alertId size=${bytes.size}")
                        }
                    } else {
                        Log.w(TAG, "截图下载失败: ${response.code} ${response.message} url=$url")
                    }
                }
            }
            // 触发 UI 重组，列表/详情页读取新缓存文件
            _alerts.value = _alerts.value.toList()
        } catch (e: Exception) {
            Log.w(TAG, "截图下载异常: alertId=$alertId ${e.message}", e)
        }
    }

    // ── 通知发送 ──────────────────────────────────────────────

    private fun sendAlertNotification(alert: AlertMessage) {
        if (alert.alertId.isEmpty() || alert.deviceId.isEmpty()) {
            Log.w(TAG, "报警消息缺少 alertId 或 deviceId，跳过通知")
            return
        }
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
