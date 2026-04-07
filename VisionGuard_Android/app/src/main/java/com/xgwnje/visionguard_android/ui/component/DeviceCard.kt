package com.xgwnje.visionguard_android.ui.component

// ┌─────────────────────────────────────────────────────────┐
// │ DeviceCard.kt                                           │
// │ 角色：设备卡片，含控制按钮 + 参数调节弹窗                  │
// │ 控制：暂停/恢复/停止报警                                  │
// │ 参数：冷却时间 / 置信度 / 监控目标（实时下发）             │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import kotlin.math.roundToInt

@Composable
fun DeviceCard(
    device: DeviceInfo,
    onCommand: (String) -> Unit,                          // "pause" | "resume" | "stop-alarm"
    onSetConfig: (key: String, value: String) -> Unit     // "cooldown"|"confidence"|"targets"
) {
    var showConfigDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 设备名 + 状态 + 参数按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    val statusText = when {
                        !device.online      -> "⬜ 离线"
                        device.isAlarming   -> "🔴 报警中"
                        device.isMonitoring -> "🟢 监控中"
                        else                -> "⚪ 已暂停"
                    }
                    Text(
                        text = statusText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 参数调节入口
                IconButton(
                    onClick = { showConfigDialog = true },
                    enabled = device.online
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "参数调节",
                        tint = if (device.online)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 控制按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val enabled = device.online

                if (!device.isAlarming) {
                    if (device.isMonitoring) {
                        OutlinedButton(
                            onClick = { onCommand("pause") },
                            enabled = enabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("停止推理") }
                    } else {
                        OutlinedButton(
                            onClick = { onCommand("resume") },
                            enabled = enabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("启动推理") }
                    }
                }

                if (device.isAlarming) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse_alpha"
                    )
                    Button(
                        onClick = { onCommand("stop-alarm") },
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier
                            .weight(1f)
                            .alpha(if (enabled) alpha else 0.5f)
                    ) { Text("停止报警", color = Color.White) }
                }
            }
        }
    }

    // 参数调节弹窗
    if (showConfigDialog) {
        DeviceConfigDialog(
            device = device,
            onSetConfig = onSetConfig,
            onDismiss = { showConfigDialog = false }
        )
    }
}

// ── 参数调节弹窗 ───────────────────────────────────────────────

@Composable
private fun DeviceConfigDialog(
    device: DeviceInfo,
    onSetConfig: (key: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    // 本地状态（用户调节中，未发送）
    var cooldownText  by remember { mutableStateOf("5") }
    var confidence    by remember { mutableFloatStateOf(0.45f) }
    var targetsText   by remember { mutableStateOf("") }
    var cooldownError by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参数调节 — ${device.deviceName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // ── 冷却时间 ────────────────────────────────────
                Column {
                    Text("报警冷却时间（秒）", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = cooldownText,
                        onValueChange = {
                            cooldownText = it.filter { c -> c.isDigit() }
                            cooldownError = ""
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            if (cooldownError.isNotEmpty())
                                Text(cooldownError, color = MaterialTheme.colorScheme.error)
                            else
                                Text("范围 1–300 秒，当前报警触发后的最短间隔")
                        },
                        isError = cooldownError.isNotEmpty()
                    )
                    TextButton(onClick = {
                        val v = cooldownText.toIntOrNull()
                        if (v != null && v in 1..300) {
                            onSetConfig("cooldown", v.toString())
                        } else {
                            cooldownError = "请输入 1–300 之间的整数"
                        }
                    }) { Text("应用冷却时间") }
                }

                // ── 置信度阈值 ───────────────────────────────────
                Column {
                    Text(
                        "置信度阈值：${(confidence * 100).roundToInt()}%",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = confidence,
                        onValueChange = { confidence = it },
                        valueRange = 0.10f..0.95f,
                        steps = 16,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "低阈值 = 更敏感（误报↑），高阈值 = 更保守（漏报↑）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        val v = String.format("%.2f", confidence)
                        onSetConfig("confidence", v)
                    }) { Text("应用置信度") }
                }

                // ── 监控目标 ─────────────────────────────────────
                Column {
                    Text("监控目标（逗号分隔）", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = targetsText,
                        onValueChange = { targetsText = it },
                        singleLine = true,
                        placeholder = { Text("例：person,cat  留空=检测全部") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("与 COCO 类名一致，留空表示检测所有目标") }
                    )
                    TextButton(onClick = {
                        onSetConfig("targets", targetsText.trim())
                    }) { Text("应用监控目标") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
