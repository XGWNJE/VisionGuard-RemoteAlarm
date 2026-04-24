package com.xgwnje.visionguard.data.model

import android.graphics.RectF

data class Detection(
    val label: String,
    val confidence: Float,
    val bbox: RectF
)
