package com.xgwnje.visionguard_android.ui.screen

// ┌─────────────────────────────────────────────────────────┐
// │ AlertListScreen.kt                                      │
// │ 角色：主界面，显示实时报警列表                             │
// │ 布局：顶部 ConnectionBanner + 清除按钮 + LazyColumn 列表  │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.ui.component.AlertCard
import com.xgwnje.visionguard_android.ui.component.ConnectionBanner
import com.xgwnje.visionguard_android.ui.viewmodel.AlertViewModel
import com.xgwnje.visionguard_android.ui.viewmodel.DeviceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertListScreen(
    service: AlertForegroundService,
    onAlertClick: (String) -> Unit   // alertId
) {
    val alertVm: AlertViewModel = viewModel(factory = AlertViewModel.Factory(service))
    val deviceVm: DeviceViewModel = viewModel(factory = DeviceViewModel.Factory(service))

    val alerts by alertVm.alerts.collectAsState()
    val devices by deviceVm.devices.collectAsState()
    val wsState by service.connectionState.collectAsState()

    val onlineCount = devices.count { it.online }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("警报") },
                actions = {
                    IconButton(onClick = { alertVm.clearAlerts() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ConnectionBanner(state = wsState, onlineCount = onlineCount)

            if (alerts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无报警记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = alerts.asReversed(),  // 最新在顶
                        key = { it.alertId }
                    ) { alert ->
                        AlertCard(
                            alert = alert,
                            onClick = { onAlertClick(alert.alertId) }
                        )
                    }
                }
            }
        }
    }
}
