package com.xgwnje.visionguard.data.model

import android.graphics.Bitmap

data class AlertEvent(
    val timestamp: Long,
    val detections: List<Detection>,
    val renderedFrame: Bitmap?
)
