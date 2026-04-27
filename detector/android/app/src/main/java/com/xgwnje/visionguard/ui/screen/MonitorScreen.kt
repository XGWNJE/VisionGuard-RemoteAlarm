package com.xgwnje.visionguard.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.data.model.MonitorConfig
import com.xgwnje.visionguard.data.remote.WsState
import com.xgwnje.visionguard.ui.component.AlertFrameViewer
import com.xgwnje.visionguard.ui.component.StatusCard

@Composable
fun MonitorScreen(
    config: MonitorConfig,
    connectionState: WsState,
    isMonitoring: Boolean,
    isReady: Boolean,
    lastAlertFrame: Bitmap?,
    lastAlertPushTime: String?,
    actualSamplingRate: Float,
    onToggleMonitoring: () -> Unit,
    onOpenMaskEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "VisionGuard 监控",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 报警帧暂显区域
            AlertFrameViewer(
                alertFrame = lastAlertFrame,
                modifier = Modifier.fillMaxWidth()
            )

            // 状态卡片
            StatusCard(
                connectionState = connectionState,
                isMonitoring = isMonitoring,
                targetSamplingRate = config.targetSamplingRate,
                actualSamplingRate = actualSamplingRate,
                isReady = isReady,
                modelName = config.modelName,
                inputSize = config.inputSize,
                lastAlertTime = lastAlertPushTime
            )

            // 启停按钮
            Button(
                onClick = onToggleMonitoring,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMonitoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                enabled = isReady
            ) {
                Text(
                    text = if (isMonitoring) "停止监控" else "开始监控",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 设置监控区域按钮：仅在未监控时可用
            OutlinedButton(
                onClick = onOpenMaskEditor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !isMonitoring
            ) {
                Text(
                    text = "设置监控区域",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
