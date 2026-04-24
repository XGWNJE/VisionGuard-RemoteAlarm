package com.xgwnje.visionguard_android

// ┌─────────────────────────────────────────────────────────┐
// │ MainActivity.kt                                         │
// │ 角色：NavHost 宿主 + 通知权限申请 + Service 绑定          │
// │ 路由：main(alertList / deviceList) → alertDetail         │
// │ 连接状态集成到顶部 ConnectionBanner，无独立连接页         │
// └─────────────────────────────────────────────────────────┘

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.ui.screen.AlertDetailScreen
import com.xgwnje.visionguard_android.ui.screen.AlertListScreen
import com.xgwnje.visionguard_android.ui.screen.DeviceListScreen
import com.xgwnje.visionguard_android.ui.theme.VisionGuard_AndroidTheme

class MainActivity : ComponentActivity() {

    private var boundService: AlertForegroundService? = null
    private var serviceBound by mutableStateOf(false)
    private var pendingAlertId by mutableStateOf<String?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boundService = (binder as AlertForegroundService.AlertServiceBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            boundService = null
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户选择，不强制要求 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 读取通知点击传入的 alertId
        pendingAlertId = intent.getStringExtra("alertId")

        // 申请通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 直接启动并绑定服务（服务内部读 AppConstants 连接）
        startAndBindService()

        setContent {
            VisionGuard_AndroidTheme {
                if (!serviceBound || boundService == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    VisionGuardNavHost(
                        service = boundService!!,
                        initialAlertId = pendingAlertId
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop 模式下通知点击会触发此处
        val alertId = intent.getStringExtra("alertId")
        if (alertId != null) {
            pendingAlertId = alertId
        }
    }

    private fun startAndBindService() {
        val svc = Intent(this, AlertForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        bindService(svc, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(connection)
        }
    }
}

// ── 导航主机 ──────────────────────────────────────────────────

@Composable
fun VisionGuardNavHost(
    service: AlertForegroundService,
    initialAlertId: String? = null
) {
    val navController = rememberNavController()

    // 若从通知点击进入，自动导航到报警详情
    LaunchedEffect(initialAlertId) {
        if (!initialAlertId.isNullOrEmpty()) {
            navController.navigate("alertDetail/$initialAlertId") {
                popUpTo("main") { saveState = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = "main") {

        composable("main") {
            MainScreen(
                service = service,
                onAlertClick = { alertId ->
                    navController.navigate("alertDetail/$alertId")
                }
            )
        }

        composable("alertDetail/{alertId}") { backStack ->
            val alertId = backStack.arguments?.getString("alertId") ?: ""
            AlertDetailScreen(
                service = service,
                alertId = alertId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// ── 主界面（底部 Tab 导航）────────────────────────────────────

@Composable
fun MainScreen(
    service: AlertForegroundService,
    onAlertClick: (String) -> Unit
) {
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentDest?.hierarchy?.any { it.route == "alertList" } == true,
                    onClick = {
                        tabNavController.navigate("alertList") {
                            popUpTo(tabNavController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("警报") }
                )
                NavigationBarItem(
                    selected = currentDest?.hierarchy?.any { it.route == "deviceList" } == true,
                    onClick = {
                        tabNavController.navigate("deviceList") {
                            popUpTo(tabNavController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                    label = { Text("设备") }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = tabNavController,
            startDestination = "alertList",
            modifier = Modifier.padding(padding)
        ) {
            composable("alertList") {
                AlertListScreen(
                    service = service,
                    onAlertClick = onAlertClick
                )
            }
            composable("deviceList") {
                DeviceListScreen(service = service)
            }
        }
    }
}
