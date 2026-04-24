package com.xgwnje.visionguard.util

// ┌─────────────────────────────────────────────────────────┐
// │ InferenceDiagnostics.kt                                 │
// │ 角色：推理链路诊断工具 — 在每个关键节点采样数据           │
// │ 用法：运行时调用各 diagnoseXxx 方法，输出结构化日志       │
// └─────────────────────────────────────────────────────────┘

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy

object InferenceDiagnostics {

    private const val TAG = "VG_Diag"

    /** 采样数量上限（避免日志过长） */
    private const val SAMPLE_MAX = 10

    // ═════════════════════════════════════════════════════════
    // 1. CameraX 输入诊断
    // ═════════════════════════════════════════════════════════

    fun diagnoseImageProxy(imageProxy: ImageProxy) {
        Log.i(TAG, "[DIAG-INPUT] ImageProxy: ${imageProxy.width}x${imageProxy.height}, " +
            "format=${imageProxy.format}, " +
            "planes=${imageProxy.planes.size}, " +
            "imageInfo.rotationDegrees=${imageProxy.imageInfo.rotationDegrees}")

        if (imageProxy.planes.size >= 3) {
            val y = imageProxy.planes[0]
            val u = imageProxy.planes[1]
            val v = imageProxy.planes[2]
            Log.i(TAG, "[DIAG-INPUT] Y: rowStride=${y.rowStride}, pixelStride=${y.pixelStride}, remaining=${y.buffer.remaining()}")
            Log.i(TAG, "[DIAG-INPUT] U: rowStride=${u.rowStride}, pixelStride=${u.pixelStride}, remaining=${u.buffer.remaining()}")
            Log.i(TAG, "[DIAG-INPUT] V: rowStride=${v.rowStride}, pixelStride=${v.pixelStride}, remaining=${v.buffer.remaining()}")
        }
    }

    // ═════════════════════════════════════════════════════════
    // 2. Bitmap 诊断
    // ═════════════════════════════════════════════════════════

    fun diagnoseBitmap(bitmap: Bitmap, label: String) {
        val w = bitmap.width
        val h = bitmap.height
        val config = bitmap.config

        // 采样 4 个角 + 中心的像素值
        val samples = listOf(
            Pair(0, 0),
            Pair(w - 1, 0),
            Pair(0, h - 1),
            Pair(w - 1, h - 1),
            Pair(w / 2, h / 2)
        )

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val sb = StringBuilder("[DIAG-BMP][$label] ${w}x${h} config=$config | samples: ")
        for ((x, y) in samples) {
            val p = pixels[y * w + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sb.append("($x,$y)=0x${String.format("%02X%02X%02X", r, g, b)} ")
        }
        Log.i(TAG, sb.toString())
    }

    // ═════════════════════════════════════════════════════════
    // 3. 预处理张量诊断
    // ═════════════════════════════════════════════════════════

    fun diagnoseTensor(inputData: FloatArray, inputSize: Int, label: String) {
        val expected = 3 * inputSize * inputSize
        if (inputData.size != expected) {
            Log.e(TAG, "[DIAG-TENSOR][$label] SIZE MISMATCH: got=${inputData.size}, expected=$expected")
            return
        }

        val planeSize = inputSize * inputSize
        val rPlane = inputData.copyOfRange(0, planeSize)
        val gPlane = inputData.copyOfRange(planeSize, 2 * planeSize)
        val bPlane = inputData.copyOfRange(2 * planeSize, 3 * planeSize)

        fun stats(arr: FloatArray): String {
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var sum = 0.0
            for (v in arr) {
                if (v < min) min = v
                if (v > max) max = v
                sum += v
            }
            val mean = (sum / arr.size).toFloat()
            return "min=$min max=$max mean=${String.format("%.4f", mean)}"
        }

        Log.i(TAG, "[DIAG-TENSOR][$label] size=${inputData.size} shape=[1,3,$inputSize,$inputSize]")
        Log.i(TAG, "[DIAG-TENSOR][$label] R-plane: ${stats(rPlane)}")
        Log.i(TAG, "[DIAG-TENSOR][$label] G-plane: ${stats(gPlane)}")
        Log.i(TAG, "[DIAG-TENSOR][$label] B-plane: ${stats(bPlane)}")

        // 采样前 5 个像素的 RGB 值（确认通道顺序）
        val sb = StringBuilder("[DIAG-TENSOR][$label] first 5 pixels (R,G,B): ")
        for (i in 0 until minOf(5, planeSize)) {
            sb.append("(${String.format("%.3f", rPlane[i])},${String.format("%.3f", gPlane[i])},${String.format("%.3f", bPlane[i])}) ")
        }
        Log.i(TAG, sb.toString())
    }

    // ═════════════════════════════════════════════════════════
    // 4. ONNX 输出诊断
    // ═════════════════════════════════════════════════════════

    fun diagnoseOnnxOutput(output: FloatArray, inputSize: Int, label: String) {
        val expectedFlat = 300 * 6  // [1, 300, 6]
        Log.i(TAG, "[DIAG-OUTPUT][$label] size=${output.size} (expected $expectedFlat)")

        if (output.isEmpty()) {
            Log.e(TAG, "[DIAG-OUTPUT][$label] EMPTY OUTPUT!")
            return
        }

        // 打印前 3 个候选框的原始值
        val sb = StringBuilder("[DIAG-OUTPUT][$label] top 3 raw: ")
        for (i in 0 until minOf(3, 300)) {
            val off = i * 6
            if (off + 5 >= output.size) break
            val x1 = output[off]
            val y1 = output[off + 1]
            val x2 = output[off + 2]
            val y2 = output[off + 3]
            val conf = output[off + 4]
            val cls = output[off + 5].toInt()
            sb.append("[$i](x1=${String.format("%.1f", x1)},y1=${String.format("%.1f", y1)}," +
                "x2=${String.format("%.1f", x2)},y2=${String.format("%.1f", y2)}," +
                "conf=${String.format("%.3f", conf)},cls=$cls) ")
        }
        Log.i(TAG, sb.toString())

        // 统计置信度分布
        var nonZeroCount = 0
        var maxConf = -1f
        var maxConfIdx = -1
        for (i in 0 until minOf(300, output.size / 6)) {
            val conf = output[i * 6 + 4]
            if (conf > 0.001f) nonZeroCount++
            if (conf > maxConf) {
                maxConf = conf
                maxConfIdx = i
            }
        }
        Log.i(TAG, "[DIAG-OUTPUT][$label] non-zero conf count=$nonZeroCount, maxConf=${String.format("%.3f", maxConf)} at idx=$maxConfIdx")
    }

    // ═════════════════════════════════════════════════════════
    // 5. 检测框解析结果诊断
    // ═════════════════════════════════════════════════════════

    fun diagnoseDetections(
        allDetections: List<com.xgwnje.visionguard.data.model.Detection>,
        filteredDetections: List<com.xgwnje.visionguard.data.model.Detection>,
        confidenceThreshold: Float,
        targets: Set<String>
    ) {
        Log.i(TAG, "[DIAG-PARSE] total=${allDetections.size}, filtered=${filteredDetections.size}, " +
            "threshold=$confidenceThreshold, targets=$targets")

        if (allDetections.isNotEmpty()) {
            val sb = StringBuilder("[DIAG-PARSE] top detections: ")
            for (d in allDetections.take(5)) {
                sb.append("${d.label}(${String.format("%.2f", d.confidence)})[${d.bbox}] ")
            }
            Log.i(TAG, sb.toString())
        }

        if (filteredDetections.isNotEmpty()) {
            val sb = StringBuilder("[DIAG-PARSE] FILTERED HITS: ")
            for (d in filteredDetections.take(5)) {
                sb.append("${d.label}(${String.format("%.2f", d.confidence)})[${d.bbox}] ")
            }
            Log.i(TAG, sb.toString())
        }
    }

    // ═════════════════════════════════════════════════════════
    // 6. 报警诊断
    // ═════════════════════════════════════════════════════════

    fun diagnoseAlertEvent(event: com.xgwnje.visionguard.data.model.AlertEvent?) {
        if (event == null) {
            Log.i(TAG, "[DIAG-ALERT] NO ALERT (cooldown or empty detections)")
        } else {
            Log.i(TAG, "[DIAG-ALERT] ALERT FIRED! detections=${event.detections.size}, " +
                "renderedFrame=${if (event.renderedFrame != null) "${event.renderedFrame.width}x${event.renderedFrame.height}" else "null"}")
        }
    }
}
