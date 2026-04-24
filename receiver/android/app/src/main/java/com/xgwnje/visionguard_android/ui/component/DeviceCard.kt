package com.xgwnje.visionguard_android.ui.component

// ┌─────────────────────────────────────────────────────────┐
// │ DeviceCard.kt                                           │
// │ 角色：设备卡片，含控制按钮 + 参数调节弹窗                  │
// │ 控制：暂停/恢复/停止报警                                  │
// │ 参数：冷却时间 / 置信度 / 监控目标（实时下发）             │
// └─────────────────────────────────────────────────────────┘

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard_android.data.model.DeviceConfig
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import com.xgwnje.visionguard_android.data.model.targetEnZhPairs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DeviceCard(
    device: DeviceInfo,
    initialConfig: DeviceConfig?,
    onCommand: (String) -> Unit,
    onSetConfig: (key: String, value: String) -> Unit
) {
    var showConfigDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 设备名 + 状态
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = device.deviceName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusText = when {
                    !device.online      -> "⬜ 离线"
                    device.isAlarming   -> "🔴 报警中"
                    device.isMonitoring -> "🟢 监控中"
                    !device.isReady     -> "⚪ 选区未设定"
                    else                -> "🟡 已就绪"
                }
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 控制按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val enabled = device.online

                // 开始/停止监控：纯远控按钮，只响应 isMonitoring，不响应 isAlarming
                if (device.isMonitoring) {
                    OutlinedButton(
                        onClick = { onCommand("pause") },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("停止监控") }
                } else {
                    OutlinedButton(
                        onClick = { onCommand("resume") },
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) { Text("开始监控") }
                }

                OutlinedButton(
                    onClick = { showConfigDialog = true },
                    enabled = device.online,
                    modifier = Modifier.weight(1f)
                ) { Text("参数调节") }
            }
        }
    }

    if (showConfigDialog) {
        DeviceConfigDialog(
            device = device,
            initialConfig = initialConfig ?: DeviceConfig(),
            onSetConfig = onSetConfig,
            onDismiss = { showConfigDialog = false }
        )
    }
}

// ── 参数调节弹窗（HorizontalPager 分页）──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceConfigDialog(
    device: DeviceInfo,
    initialConfig: DeviceConfig,
    onSetConfig: (key: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // 各参数的本地编辑状态
    var cooldown by remember { mutableStateOf(initialConfig.cooldown.toFloat()) }
    var confidence by remember { mutableStateOf(initialConfig.confidence.toFloat()) }
    var selectedTargets by remember {
        mutableStateOf(
            initialConfig.targets
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        )
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参数调节 — ${device.deviceName}") },
        text = {
            Column {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) { page ->
                    when (page) {
                        0 -> CooldownPage(cooldown = cooldown) { cooldown = it }
                        1 -> ConfidencePage(confidence = confidence) { confidence = it }
                        2 -> TargetsPage(
                            selectedTargets = selectedTargets,
                            onToggle = { en ->
                                selectedTargets = if (en in selectedTargets)
                                    selectedTargets - en
                                else
                                    selectedTargets + en
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 页面指示器 + 应用按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 3 个小圆点
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(3) { i ->
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (i == pagerState.currentPage)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }

                    // 应用按钮
                    Button(
                        onClick = {
                            when (pagerState.currentPage) {
                                0 -> onSetConfig("cooldown", cooldown.roundToInt().toString())
                                1 -> onSetConfig("confidence", String.format(java.util.Locale.US, "%.2f", confidence))
                                2 -> onSetConfig("targets", selectedTargets.joinToString(","))
                            }
                            onDismiss()
                        },
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Text("应用")
                    }
                }

                // 页面标题
                Text(
                    text = when (pagerState.currentPage) {
                        0 -> "冷却时间（秒）"
                        1 -> "置信度阈值"
                        2 -> "监控目标"
                        else -> ""
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {}
    )
}

// ── 第 1 页：冷却时间 ────────────────────────────────────────────

@Composable
private fun CooldownPage(cooldown: Float, onChange: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${cooldown.roundToInt()} 秒",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Slider(
            value = cooldown,
            onValueChange = { onChange(it) },
            valueRange = 1f..300f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── 第 2 页：置信度 ──────────────────────────────────────────────

@Composable
private fun ConfidencePage(confidence: Float, onChange: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${(confidence * 100).roundToInt()}%",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Slider(
            value = confidence,
            onValueChange = { onChange(it) },
            valueRange = 0.10f..0.95f,
            steps = 16,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── 第 3 页：监控目标（中文 Chip 多选）────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetsPage(
    selectedTargets: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp)
    ) {
        // FlowLayout 效果：用多行 Row 模拟（每行放多个 chip）
        val rows = targetEnZhPairs.chunked(4)
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((en, zh) in row) {
                    FilterChip(
                        selected = en in selectedTargets,
                        onClick = { onToggle(en) },
                        label = { Text(zh, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        leadingIcon = null
                    )
                }
                // 最后一行不足 4 个时填充空白
                if (row.size < 4) {
                    repeat(4 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
