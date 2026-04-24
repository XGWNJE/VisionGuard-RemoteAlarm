package com.xgwnje.visionguard.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.data.remote.WsState

@Composable
fun StatusCard(
    connectionState: WsState,
    isMonitoring: Boolean,
    targetSamplingRate: Int,
    actualSamplingRate: Float,
    isReady: Boolean,
    modelName: String,
    inputSize: Int,
    lastAlertTime: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 顶部一行：连接状态 + 监控状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionIndicator(connectionState)
                MonitoringBadge(isMonitoring)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 中间一行：采样率 + 模型就绪状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isUnderperforming = isMonitoring && actualSamplingRate > 0f && actualSamplingRate < targetSamplingRate * 0.8f
                Column {
                    Text(
                        text = "采样率: ${targetSamplingRate}次/秒",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isMonitoring) {
                        Text(
                            text = if (isUnderperforming) {
                                "实际 ${String.format("%.1f", actualSamplingRate)} 次/秒 (性能不足)"
                            } else {
                                "实际 ${String.format("%.1f", actualSamplingRate)} 次/秒"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isUnderperforming) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                ModelReadyBadge(isReady, modelName, inputSize)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 底部一行：最近报警时间
            Text(
                text = if (lastAlertTime != null) {
                    "最近报警: $lastAlertTime"
                } else {
                    "无报警"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun ConnectionIndicator(state: WsState) {
    val (dotColor, label) = when (state) {
        WsState.CONNECTED -> Color(0xFF4CAF50) to "已连接"
        WsState.CONNECTING -> Color(0xFFFFC107) to "连接中"
        WsState.DISCONNECTED -> Color(0xFF9E9E9E) to "未连接"
        WsState.AUTH_FAILED -> Color(0xFFF44336) to "认证失败"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonitoringBadge(isMonitoring: Boolean) {
    val backgroundColor = if (isMonitoring) {
        Color(0xFF4CAF50).copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    }
    val textColor = if (isMonitoring) {
        Color(0xFF4CAF50)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    val label = if (isMonitoring) "运行中" else "已停止"

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor
        )
    }
}

@Composable
private fun ModelReadyBadge(isReady: Boolean, modelName: String, inputSize: Int) {
    val label = if (isReady) {
        "$modelName | ${inputSize}×$inputSize"
    } else {
        "模型加载中"
    }
    val color = if (isReady) {
        Color(0xFF4CAF50)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = color
    )
}
