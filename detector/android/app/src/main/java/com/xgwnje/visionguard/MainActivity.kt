package com.xgwnje.visionguard

import android.Manifest
import android.util.Log
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xgwnje.visionguard.data.model.MonitorConfig
import com.xgwnje.visionguard.data.repository.SettingsRepository
import com.xgwnje.visionguard.service.DetectorForegroundService
import com.xgwnje.visionguard.ui.screen.MaskEditorScreen
import com.xgwnje.visionguard.ui.screen.MonitorScreen
import com.xgwnje.visionguard.ui.screen.SettingsScreen
import com.xgwnje.visionguard.ui.theme.VisionguardTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<DetectorForegroundService?>(null)
    private var isBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? DetectorForegroundService.LocalBinder
            service = localBinder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // 权限请求结果处理，若必要权限被拒绝可在此提示
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestRequiredPermissions()
        ensureServiceRunning()
        bindService()

        setContent {
            VisionguardTheme {
                MainScreen(
                    service = service,
                    isBound = isBound
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            service = null
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun ensureServiceRunning() {
        val intent = Intent(this, DetectorForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindService() {
        val intent = Intent(this, DetectorForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
}

@Composable
private fun MainScreen(
    modifier: Modifier = Modifier,
    service: DetectorForegroundService?,
    isBound: Boolean
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = service?.applicationContext
    val settingsRepository = remember(context) {
        context?.let { SettingsRepository(it) }
    }
    val safeSettingsRepository = settingsRepository

    if (safeSettingsRepository == null) {
        BoxLoading(modifier = modifier)
        return
    }

    // 从 SettingsRepository 加载初始配置
    var config by remember { mutableStateOf(MonitorConfig()) }
    var deviceName by remember { mutableStateOf("Android-Detector") }
    var configLoaded by remember { mutableStateOf(false) }

    // 遮罩编辑器状态
    var showMaskEditor by remember { mutableStateOf(false) }
    // 正在获取预览帧（用于编辑器背景）
    var isLoadingPreview by remember { mutableStateOf(false) }

    // 返回键优先关闭编辑器
    BackHandler(enabled = showMaskEditor) {
        showMaskEditor = false
    }

    LaunchedEffect(service) {
        if (!configLoaded && service != null) {
            try {
                val cooldown = safeSettingsRepository.getCooldown()
                val confidence = safeSettingsRepository.getConfidence()
                val targetsStr = safeSettingsRepository.getTargets()
                val model = safeSettingsRepository.getSelectedModel()
                val samplingRate = safeSettingsRepository.getTargetSamplingRate()
                val useHighRes = safeSettingsRepository.getUseHighResolution()
                val maskRegions = safeSettingsRepository.getMaskRegions()
                val digitalZoom = safeSettingsRepository.getDigitalZoom()
                val name = safeSettingsRepository.getDeviceName()
                val inputSize = if (useHighRes) 640 else 320
                config = MonitorConfig(
                    confidence = confidence,
                    cooldownMs = cooldown * 1000L,
                    targets = targetsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    targetSamplingRate = samplingRate,
                    inputSize = inputSize,
                    modelName = model,
                    useHighResolution = useHighRes,
                    maskRegions = maskRegions,
                    digitalZoom = digitalZoom
                )
                deviceName = name
                configLoaded = true
                Log.d("VG_Persist", "配置已从 DataStore 加载: confidence=$confidence, " +
                    "cooldown=${cooldown}s, model=$model, zoom=$digitalZoom, masks=${maskRegions.size}")
            } catch (e: Exception) {
                Log.e("VG_Persist", "配置加载失败，使用默认值", e)
                configLoaded = true
            }
        }
    }

    if (!isBound || service == null || !configLoaded) {
        BoxLoading(modifier = modifier)
        return
    }

    val connectionState by service.connectionState.collectAsState()
    val isMonitoring by service.isMonitoring.collectAsState()
    val lastAlertFrame by service.lastAlertFrame.collectAsState()
    val serviceAspectRatio by service.frameAspectRatio.collectAsState()
    // 优先使用实际帧的宽高比，确保遮罩编辑器画布与真实帧一致
    val editorAspectRatio = lastAlertFrame?.let {
        it.width.toFloat() / it.height.toFloat()
    } ?: serviceAspectRatio
    val isReady by service.isReady.collectAsState()
    val actualSamplingRate by service.actualSamplingRate.collectAsState()
    val lastAlertPushTime by service.lastAlertPushTime.collectAsState()

    // 预览帧到来后自动打开编辑器
    LaunchedEffect(lastAlertFrame) {
        if (isLoadingPreview && lastAlertFrame != null) {
            isLoadingPreview = false
            showMaskEditor = true
        }
    }

    // 预览帧获取超时（5 秒）
    LaunchedEffect(isLoadingPreview) {
        if (isLoadingPreview) {
            delay(5000)
            if (isLoadingPreview) {
                isLoadingPreview = false
            }
        }
    }

    // 监听 Service 中的远程配置变更，同步到 UI
    LaunchedEffect(service) {
        service.currentConfigFlow.collect { remoteConfig ->
            // 避免 Service 初始化前的默认值覆盖已加载的 DataStore 配置
            if (configLoaded && remoteConfig != config) {
                config = remoteConfig
                Log.d("VG_Persist", "远程配置同步到 UI: confidence=${remoteConfig.confidence}, zoom=${remoteConfig.digitalZoom}")
            }
        }
    }

    // 保存配置到 DataStore（不热更新 Service，统一在下次启动监控时应用）
    var saveJob by remember { mutableStateOf<Job?>(null) }

    val saveConfig: (MonitorConfig) -> Unit = { newConfig ->
        config = newConfig
        // 追踪调用源，诊断频繁触发问题
        val caller = Throwable().stackTrace.getOrNull(2)?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        Log.d("VG_Persist", "saveConfig 被调用 (caller=$caller), conf=${newConfig.confidence}, zoom=${newConfig.digitalZoom}")
        saveJob?.cancel()
        saveJob = lifecycleOwner.lifecycleScope.launch {
            delay(500) // 防抖 500ms，连续操作只保存最终状态
            try {
                // 保存当前最新的 config 状态，避免 lambda 捕获的旧值覆盖新值
                val current = config
                safeSettingsRepository.saveMonitorConfig(current)
                Log.d("VG_Persist", "配置已持久化: confidence=${current.confidence}, " +
                    "cooldown=${current.cooldownMs}ms, model=${current.modelName}, " +
                    "zoom=${current.digitalZoom}, masks=${current.maskRegions.size}")
            } catch (e: Exception) {
                Log.e("VG_Persist", "配置持久化失败", e)
            }
        }
    }

    val saveDeviceName: (String) -> Unit = { newName ->
        deviceName = newName
        lifecycleOwner.lifecycleScope.launch {
            try {
                safeSettingsRepository.setDeviceName(newName)
                Log.d("VG_Persist", "设备名已持久化: $newName")
            } catch (e: Exception) {
                Log.e("VG_Persist", "设备名持久化失败", e)
            }
        }
    }

    val navController = rememberNavController()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                NavigationBarItem(
                    icon = { Icon(Icons.Default.Videocam, contentDescription = "监控") },
                    label = { Text("监控") },
                    selected = currentDestination?.hierarchy?.any { it.route == "monitor" } == true,
                    onClick = {
                        navController.navigate("monitor") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                    label = { Text("设置") },
                    selected = currentDestination?.hierarchy?.any { it.route == "settings" } == true,
                    onClick = {
                        navController.navigate("settings") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "monitor",
            modifier = Modifier.padding(padding)
        ) {
            composable("monitor") {
                MonitorScreen(
                    config = config,
                    connectionState = connectionState,
                    isMonitoring = isMonitoring,
                    isReady = isReady,
                    lastAlertFrame = lastAlertFrame,
                    lastAlertPushTime = lastAlertPushTime,
                    actualSamplingRate = actualSamplingRate,
                    onToggleMonitoring = {
                        if (isMonitoring) {
                            service.stopMonitoring()
                        } else {
                            service.startMonitoring(config)
                        }
                    },
                    onOpenMaskEditor = {
                        // 每次都重新获取预览帧，避免二次编辑时显示旧画面
                        isLoadingPreview = true
                        service.capturePreviewFrame()
                    }
                )
            }
            composable("settings") {
                SettingsScreen(
                    config = config,
                    deviceName = deviceName,
                    onConfigChange = saveConfig,
                    onDeviceNameChange = saveDeviceName,
                    connectionState = connectionState,
                    onReconnect = { service.reconnect() }
                )
            }
        }

        // 遮罩编辑器弹窗
        if (showMaskEditor) {
            MaskEditorScreen(
                bitmap = lastAlertFrame,
                frameAspectRatio = editorAspectRatio,
                initialMasks = config.maskRegions,
                initialZoom = config.digitalZoom,
                onConfirm = { masks, zoom ->
                    showMaskEditor = false
                    val newConfig = config.copy(
                        maskRegions = masks,
                        digitalZoom = zoom
                    )
                    saveConfig(newConfig)
                },
                onDismiss = { showMaskEditor = false }
            )
        }

        // 正在获取预览帧的 loading 提示
        if (isLoadingPreview) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "正在获取摄像头画面...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "加载中...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
