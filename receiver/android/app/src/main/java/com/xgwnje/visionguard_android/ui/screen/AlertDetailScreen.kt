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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.data.model.cocoLabelZh
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.ui.component.formatTimestamp
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        start = 16.dp,
                        end = 16.dp
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        text = alert?.deviceName ?: "报警详情",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
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
                    text = formatTimestamp(alert.timestamp),
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

                // 链路耗时（简化：本地处理 + 网络中继 + 合计）
                val timings = alert.timings
                val processMs = timings?.get("processMs")
                val relayMs = computeRelayMs(alert)
                if (processMs != null || relayMs != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("链路耗时", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    processMs?.let { TimingRow("本地处理", "${it}ms") }
                    relayMs?.let { TimingRow("网络中继", "${it}ms") }

                    if (processMs != null && relayMs != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TimingRow("合计", "${processMs + relayMs}ms", bold = true)
                    }
                }
            }
        }
    }
}

/**
 * 计算网络中继耗时：检测端 WS 发出 → 接收端收到。
 * 两端已 NTP 校准，直接做差即可。
 */
private fun computeRelayMs(alert: AlertMessage): Long? {
    val wsSent = try {
        alert.wsSentAt?.let { java.time.Instant.parse(it).toEpochMilli() }
    } catch (_: Exception) { null }
    val received = alert.receivedAt.takeIf { it > 0L }
    return if (wsSent != null && received != null) received - wsSent else null
}

@Composable
private fun TimingRow(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
