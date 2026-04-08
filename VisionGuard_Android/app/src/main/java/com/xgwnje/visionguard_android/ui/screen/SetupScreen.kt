package com.xgwnje.visionguard_android.ui.screen

// ┌─────────────────────────────────────────────────────────┐
// │ ConnectionStatusScreen.kt（原 SetupScreen）              │
// │ 角色：连接状态详情页，展示当前状态 + 丰富异常说明 + 手动重试│
// │ 服务器地址/Key 已硬编码，不再提供用户输入                  │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard_android.data.remote.WsState
import com.xgwnje.visionguard_android.service.AlertForegroundService

@Composable
fun SetupScreen(service: AlertForegroundService) {
    val wsState by service.connectionState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── 标题 ──────────────────────────────────────────────
        Text(
            text = "服务器连接",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        // ── 当前状态卡片 ──────────────────────────────────────
        StatusCard(wsState = wsState)

        // ── 异常说明 ──────────────────────────────────────────
        if (wsState != WsState.CONNECTED) {
            TroubleshootCard(wsState = wsState)
        }

        // ── 手动重试按钮 ──────────────────────────────────────
        Button(
            onClick = { service.reconnect() },
            enabled = wsState != WsState.CONNECTING,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (wsState == WsState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("重试", fontSize = 16.sp)
            }
        }
    }
}

// ── 状态卡片 ──────────────────────────────────────────────

@Composable
private fun StatusCard(wsState: WsState) {
    val (icon, label, color) = when (wsState) {
        WsState.CONNECTED    -> Triple("●", "已连接", Color(0xFF2E7D32))
        WsState.CONNECTING   -> Triple("◌", "连接中...", Color(0xFFF9A825))
        WsState.AUTH_FAILED  -> Triple("✕", "认证失败", Color(0xFFC62828))
        WsState.DISCONNECTED -> Triple("○", "未连接 / 已断开", Color(0xFF616161))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(icon, fontSize = 28.sp, color = color)
            Column {
                Text(
                    text = label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                val desc = when (wsState) {
                    WsState.CONNECTED    -> "WebSocket 已就绪，可接收报警推送"
                    WsState.CONNECTING   -> "正在建立连接，请稍候..."
                    WsState.AUTH_FAILED  -> "API Key 与服务器不匹配，需更新 APK"
                    WsState.DISCONNECTED -> "与服务器的连接中断，正在自动重连"
                }
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 故障排查说明卡片 ──────────────────────────────────────

@Composable
private fun TroubleshootCard(wsState: WsState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "排查建议",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            HorizontalDivider()

            when (wsState) {
                WsState.AUTH_FAILED -> {
                    BulletItem("API Key 与服务器 .env 中的 API_KEY 不一致")
                    BulletItem("服务器 API Key 已更换，需要重新打包 APK")
                    BulletItem("联系管理员确认当前有效的 API Key")
                }
                WsState.DISCONNECTED -> {
                    BulletItem("检查手机网络是否正常（Wi-Fi / 移动数据）")
                    BulletItem("确认服务器网络可访问")
                    BulletItem("服务器可能正在重启，稍后自动重连")
                    BulletItem("点击「手动重试连接」立即触发重连")
                }
                WsState.CONNECTING -> {
                    BulletItem("连接超时后将自动退避重连")
                    BulletItem("若长时间停留在此状态，检查服务器是否运行")
                }
                WsState.CONNECTED -> { /* 不显示 */ }
            }
        }
    }
}

@Composable
private fun BulletItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

