package com.xgwnje.visionguard_android.ui.screen

// ┌─────────────────────────────────────────────────────────┐
// │ DeviceListScreen.kt                                     │
// │ 角色：设备列表 + 反向控制（暂停/恢复/停止报警/参数调节）   │
// │ 修复：ack 区分成功/失败；显示具体失败原因                 │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xgwnje.visionguard_android.data.model.DeviceConfig
import com.xgwnje.visionguard_android.data.remote.WsState
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.ui.component.DeviceCard
import com.xgwnje.visionguard_android.ui.viewmodel.DeviceViewModel

@Composable
fun DeviceListScreen(
    service: AlertForegroundService
) {
    val deviceVm: DeviceViewModel = viewModel(factory = DeviceViewModel.Factory(service))
    val devices by deviceVm.devices.collectAsState()
    val wsState by service.connectionState.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    // 监听命令回执，区分成功/失败显示不同 Snackbar
    LaunchedEffect(Unit) {
        deviceVm.commandAck.collect { (cmd, success) ->
            val msg = if (success)
                "✓ 命令已执行：$cmd"
            else
                "✕ 命令失败：$cmd"
            snackbarHost.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        start = 16.dp,
                        end = 16.dp
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "在线设备",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHost) { data ->
                val isError = data.visuals.message.startsWith("✕")
                Snackbar(
                    snackbarData = data,
                    // 成功：深绿；失败：深红。硬编码颜色确保亮/暗模式下白字对比度始终足够
                    containerColor = if (isError) Color(0xFFC62828) else Color(0xFF2E7D32),
                    contentColor = Color.White,
                    actionColor = Color.White
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (wsState == WsState.CONNECTED) "暂无设备在线" else "等待连接...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(devices, key = { it.deviceId }) { device ->
                        DeviceCard(
                            device = device,
                            initialConfig = DeviceConfig(
                                cooldown = device.cooldown,
                                confidence = device.confidence,
                                targets = device.targets
                            ),
                            onCommand = { command ->
                                deviceVm.sendCommand(device.deviceId, command)
                            },
                            onSetConfig = { key, value ->
                                deviceVm.sendSetConfig(device.deviceId, key, value)
                            }
                        )
                    }
                }
            }
        }
    }
}
