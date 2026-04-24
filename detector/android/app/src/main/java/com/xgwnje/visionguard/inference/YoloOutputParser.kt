package com.xgwnje.visionguard.inference

import android.graphics.RectF
import android.util.Log
import com.xgwnje.visionguard.data.model.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO26 ONNX 输出解析器。
 *
 * yolo26 导出为 ONNX 后，输出格式为 [1, 300, 6]，
 * 其中 6 = [x1, y1, x2, y2, conf, class_id]，已内置 NMS。
 * 坐标已是绝对像素值（0~inputSize 范围），本类仅做边界裁剪并封装为 Detection 对象。
 */
class YoloOutputParser(
    val inputSize: Int = 320,
    private val numClasses: Int = 80
) {

    companion object {
        private const val TAG = "VG_Parser"
        private const val MAX_DETECTIONS = 300
        private const val VALUES_PER_DETECTION = 6

        // COCO 80 类标签
        private val COCO_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }

    /**
     * 解析 ONNX 原始输出并过滤低置信度检测框。
     *
     * @param output ONNX 模型原始输出 float 数组
     * @param confidenceThreshold 置信度阈值，低于此值的检测框被过滤
     * @param iouThreshold NMS IoU 阈值（yolo26 ONNX 已内置 NMS，此参数保留用于兼容性）
     * @return 检测框列表
     */
    fun parse(
        output: FloatArray,
        confidenceThreshold: Float,
        iouThreshold: Float = 0.45f
    ): List<Detection> {
        if (output.isEmpty()) {
            Log.w(TAG, "Empty output array")
            return emptyList()
        }

        val detections = mutableListOf<Detection>()

        try {
            // 输出格式: [1, 300, 6]，展平后长度为 1800
            val expectedSize = MAX_DETECTIONS * VALUES_PER_DETECTION

            if (output.size < expectedSize) {
                Log.w(TAG, "Output size ${output.size} smaller than expected $expectedSize")
                return emptyList()
            }

            for (i in 0 until MAX_DETECTIONS) {
                val offset = i * VALUES_PER_DETECTION

                val x1 = output[offset]
                val y1 = output[offset + 1]
                val x2 = output[offset + 2]
                val y2 = output[offset + 3]
                val conf = output[offset + 4]
                val classId = output[offset + 5].toInt()

                // 过滤低置信度和无效类别
                if (conf < confidenceThreshold || classId < 0 || classId >= numClasses) {
                    continue
                }

                // yolo26 ONNX 输出已是绝对像素值（0~inputSize 范围），直接裁剪边界
                val left = max(0f, x1)
                val top = max(0f, y1)
                val right = min(inputSize.toFloat(), x2)
                val bottom = min(inputSize.toFloat(), y2)

                // 跳过无效框
                if (right <= left || bottom <= top) {
                    continue
                }

                val bbox = RectF(left, top, right, bottom)
                val label = if (classId < COCO_LABELS.size) COCO_LABELS[classId] else "unknown"

                detections.add(
                    Detection(
                        label = label,
                        confidence = conf,
                        bbox = bbox
                    )
                )
            }

            Log.d(TAG, "Parsed ${detections.size} detections from $MAX_DETECTIONS candidates")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing YOLO output", e)
        }

        return detections
    }
}
