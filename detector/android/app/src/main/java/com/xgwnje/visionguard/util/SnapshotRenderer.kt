package com.xgwnje.visionguard.util

// ┌─────────────────────────────────────────────────────────┐
// │ SnapshotRenderer.kt                                     │
// │ 角色：在 Bitmap 上绘制检测框和标签                       │
// │ 样式：LimeGreen 边框 + 半透明黑底白字标签               │
// └─────────────────────────────────────────────────────────┘

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.xgwnje.visionguard.data.model.Detection

object SnapshotRenderer {

    private const val STROKE_WIDTH = 4f
    private const val TEXT_SIZE = 28f
    private const val BOX_COLOR = 0xFF32CD32.toInt() // LimeGreen
    private const val LABEL_BG_COLOR = 0xAA000000.toInt() // 半透明黑色

    /**
     * 在 Bitmap 上绘制检测框和标签。
     *
     * @param bitmap 原始帧（不会被修改）
     * @param detections 检测框列表
     * @return 绘制后的新 Bitmap
     */
    fun drawDetections(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val boxPaint = Paint().apply {
            color = BOX_COLOR
            strokeWidth = STROKE_WIDTH
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = TEXT_SIZE
            isAntiAlias = true
        }

        val labelBgPaint = Paint().apply {
            color = LABEL_BG_COLOR
            style = Paint.Style.FILL
        }

        for (detection in detections) {
            // 绘制检测框
            canvas.drawRect(detection.bbox, boxPaint)

            // 标签文字
            val labelText = "${detection.label} ${(detection.confidence * 100).toInt()}%"

            // 计算文字尺寸
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)

            // 标签背景矩形（位于检测框左上角上方）
            val padding = 4f
            val bgLeft = detection.bbox.left
            val bgTop = detection.bbox.top - textBounds.height() - padding * 2
            val bgRight = detection.bbox.left + textBounds.width() + padding * 2
            val bgBottom = detection.bbox.top

            val safeBgTop = if (bgTop < 0) 0f else bgTop
            val safeBgBottom = if (bgTop < 0) textBounds.height() + padding * 2 else bgBottom

            val labelBgRect = RectF(bgLeft, safeBgTop, bgRight, safeBgBottom)
            canvas.drawRect(labelBgRect, labelBgPaint)

            // 绘制文字
            val textX = bgLeft + padding
            val textY = safeBgBottom - padding
            canvas.drawText(labelText, textX, textY, textPaint)
        }

        return mutableBitmap
    }
}
