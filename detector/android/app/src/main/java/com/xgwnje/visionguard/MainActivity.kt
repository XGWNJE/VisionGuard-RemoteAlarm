package com.xgwnje.visionguard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.xgwnje.visionguard.ui.screen.MonitorScreen
import com.xgwnje.visionguard.ui.screen.SettingsScreen
import com.xgwnje.visionguard.ui.theme.VisionguardTheme
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
    val scope = rememberCoroutineScope()
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
    var configLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        if (!configLoaded && service != null) {
            val cooldown = safeSettingsRepository.getCooldown()
            val confidence = safeSettingsRepository.getConfidence()
            val targetsStr = safeSettingsRepository.getTargets()
            val model = safeSettingsRepository.getSelectedModel()
            val samplingRate = safeSettingsRepository.getTargetSamplingRate()
            val useHighRes = safeSettingsRepository.getUseHighResolution()
            val inputSize = if (useHighRes) 640 else 320
            config = MonitorConfig(
                confidence = confidence,
                cooldownMs = cooldown * 1000L,
                targets = targetsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                targetSamplingRate = samplingRate,
                inputSize = inputSize,
                modelName = model,
                useHighResolution = useHighRes
            )
            configLoaded = true
        }
    }

    if (!isBound || service == null || !configLoaded) {
        BoxLoading(modifier = modifier)
        return
    }

    val connectionState by service.connectionState.collectAsState()
    val isMonitoring by service.isMonitoring.collectAsState()
    val lastAlertFrame by service.lastAlertFrame.collectAsState()
    val isReady by service.isReady.collectAsState()
    val actualSamplingRate by service.actualSamplingRate.collectAsState()
    val lastAlertPushTime by service.lastAlertPushTime.collectAsState()

    // 保存配置到 DataStore
    val saveConfig: (MonitorConfig) -> Unit = { newConfig ->
        config = newConfig
        service.updateConfig(newConfig)
        scope.launch {
            safeSettingsRepository.setConfidence(newConfig.confidence)
            safeSettingsRepository.setCooldown((newConfig.cooldownMs / 1000).toInt())
            safeSettingsRepository.setTargets(newConfig.targets.joinToString(","))
            safeSettingsRepository.setSelectedModel(newConfig.modelName)
            safeSettingsRepository.setTargetSamplingRate(newConfig.targetSamplingRate)
            safeSettingsRepository.setUseHighResolution(newConfig.useHighResolution)
        }
        Unit
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
                    onRequestSnapshot = { service.requestSnapshot() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    config = config,
                    onConfigChange = saveConfig,
                    connectionState = connectionState,
                    onReconnect = { service.reconnect() }
                )
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
