package com.xgwnje.visionguard_android.ui.screen

// ┌─────────────────────────────────────────────────────────┐
// │ AlertDetailScreen.kt                                    │
// │ 角色：报警详情，全屏截图 + 检测标签列表                   │
// └─────────────────────────────────────────────────────────┘

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard_android.data.model.cocoLabelZh
import com.xgwnje.visionguard_android.service.AlertForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(
    service: AlertForegroundService,
    alertId: String,
    onBack: () -> Unit
) {
    val alerts by service.alerts.collectAsState()
    val alert = alerts.find { it.alertId == alertId }

    var screenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var screenshotFailed by remember { mutableStateOf(false) }

    // 进入页面时：优先从缓存加载，缓存未命中再请求截图
    LaunchedEffect(alertId) {
        if (alert == null) return@LaunchedEffect

        // 先查本地缓存
        val cachedFile = service.getScreenshotFile(alertId)
        if (cachedFile != null && cachedFile.exists()) {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(cachedFile.absolutePath)
            }
            if (bitmap != null) {
                screenshotBitmap = bitmap
                return@LaunchedEffect
            }
        }

        // 缓存未命中，走网络请求
        val sent = service.requestScreenshot(alertId, alert.deviceId)
        if (!sent) {
            screenshotFailed = true
            return@LaunchedEffect
        }
        // 等待截图数据，10 秒超时
        delay(10_000)
        if (screenshotBitmap == null) {
            screenshotFailed = true
        }
    }

    // 监听截图数据（来自 Windows 经服务器转发）
    LaunchedEffect(Unit) {
        service.onScreenshotData.collect { data ->
            if (data.alertId == alertId && data.imageBase64.isNotEmpty()) {
                try {
                    val bytes = Base64.decode(data.imageBase64, Base64.DEFAULT)
                    screenshotBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(alert?.deviceName ?: "报警详情") },
                windowInsets = WindowInsets(0),
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
            // 截图（按需从 Windows 拉取）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                when {
                    screenshotBitmap != null -> {
                        Image(
                            bitmap = screenshotBitmap!!.asImageBitmap(),
                            contentDescription = "报警截图",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                        )
                    }
                    screenshotFailed -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.ImageNotSupported,
                                contentDescription = "截图不可用",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "截图不可用（设备离线）",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        // 加载中
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
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
                        text = "• ${cocoLabelZh(d.label)}  ${(d.confidence * 100).toInt()}%  " +
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
