package com.xgwnje.visionguard.data.model

data class MonitorConfig(
    val confidence: Float = 0.45f,
    val cooldownMs: Long = 5000L,
    val targets: Set<String> = setOf("person"),
    val targetSamplingRate: Int = 3,
    val inputSize: Int = 320,
    val modelName: String = "yolo26n",
    val useHighResolution: Boolean = false
)
