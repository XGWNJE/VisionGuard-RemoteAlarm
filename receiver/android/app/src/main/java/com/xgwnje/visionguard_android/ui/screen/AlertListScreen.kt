package com.xgwnje.visionguard_android.ui.screen

// ┌─────────────────────────────────────────────────────────┐
// │ AlertListScreen.kt                                      │
// │ 角色：主界面，显示实时报警列表                             │
// │ 布局：标题栏 + LazyColumn 列表                         │
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.ui.component.AlertCard
import com.xgwnje.visionguard_android.ui.viewmodel.AlertViewModel

@Composable
fun AlertListScreen(
    service: AlertForegroundService,
    onAlertClick: (String) -> Unit   // alertId
) {
    val alertVm: AlertViewModel = viewModel(factory = AlertViewModel.Factory(service))
    val alerts by alertVm.alerts.collectAsState()

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        start = 16.dp,
                        end = 16.dp
                    )
            ) {
                Text(
                    text = "警报",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                        items = alerts.filter { it.alertId.isNotEmpty() },  // 已保证最新在顶，过滤非法项
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
