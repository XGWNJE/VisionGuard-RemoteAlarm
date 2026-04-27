package com.xgwnje.visionguard.ui.screen

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.data.model.MaskRegion
import android.util.Log
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * 遮罩编辑器：在摄像头预览帧上手动画矩形遮罩，并设置数码裁切倍率。
 *
 * @param bitmap 摄像头预览帧（作为画布背景，可为 null 时显示灰色占位）
 * @param frameAspectRatio 帧的宽高比（宽/高），必须与实际监控帧一致，确保遮罩位置准确
 * @param initialMasks 初始遮罩列表
 * @param initialZoom 初始裁切倍率
 * @param onConfirm 确认回调，返回遮罩列表和裁切倍率
 * @param onDismiss 取消回调
 */
@Composable
private fun CollapsibleHintDark(
    hint: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起提示" else "展开提示",
                    tint = Color.LightGray.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = if (expanded) "收起提示" else "查看提示",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray.copy(alpha = 0.7f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
fun MaskEditorScreen(
    bitmap: Bitmap? = null,
    frameAspectRatio: Float,
    initialMasks: List<MaskRegion> = emptyList(),
    initialZoom: Float = 1.0f,
    onConfirm: (List<MaskRegion>, Float) -> Unit,
    onDismiss: () -> Unit
) {
    val aspectRatio = frameAspectRatio

    // 遮罩列表（相对坐标 0~1）
    val masks = remember { mutableStateListOf<MaskRegion>().apply { addAll(initialMasks) } }

    // 当前正在拖拽的矩形（相对坐标）
    var draggingRect by remember { mutableStateOf<Rect?>(null) }

    // 数码裁切倍率
    var digitalZoom by remember { mutableFloatStateOf(initialZoom.coerceIn(1.0f, 5.0f)) }

    val scrollState = rememberScrollState()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxCanvasHeight = (screenHeight * 0.45f).coerceAtMost(360.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(scrollState)
    ) {
        // 标题
        Text(
            text = "设置监控区域",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        CollapsibleHintDark(
            hint = "拖拽画矩形遮挡不需要监控的区域，空白处表示全部识别"
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 画布区域：Bitmap + 遮罩层 + 裁切框层
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxCanvasHeight)
                .aspectRatio(aspectRatio),
            contentAlignment = Alignment.Center
        ) {
            // 背景图（摄像头帧或灰色占位）
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "预览帧",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = Color.DarkGray)
                    val gridSize = 40f
                    val w = size.width
                    val h = size.height
                    for (x in 0..(w / gridSize).toInt()) {
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.3f),
                            start = Offset(x * gridSize, 0f),
                            end = Offset(x * gridSize, h),
                            strokeWidth = 1f
                        )
                    }
                    for (y in 0..(h / gridSize).toInt()) {
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.3f),
                            start = Offset(0f, y * gridSize),
                            end = Offset(w, y * gridSize),
                            strokeWidth = 1f
                        )
                    }
                }
            }

            // 遮罩和交互层
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val x = (offset.x / size.width).coerceIn(0f, 1f)
                                val y = (offset.y / size.height).coerceIn(0f, 1f)
                                draggingRect = Rect(Offset(x, y), Size(0f, 0f))
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                draggingRect?.let { rect ->
                                    // 使用当前指针位置（像素）→ 相对坐标，实现实时拖拽扩展
                                    val endX = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val endY = (change.position.y / size.height).coerceIn(0f, 1f)
                                    val left = minOf(rect.left, endX)
                                    val top = minOf(rect.top, endY)
                                    val right = maxOf(rect.left, endX)
                                    val bottom = maxOf(rect.top, endY)
                                    draggingRect = Rect(left, top, right, bottom)
                                }
                            },
                            onDragEnd = {
                                draggingRect?.let { rect ->
                                    if (rect.width > 0.02f && rect.height > 0.02f) {
                                        masks.add(
                                            MaskRegion(
                                                left = rect.left,
                                                top = rect.top,
                                                right = rect.right,
                                                bottom = rect.bottom
                                            )
                                        )
                                    }
                                }
                                draggingRect = null
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                // 绘制已保存的遮罩（半透明红色填充）
                for (mask in masks) {
                    drawRect(
                        color = Color.Red.copy(alpha = 0.4f),
                        topLeft = Offset(mask.left * w, mask.top * h),
                        size = Size(
                            (mask.right - mask.left) * w,
                            (mask.bottom - mask.top) * h
                        )
                    )
                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(mask.left * w, mask.top * h),
                        size = Size(
                            (mask.right - mask.left) * w,
                            (mask.bottom - mask.top) * h
                        ),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // 绘制正在拖拽的矩形
                draggingRect?.let { rect ->
                    drawRect(
                        color = Color.Yellow.copy(alpha = 0.3f),
                        topLeft = Offset(rect.left * w, rect.top * h),
                        size = Size(
                            (rect.right - rect.left) * w,
                            (rect.bottom - rect.top) * h
                        )
                    )
                    drawRect(
                        color = Color.Yellow,
                        topLeft = Offset(rect.left * w, rect.top * h),
                        size = Size(
                            (rect.right - rect.left) * w,
                            (rect.bottom - rect.top) * h
                        ),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // 绘制裁切框（中心虚线框，表示实际推理区域）
                if (digitalZoom > 1.0f) {
                    val cropW = w / digitalZoom
                    val cropH = h / digitalZoom
                    val cropLeft = (w - cropW) / 2
                    val cropTop = (h - cropH) / 2
                    drawRect(
                        color = Color.Cyan,
                        topLeft = Offset(cropLeft, cropTop),
                        size = Size(cropW, cropH),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 工具栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(
                onClick = { if (masks.isNotEmpty()) masks.removeAt(masks.lastIndex) },
                enabled = masks.isNotEmpty()
            ) {
                Text("撤销")
            }
            OutlinedButton(
                onClick = { masks.clear() },
                enabled = masks.isNotEmpty()
            ) {
                Text("清空")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 数码裁切滑块
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "数码裁切",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "${String.format("%.1f", digitalZoom)}x",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Slider(
                value = digitalZoom,
                onValueChange = { digitalZoom = it.coerceIn(1.0f, 5.0f) },
                valueRange = 1.0f..5.0f,
                steps = 35,
                modifier = Modifier.fillMaxWidth()
            )
            CollapsibleHintDark(
                hint = "1x = 全画幅识别，5x = 中心区域放大5倍"
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 确认 / 取消
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    Log.d("MaskEditor", "取消按钮点击")
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("取消")
            }
            Button(
                onClick = {
                    Log.d("MaskEditor", "确认按钮点击，masks=${masks.size}, zoom=$digitalZoom")
                    onConfirm(masks.toList(), digitalZoom)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("确认")
            }
        }
    }
}
