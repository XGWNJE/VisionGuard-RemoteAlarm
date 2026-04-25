package com.xgwnje.visionguard.service

// ┌─────────────────────────────────────────────────────────┐
// │ DetectorForegroundService.kt                            │
// │ 角色：前台服务，Android 检测端核心                       │
// │ 职责：CameraX 绑定、ONNX 推理、报警推送、生命周期管理    │
// │ 约束：无实时 Preview，仅 ImageAnalysis；默认 2 FPS       │
// └─────────────────────────────────────────────────────────┘

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xgwnje.visionguard.AppConstants
import com.xgwnje.visionguard.data.model.AlertEvent
import com.xgwnje.visionguard.data.model.MonitorConfig
import com.xgwnje.visionguard.data.model.WsCommandMessage
import com.xgwnje.visionguard.data.model.WsSetConfigMessage
import com.xgwnje.visionguard.data.remote.WsState
import com.xgwnje.visionguard.data.repository.SettingsRepository
import com.xgwnje.visionguard.inference.ImagePreprocessor
import com.xgwnje.visionguard.inference.OnnxInferenceEngine
import com.xgwnje.visionguard.inference.SocWhitelist
import com.xgwnje.visionguard.inference.YoloOutputParser
import com.xgwnje.visionguard.util.LogManager
import com.xgwnje.visionguard.util.NotificationHelper
import com.xgwnje.visionguard.util.NtpSync
import com.xgwnje.visionguard.util.ScreenshotCache
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

private const val TAG = "VG_Service"

class DetectorForegroundService : LifecycleService() {

    // ── 对外暴露的状态 ─────────────────────────────────────────
    private val _connectionState = MutableStateFlow(WsState.DISCONNECTED)
    val connectionState: StateFlow<WsState> = _connectionState.asStateFlow()

    private val _lastAlertFrame = MutableStateFlow<Bitmap?>(null)
    val lastAlertFrame: StateFlow<Bitmap?> = _lastAlertFrame.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _actualSamplingRate = MutableStateFlow(0f)
    val actualSamplingRate: StateFlow<Float> = _actualSamplingRate.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /** 最近推送报警的时间戳（受冷却期控制，仅实际推送时更新） */
    private val _lastAlertPushTime = MutableStateFlow<String?>(null)
    val lastAlertPushTime: StateFlow<String?> = _lastAlertPushTime.asStateFlow()

    /** 当前配置（供 UI 同步远程变更） */
    private val _currentConfigFlow = MutableStateFlow(MonitorConfig())
    val currentConfigFlow: StateFlow<MonitorConfig> = _currentConfigFlow.asStateFlow()

    // ── 核心组件 ──────────────────────────────────────────────
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var serverPushService: ServerPushService
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var screenshotCache: ScreenshotCache

    private lateinit var inferenceEngine: OnnxInferenceEngine
    private lateinit var preprocessor: ImagePreprocessor
    private lateinit var parser: YoloOutputParser
    private lateinit var alertService: AlertService
    private lateinit var monitorService: MonitorService

    // ── CameraX ──────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── 电源锁 ───────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── 服务 Scope ───────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 当前配置 ─────────────────────────────────────────────
    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig()

    // ── Binder ───────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): DetectorForegroundService = this@DetectorForegroundService
    }

    private val binder = LocalBinder()

    // ═════════════════════════════════════════════════════════
    // Service 生命周期
    // ═════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DetectorForegroundService onCreate")

        // 1. 通知渠道
        NotificationHelper.createChannels(this)

        // 2. 初始化数据层
        settingsRepo = SettingsRepository(this)
        serverPushService = ServerPushService(this, settingsRepo, serviceScope)
        networkMonitor = NetworkMonitor(this)
        screenshotCache = ScreenshotCache(this)

        // 3. 初始化推理引擎
        inferenceEngine = OnnxInferenceEngine(this)

        // 4. 初始化日志管理器（注入 WS 客户端用于 ERROR 上报）
        LogManager.init(serverPushService.wsClient)

        // 5. 根据设置 + SoC 白名单决定模型和分辨率
        serviceScope.launch {
            initModelAndServices()
        }

        // 6. 启动前台通知
        startForeground(
            NotificationHelper.FOREGROUND_NOTIF_ID,
            NotificationHelper.buildForegroundNotification(this, "初始化中...")
        )

        // 7. 获取 PARTIAL_WAKE_LOCK
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VisionGuard::DetectorWakeLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 分钟，持续监控中会重新 acquire
        }

        // 8. 连接 WebSocket
        serverPushService.connect()

        // 9. 订阅连接状态 → 更新前台通知
        serviceScope.launch {
            serverPushService.connectionState.collect { state ->
                _connectionState.value = state
                updateForegroundNotification(state)
            }
        }

        // 11. 订阅远程命令
        serviceScope.launch {
            serverPushService.onCommand.collect { command ->
                handleRemoteCommand(command)
            }
        }

        // 12. 订阅远程配置变更
        serviceScope.launch {
            serverPushService.onSetConfig.collect { configMsg ->
                handleRemoteSetConfig(configMsg)
            }
        }

        // 13. NTP 时钟同步
        serviceScope.launch {
            NtpSync.sync()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand: START_STICKY")
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "DetectorForegroundService onDestroy")

        // 停止监控
        stopMonitoring()

        // 释放 wakeLock
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放 wakeLock 异常", e)
        }

        // 断开 WS
        serverPushService.disconnect()

        // 关闭 ONNX session
        inferenceEngine.close()

        // 注销网络监听
        networkMonitor.unregister()

        // 关闭 CameraX executor
        cameraExecutor.shutdown()
    }

    // ═════════════════════════════════════════════════════════
    // 公共 API
    // ═════════════════════════════════════════════════════════

    /** 启动监控 */
    fun startMonitoring(config: MonitorConfig) {
        serviceScope.launch {
            try {
                // 如果需要切换模型，先重新加载
                val modelChanged = config.modelName != currentConfig.modelName ||
                        config.inputSize != currentConfig.inputSize

                if (modelChanged || !inferenceEngine.isLoaded) {
                    val success = loadModel(config.modelName, config.inputSize)
                    if (!success) {
                        Log.e(TAG, "模型加载失败，无法启动监控")
                        return@launch
                    }
                }

                currentConfig = config

                // 更新 preprocessor / parser（分辨率可能变化）
                preprocessor = ImagePreprocessor(config.inputSize)
                parser = YoloOutputParser(config.inputSize)
                monitorService.updateComponents(preprocessor, parser)

                // 启动 MonitorService
                monitorService.start(config)

                // 绑定 CameraX
                bindCameraX()

                _isMonitoring.value = true
                updateWsHeartbeatStatus()

                Log.i(TAG, "监控已启动: ${config.modelName}_${config.inputSize}")
            } catch (e: Exception) {
                Log.e(TAG, "启动监控失败", e)
            }
        }
    }

    /** 手动重连服务器 */
    fun reconnect() {
        serviceScope.launch {
            serverPushService.disconnect()
            delay(500)
            serverPushService.connect()
        }
    }

    /** 手动抓拍当前帧到预览画布 */
    fun requestSnapshot() {
        monitorService.requestSnapshot()
    }

    /** 停止监控 */
    fun stopMonitoring() {
        try {
            monitorService.stop()
            unbindCameraX()
            _isMonitoring.value = false
            _actualSamplingRate.value = 0f
            _lastAlertFrame.value = null
            updateWsHeartbeatStatus()
            Log.i(TAG, "监控已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止监控异常", e)
        }
    }

    /** 热更新配置 */
    fun updateConfig(config: MonitorConfig) {
        serviceScope.launch {
            try {
                val modelChanged = config.modelName != currentConfig.modelName ||
                        config.inputSize != currentConfig.inputSize

                if (modelChanged) {
                    val success = loadModel(config.modelName, config.inputSize)
                    if (!success) {
                        Log.e(TAG, "切换模型失败，保持原配置")
                        return@launch
                    }
                    preprocessor = ImagePreprocessor(config.inputSize)
                    parser = YoloOutputParser(config.inputSize)
                    monitorService.updateComponents(preprocessor, parser)
                }

                currentConfig = config
                _currentConfigFlow.value = config
                monitorService.updateConfig(config)
                updateWsHeartbeatStatus()

                Log.i(TAG, "配置已热更新")
            } catch (e: Exception) {
                Log.e(TAG, "更新配置失败", e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════
    // 内部初始化
    // ═════════════════════════════════════════════════════════

    private suspend fun initModelAndServices() {
        val selectedModel = settingsRepo.getSelectedModel()
        val useHighRes = settingsRepo.getUseHighResolution()
        val isHighEnd = SocWhitelist.isHighEndSoc()
        // 仅当用户手动启用且设备支持时才使用 640
        var inputSize = if (useHighRes && isHighEnd) 640 else 320

        preprocessor = ImagePreprocessor(inputSize)
        parser = YoloOutputParser(inputSize)
        alertService = AlertService(serviceScope)

        var success = loadModel(selectedModel, inputSize)

        // 如果高分辨率加载失败，回退到 320
        if (!success && inputSize == 640) {
            Log.w(TAG, "640 模型加载失败，尝试回退到 320")
            inputSize = 320
            preprocessor = ImagePreprocessor(inputSize)
            parser = YoloOutputParser(inputSize)
            success = loadModel(selectedModel, inputSize)
        }

        _isReady.value = success

        // 同步 currentConfig，确保后续 startMonitoring 不会误判模型已匹配
        currentConfig = currentConfig.copy(
            modelName = selectedModel,
            inputSize = inputSize,
            useHighResolution = useHighRes
        )

        monitorService = MonitorService(
            context = this,
            inferenceEngine = inferenceEngine,
            preprocessor = preprocessor,
            parser = parser,
            alertService = alertService,
            scope = serviceScope
        )

        // 订阅最新识别帧 → 持续更新 UI（不受冷却期影响）
        serviceScope.launch {
            monitorService.latestDetectionFrame.collect { frame ->
                _lastAlertFrame.value = frame
            }
        }

        // 订阅报警事件 → 本地缓存截图 + WS 轻量推送（受冷却期控制）
        serviceScope.launch {
            alertService.alertEvents.collect { event ->
                onAlertEvent(event)
            }
        }

        // 订阅截图请求 → 从本地缓存读取并推送
        serviceScope.launch {
            serverPushService.wsClient.onRequestScreenshot.collect { alertId ->
                handleScreenshotRequest(alertId)
            }
        }

        // 订阅实际采样率
        serviceScope.launch {
            monitorService.actualSamplingRate.collect { rate ->
                _actualSamplingRate.value = rate
            }
        }

        Log.i(TAG, "模型初始化完成: ${selectedModel}_${inputSize}.onnx, success=$success")
    }

    private suspend fun loadModel(modelName: String, inputSize: Int): Boolean {
        return withContext(Dispatchers.IO) {
            val modelFileName = "${modelName}_${inputSize}.onnx"
            val success = inferenceEngine.loadModel(modelFileName, inputSize)
            _isReady.value = success
            updateWsHeartbeatStatus()
            success
        }
    }

    // ═════════════════════════════════════════════════════════
    // CameraX 绑定
    // ═════════════════════════════════════════════════════════

    private fun bindCameraX() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    val provider = cameraProvider ?: return@addListener

                    // 仅绑定 ImageAnalysis，无 Preview
                    // 使用 ResolutionSelector 限制最大分辨率，避免某些设备回退到超大分辨率
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()

                    imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(resolutionSelector)
                        .build()

                    imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                        monitorService.processFrame(imageProxy)
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        cameraSelector,
                        imageAnalysis
                    )

                    Log.i(TAG, "CameraX 已绑定（仅 ImageAnalysis）")
                } catch (e: Exception) {
                    Log.e(TAG, "CameraX 绑定失败", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(TAG, "获取 CameraProvider 失败", e)
        }
    }

    private fun unbindCameraX() {
        // CameraX 生命周期操作必须在主线程执行
        androidx.core.content.ContextCompat.getMainExecutor(this).execute {
            try {
                cameraProvider?.unbindAll()
                imageAnalysis?.clearAnalyzer()
                imageAnalysis = null
                Log.i(TAG, "CameraX 已解绑")
            } catch (e: Exception) {
                Log.w(TAG, "解绑 CameraX 异常", e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════
    // 报警处理
    // ═════════════════════════════════════════════════════════

    private fun onAlertEvent(event: AlertEvent) {
        val alertId = java.util.UUID.randomUUID().toString()
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _lastAlertPushTime.value = timeStr
        Log.i(TAG, "报警已触发: alertId=$alertId, targets=${event.detections.map { it.label }}")

        // 1. 保存截图到本地缓存（供后续 request-screenshot 响应使用）
        event.renderedFrame?.let { bmp ->
            screenshotCache.save(alertId, bmp)
        }

        // 2. 发送轻量 WS alert（无截图数据，模仿 Windows PushAlert）
        serverPushService.pushAlert(alertId, event.detections)

        // 3. 更新心跳状态
        serverPushService.wsClient.isAlarming = true
        serviceScope.launch {
            delay(currentConfig.cooldownMs)
            serverPushService.wsClient.isAlarming = false
        }
    }

    /** 响应接收端的截图请求：从本地缓存读取并 base64 推送 */
    private fun handleScreenshotRequest(alertId: String) {
        serviceScope.launch {
            try {
                val bytes = screenshotCache.readBytes(alertId)
                if (bytes == null) {
                    Log.w(TAG, "截图请求: 本地缓存未命中 alertId=$alertId")
                    return@launch
                }
                val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                // 估算宽高（从 JPEG 解析太复杂，简单估算或设为 0）
                val sent = serverPushService.wsClient.sendScreenshotData(alertId, base64, 0, 0)
                if (sent) {
                    Log.i(TAG, "截图已推送: alertId=$alertId size=${bytes.size}B")
                } else {
                    Log.w(TAG, "截图推送失败: WS 未连接 alertId=$alertId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理截图请求异常: alertId=$alertId", e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════
    // 远程命令处理
    // ═════════════════════════════════════════════════════════

    private fun handleRemoteCommand(command: WsCommandMessage) {
        Log.i(TAG, "收到远程命令: ${command.command}")
        when (command.command) {
            "pause" -> {
                // 与本地停止监控对齐：解绑 CameraX、停止 MonitorService
                stopMonitoring()
                serverPushService.sendCommandAck("pause", true)
                // 立即推送心跳，让接收端快速同步状态
                serverPushService.wsClient.sendHeartbeatNow()
            }
            "resume" -> {
                // 与本地开始监控对齐：加载模型（如需）、绑定 CameraX
                if (!isMonitoring.value) {
                    startMonitoring(currentConfig)
                }
                serverPushService.sendCommandAck("resume", true)
                // 立即推送心跳，让接收端快速同步状态
                serverPushService.wsClient.sendHeartbeatNow()
            }
            else -> {
                Log.w(TAG, "未知命令: ${command.command}")
                serverPushService.sendCommandAck(command.command, false)
            }
        }
    }

    private fun handleRemoteSetConfig(configMsg: WsSetConfigMessage) {
        Log.i(TAG, "收到远程配置变更: ${configMsg.key}=${configMsg.value}")
        serviceScope.launch {
            try {
                when (configMsg.key) {
                    "cooldown" -> {
                        val raw = configMsg.value.toIntOrNull() ?: return@launch
                        val value = raw.coerceIn(1, 300)
                        if (value != raw) {
                            Log.w(TAG, "cooldown 值 $raw 超出范围，已裁剪为 $value")
                        }
                        settingsRepo.setCooldown(value)
                        val newConfig = currentConfig.copy(cooldownMs = value * 1000L)
                        updateConfig(newConfig)
                    }
                    "confidence" -> {
                        val raw = configMsg.value.toFloatOrNull() ?: return@launch
                        val value = raw.coerceIn(0.1f, 0.95f)
                        if (value != raw) {
                            Log.w(TAG, "confidence 值 $raw 超出范围，已裁剪为 $value")
                        }
                        settingsRepo.setConfidence(value)
                        val newConfig = currentConfig.copy(confidence = value)
                        updateConfig(newConfig)
                    }
                    "targets" -> {
                        settingsRepo.setTargets(configMsg.value)
                        val targets = configMsg.value.split(",").map { it.trim() }.toSet()
                        val newConfig = currentConfig.copy(targets = targets)
                        updateConfig(newConfig)
                    }
                    else -> {
                        Log.w(TAG, "未知配置项: ${configMsg.key}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理远程配置变更失败", e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════
    // 心跳状态同步
    // ═════════════════════════════════════════════════════════

    private fun updateWsHeartbeatStatus() {
        val client = serverPushService.wsClient
        client.isMonitoring = _isMonitoring.value
        client.isReady = _isReady.value
        client.heartbeatCooldown = (currentConfig.cooldownMs / 1000).toInt()
        client.heartbeatConfidence = currentConfig.confidence.toDouble()
        client.heartbeatTargets = currentConfig.targets.joinToString(",")
    }

    // ═════════════════════════════════════════════════════════
    // 通知更新
    // ═════════════════════════════════════════════════════════

    private fun updateForegroundNotification(state: WsState) {
        val stateText = when (state) {
            WsState.CONNECTED -> if (_isMonitoring.value) "监控中 | 已连接" else "就绪 | 已连接"
            WsState.CONNECTING -> "连接中..."
            WsState.AUTH_FAILED -> "认证失败"
            WsState.DISCONNECTED -> "未连接"
        }
        val notification = NotificationHelper.buildForegroundNotification(this, stateText)
        startForeground(NotificationHelper.FOREGROUND_NOTIF_ID, notification)
    }
}
