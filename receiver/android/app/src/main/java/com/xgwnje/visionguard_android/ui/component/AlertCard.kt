package com.xgwnje.visionguard_android.ui.component

// ┌─────────────────────────────────────────────────────────┐
// │ AlertCard.kt                                            │
// │ 角色：报警列表中的单条卡片，纯文本展示（无缩略图）        │
// │ 说明：截图改为详情页按需从检测端 WS 拉取，列表不预加载   │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.data.model.cocoLabelZh
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun AlertCard(
    alert: AlertMessage,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态指示器：红色圆点
            Icon(
                imageVector = Icons.Default.Circle,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
                tint = Color.Red
            )
            Spacer(modifier = Modifier.width(4.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alert.deviceName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                // 检测标签摘要
                val labelsText = alert.detections.take(3).joinToString("  ") {
                    "${cocoLabelZh(it.label)} ${(it.confidence * 100).toInt()}%"
                }
                Text(
                    text = labelsText.ifEmpty { "无检测结果" },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTimestamp(alert.timestamp),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

internal fun formatTimestamp(iso: String): String {
    return try {
        val parser = if (iso.contains("Z")) {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        }
        val date = parser.parse(iso) ?: return iso
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        formatter.format(date)
    } catch (_: Exception) {
        iso.substringAfter('T').substringBefore('.').take(8)
    }
}
