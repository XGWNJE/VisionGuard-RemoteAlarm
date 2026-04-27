package com.xgwnje.visionguard.service

// ┌─────────────────────────────────────────────────────────┐
// │ AlertService.kt                                         │
// │ 角色：报警判定与冷却管理                                 │
// │ 规则：冷却期内不重复报警；满足条件时绘制报警帧并发射事件 │
// └─────────────────────────────────────────────────────────┘

import android.graphics.Bitmap
import android.util.Log
import com.xgwnje.visionguard.data.model.AlertEvent
import com.xgwnje.visionguard.data.model.Detection
import com.xgwnje.visionguard.data.model.MonitorConfig
import com.xgwnje.visionguard.util.NtpSync
import com.xgwnje.visionguard.util.SnapshotRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

private const val TAG = "VG_Alert"

class AlertService(private val scope: CoroutineScope) {

    private val _alertEvents = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 8)
    val alertEvents: SharedFlow<AlertEvent> = _alertEvents

    private var lastAlertTime = 0L
    private val lock = Any()

    /**
     * 评估是否需要报警（冷却锁保护）。
     *
     * @param detections 当前帧检测到的目标（已映射到原始帧坐标）
     * @param frameBitmap 原始帧 Bitmap 的独立副本（本方法负责回收）
     * @param config 监控配置
     * @param timings 链路耗时统计（bitmap/preprocess/infer/parse 等）
     * @return 若触发报警返回 AlertEvent，否则返回 null
     */
    fun evaluate(
        detections: List<Detection>,
        frameBitmap: Bitmap?,
        config: MonitorConfig,
        timings: Map<String, Long> = emptyMap()
    ): AlertEvent? {
        synchronized(lock) {
            if (detections.isEmpty()) {
                frameBitmap?.recycle()
                return null
            }

            val now = System.currentTimeMillis()
            val inCooldown = now - lastAlertTime <= config.cooldownMs

            // 无论是否在冷却期，都绘制帧供 UI 持续显示
            val tAlertStart = System.currentTimeMillis()
            val renderedFrame = if (frameBitmap != null) {
                try {
                    SnapshotRenderer.drawDetections(frameBitmap, detections)
                } catch (e: Exception) {
                    Log.e(TAG, "绘制报警帧失败", e)
                    null
                } finally {
                    frameBitmap.recycle()
                }
            } else null
            val alertMs = System.currentTimeMillis() - tAlertStart

            // 简化表达：只保留本地计算处理总耗时
            val processMs = timings.values.sum() + alertMs
            val finalTimings = mapOf("processMs" to processMs)

            val alertId = java.util.UUID.randomUUID().toString()
            val event = AlertEvent(
                alertId = alertId,
                timestamp = NtpSync.now(),
                detections = detections,
                renderedFrame = renderedFrame,
                timings = finalTimings
            )

            if (!inCooldown) {
                // 冷却期外：更新冷却时间并向服务器推送
                lastAlertTime = now
                scope.launch {
                    _alertEvents.emit(event)
                }
                Log.i(TAG, "报警触发并推送: alertId=$alertId, ${detections.size} 个目标, 标签=${detections.map { it.label }}")
            } else {
                Log.i(TAG, "冷却期内，仅更新显示帧，不推送: alertId=$alertId, ${detections.size} 个目标")
            }

            return event
        }
    }
}
