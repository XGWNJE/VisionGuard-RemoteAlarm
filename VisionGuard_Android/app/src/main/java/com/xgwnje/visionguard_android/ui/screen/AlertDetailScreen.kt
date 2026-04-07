package com.xgwnje.visionguard_android.ui.screen

// ┌─────────────────────────────────────────────────────────┐
// │ AlertDetailScreen.kt                                    │
// │ 角色：报警详情，全屏截图 + 检测标签列表                   │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xgwnje.visionguard_android.AppConstants
import com.xgwnje.visionguard_android.service.AlertForegroundService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(
    service: AlertForegroundService,
    alertId: String,
    onBack: () -> Unit
) {
    val alerts by service.alerts.collectAsState()
    val alert = alerts.find { it.alertId == alertId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(alert?.deviceName ?: "报警详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (alert == null) {
            Text(
                text = "报警记录不存在",
                modifier = Modifier.padding(padding).padding(16.dp)
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // 全屏截图
            if (alert.screenshotUrl.isNotEmpty()) {
                val imgUrl = "${AppConstants.SERVER_URL}${alert.screenshotUrl}?key=${AppConstants.API_KEY}"
                AsyncImage(
                    model = imgUrl,
                    contentDescription = "报警截图",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = alert.deviceName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = alert.timestamp,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("检测目标", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))

                alert.detections.forEach { d ->
                    Text(
                        text = "• ${d.label}  ${(d.confidence * 100).toInt()}%  " +
                               "[x=${d.bbox.x}, y=${d.bbox.y}, w=${d.bbox.w}, h=${d.bbox.h}]",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                if (alert.detections.isEmpty()) {
                    Text("无检测结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
