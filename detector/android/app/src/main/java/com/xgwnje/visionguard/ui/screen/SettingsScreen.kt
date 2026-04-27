package com.xgwnje.visionguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.xgwnje.visionguard.inference.SocWhitelist
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.data.model.MonitorConfig
import com.xgwnje.visionguard.data.remote.WsState

@Composable
fun SettingsScreen(
    config: MonitorConfig,
    deviceName: String,
    onConfigChange: (MonitorConfig) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    connectionState: WsState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 设备名
            ConfigSectionTitle(title = "设备名称")
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChange,
                label = { Text("显示在接收端的名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 模型选择
            ConfigSectionTitle(title = "模型选择")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModelOption(
                    label = "yolo26n（轻量）",
                    selected = config.modelName == "yolo26n",
                    onSelect = { onConfigChange(config.copy(modelName = "yolo26n")) },
                    modifier = Modifier.weight(1f)
                )
                ModelOption(
                    label = "yolo26s（精准）",
                    selected = config.modelName == "yolo26s",
                    onSelect = { onConfigChange(config.copy(modelName = "yolo26s")) },
                    modifier = Modifier.weight(1f)
                )
            }

            // 高分辨率模型（仅高端 SoC 可用）
            val isHighEnd = SocWhitelist.isHighEndSoc()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "高分辨率模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isHighEnd) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = if (isHighEnd) "640×640，精度更高，发热更大"
                        else "当前设备不支持高分辨率",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isHighEnd) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                Switch(
                    checked = config.useHighResolution,
                    onCheckedChange = { checked ->
                        val newSize = if (checked && isHighEnd) 640 else 320
                        onConfigChange(config.copy(useHighResolution = checked, inputSize = newSize))
                    },
                    enabled = isHighEnd
                )
            }

            // 置信度滑块
            ConfigSectionTitle(title = "置信度阈值")
            var localConfidence by remember(config.confidence) { mutableFloatStateOf(config.confidence) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${(localConfidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = localConfidence,
                    onValueChange = { localConfidence = it },
                    onValueChangeFinished = { onConfigChange(config.copy(confidence = localConfidence)) },
                    valueRange = 0.1f..0.95f,
                    steps = 16,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 目标采样率
            ConfigSectionTitle(title = "目标采样率")
            var localSamplingRate by remember(config.targetSamplingRate) { mutableIntStateOf(config.targetSamplingRate) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${localSamplingRate} 次/秒",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = localSamplingRate.toFloat(),
                    onValueChange = { localSamplingRate = it.toInt() },
                    onValueChangeFinished = { onConfigChange(config.copy(targetSamplingRate = localSamplingRate)) },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 监控目标
            ConfigSectionTitle(title = "监控目标")
            val targetOptions = listOf(
                "person" to "人",
                "car" to "汽车",
                "truck" to "卡车",
                "bus" to "客车",
                "bicycle" to "自行车",
                "motorcycle" to "摩托车"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                targetOptions.take(3).forEach { (key, label) ->
                    TargetChip(
                        label = label,
                        selected = key in config.targets,
                        onToggle = {
                            val newTargets = if (key in config.targets) {
                                config.targets - key
                            } else {
                                config.targets + key
                            }
                            onConfigChange(config.copy(targets = newTargets))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                targetOptions.drop(3).forEach { (key, label) ->
                    TargetChip(
                        label = label,
                        selected = key in config.targets,
                        onToggle = {
                            val newTargets = if (key in config.targets) {
                                config.targets - key
                            } else {
                                config.targets + key
                            }
                            onConfigChange(config.copy(targets = newTargets))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 警报推送冷却时间
            ConfigSectionTitle(title = "警报推送冷却时间")
            var localCooldown by remember(config.cooldownMs) { mutableIntStateOf((config.cooldownMs / 1000).toInt()) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${localCooldown} 秒",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = localCooldown.toFloat(),
                    onValueChange = { localCooldown = it.toInt() },
                    onValueChangeFinished = { onConfigChange(config.copy(cooldownMs = localCooldown * 1000L)) },
                    valueRange = 1f..300f,
                    steps = 298,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "仅控制报警推送间隔，不影响识别与预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 服务器连接状态 + 重连按钮
            ConfigSectionTitle(title = "服务器连接")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val stateLabel = when (connectionState) {
                    WsState.CONNECTED -> "已连接"
                    WsState.CONNECTING -> "连接中"
                    WsState.DISCONNECTED -> "未连接"
                    WsState.AUTH_FAILED -> "认证失败"
                }
                Text(
                    text = "状态: $stateLabel",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (connectionState == WsState.DISCONNECTED || connectionState == WsState.AUTH_FAILED) {
                    OutlinedButton(onClick = onReconnect) {
                        Text("重连")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ModelOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(modifier = Modifier.padding(start = 8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun TargetChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    )
}
