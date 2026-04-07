package com.xgwnje.visionguard_android.ui.component

// ┌─────────────────────────────────────────────────────────┐
// │ AlertCard.kt                                            │
// │ 角色：报警列表中的单条卡片，显示设备名/标签/缩略图/时间    │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xgwnje.visionguard_android.AppConstants
import com.xgwnje.visionguard_android.data.model.AlertMessage

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
            // 缩略图
            if (alert.screenshotUrl.isNotEmpty()) {
                val imgUrl = "${AppConstants.SERVER_URL}${alert.screenshotUrl}?key=${AppConstants.API_KEY}"
                AsyncImage(
                    model = imgUrl,
                    contentDescription = "截图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔴",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = alert.deviceName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
                // 检测标签摘要
                val labelsText = alert.detections.take(3).joinToString("  ") {
                    "${it.label} ${(it.confidence * 100).toInt()}%"
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

private fun formatTimestamp(iso: String): String {
    return try {
        // "2024-01-15T14:30:05.123Z" → "14:30:05"
        iso.substringAfter('T').substringBefore('.').take(8)
    } catch (_: Exception) { iso }
}
