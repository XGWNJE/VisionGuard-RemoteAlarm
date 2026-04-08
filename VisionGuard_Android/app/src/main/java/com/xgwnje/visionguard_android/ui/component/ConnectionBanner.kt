package com.xgwnje.visionguard_android.ui.component

// ┌─────────────────────────────────────────────────────────┐
// │ ConnectionBanner.kt                                     │
// │ 角色：顶部连接状态条，四色（绿/黄/灰/红）                  │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard_android.data.remote.WsState

@Composable
fun ConnectionBanner(state: WsState, onlineCount: Int) {
    val bgColor by animateColorAsState(
        targetValue = when (state) {
            WsState.CONNECTED    -> Color(0xFF2E7D32)
            WsState.CONNECTING   -> Color(0xFFF9A825)
            WsState.AUTH_FAILED  -> Color(0xFFC62828)
            WsState.DISCONNECTED -> Color(0xFF616161)
        },
        label = "banner_color"
    )
    val label = when (state) {
        WsState.CONNECTED    -> "● 已连接  ($onlineCount 台设备在线)"
        WsState.CONNECTING   -> "◌ 连接中..."
        WsState.AUTH_FAILED  -> "✕ 认证失败"
        WsState.DISCONNECTED -> "○ 未连接"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
