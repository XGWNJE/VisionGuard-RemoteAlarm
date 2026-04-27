package com.xgwnje.visionguard.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import com.xgwnje.visionguard.data.model.MaskRegion
import java.io.ByteArrayOutputStream

/**
 * 图像预处理器。
 *
 * 将 CameraX ImageProxy 或 Bitmap 转换为模型所需的
 * CHW RGB float 数组（shape = [1, 3, inputSize, inputSize]）。
 */
class ImagePreprocessor(val inputSize: Int) {

    companion object {
        private const val TAG = "VG_Preprocess"
    }

    /**
     * 将 ImageProxy 转换为 CHW RGB float 数组。
     *
     * 支持 YUV_420_888 格式，先转为 Bitmap 再缩放处理。
     * 最后会自动调用 imageProxy.close()。
     *
     * @param imageProxy CameraX 图像帧
     * @return FloatArray，大小为 1 * 3 * inputSize * inputSize
     */
    fun preprocess(imageProxy: ImageProxy): FloatArray {
        return try {
            val bitmap = toBitmap(imageProxy)
            if (bitmap == null) {
                Log.e(TAG, "Failed to convert ImageProxy to Bitmap")
                return FloatArray(3 * inputSize * inputSize)
            }
            val result = preprocess(bitmap)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error preprocessing ImageProxy", e)
            FloatArray(3 * inputSize * inputSize)
        } finally {
            try {
                imageProxy.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing ImageProxy", e)
            }
        }
    }

    /**
     * 将 Bitmap 转换为 CHW RGB float 数组。
     *
     * 缩放至 inputSize×inputSize，像素值归一化到 0-1，
     * 按 CHW 格式排列（先全部 R，再全部 G，再全部 B）。
     *
     * @param bitmap 输入位图
     * @return FloatArray，大小为 1 * 3 * inputSize * inputSize
     */
    fun preprocess(bitmap: Bitmap): FloatArray {
        return try {
            // 缩放至模型输入尺寸
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

            val pixels = IntArray(inputSize * inputSize)
            scaledBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }

            val floatArray = FloatArray(3 * inputSize * inputSize)
            val pixelCount = inputSize * inputSize

            // CHW 格式：先 R，再 G，再 B
            for (i in 0 until pixelCount) {
                val pixel = pixels[i]
                floatArray[i] = ((pixel shr 16) and 0xFF) / 255.0f                 // R
                floatArray[i + pixelCount] = ((pixel shr 8) and 0xFF) / 255.0f     // G
                floatArray[i + 2 * pixelCount] = (pixel and 0xFF) / 255.0f         // B
            }

            floatArray
        } catch (e: Exception) {
            Log.e(TAG, "Error preprocessing Bitmap", e)
            FloatArray(3 * inputSize * inputSize)
        }
    }

    /**
     * 将 YUV_420_888 ImageProxy 转换为 ARGB Bitmap（已校正旋转）。
     *
     * 处理流程：
     * 1. YUV → JPEG → Bitmap（强制 ARGB_8888）
     * 2. 根据 imageInfo.rotationDegrees 旋转到正确方向
     *
     * 注意：CameraX 后置摄像头图像通常是横向的（rotationDegrees=90），
     * 必须旋转后才能传入 YOLO 模型（模型训练时用的是正常方向）。
     */
    fun toBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            if (imageProxy.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unsupported image format: ${imageProxy.format}")
                return null
            }

            val width = imageProxy.width
            val height = imageProxy.height
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val nv21 = yuv420888ToNv21(imageProxy)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, outputStream)
            val jpegBytes = outputStream.toByteArray()

            // 强制 ARGB_8888，避免 RGB_565 颜色精度不足
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                ?: return null

            // 根据 CameraX 报告的旋转角度校正图像方向
            return if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                decoded.recycle()
                Log.i(TAG, "Bitmap rotated: ${decoded.width}x${decoded.height} → ${rotated.width}x${rotated.height} (rotation=$rotationDegrees)")
                rotated
            } else {
                decoded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
            null
        }
    }

    /**
     * 对 Bitmap 做中心裁切 + 遮罩涂黑。
     *
     * @param bitmap 原始帧（实际采集到的尺寸）
     * @param digitalZoom 裁切倍率，1.0 = 不裁切
     * @param maskRegions 遮罩区域列表（相对坐标 0~1，基于原始帧尺寸）
     * @return Pair<处理后的 Bitmap, Pair<裁切偏移X, 裁切偏移Y>>
     *         若未做任何处理，返回原 Bitmap 和 (0, 0)
     */
    fun cropAndMask(
        bitmap: Bitmap,
        digitalZoom: Float,
        maskRegions: List<MaskRegion>
    ): Pair<Bitmap, Pair<Int, Int>> {
        val zoom = digitalZoom.coerceAtLeast(1.0f)
        val needCrop = zoom > 1.0f
        val needMask = maskRegions.isNotEmpty()

        if (!needCrop && !needMask) {
            return Pair(bitmap, Pair(0, 0))
        }

        // 1. 中心裁切
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height
        val cropWidth = if (needCrop) (srcWidth / zoom).toInt() else srcWidth
        val cropHeight = if (needCrop) (srcHeight / zoom).toInt() else srcHeight
        val cropLeft = if (needCrop) (srcWidth - cropWidth) / 2 else 0
        val cropTop = if (needCrop) (srcHeight - cropHeight) / 2 else 0

        val workingBitmap = if (needCrop) {
            Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        } else {
            bitmap
        }

        // 2. 遮罩涂黑（遮罩坐标基于原始帧尺寸，需映射到裁切后的坐标系）
        if (needMask) {
            val canvas = Canvas(workingBitmap)
            val paint = Paint().apply { color = android.graphics.Color.BLACK }

            for (region in maskRegions) {
                // 遮罩在原始帧上的像素坐标
                val maskLeft = region.left * srcWidth
                val maskTop = region.top * srcHeight
                val maskRight = region.right * srcWidth
                val maskBottom = region.bottom * srcHeight

                // 映射到裁切后的坐标系
                val rectLeft = (maskLeft - cropLeft).coerceIn(0f, workingBitmap.width.toFloat())
                val rectTop = (maskTop - cropTop).coerceIn(0f, workingBitmap.height.toFloat())
                val rectRight = (maskRight - cropLeft).coerceIn(0f, workingBitmap.width.toFloat())
                val rectBottom = (maskBottom - cropTop).coerceIn(0f, workingBitmap.height.toFloat())

                if (rectRight > rectLeft && rectBottom > rectTop) {
                    canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, paint)
                }
            }
        }

        Log.i(TAG, "cropAndMask: src=${srcWidth}x${srcHeight}, zoom=$zoom, " +
                "crop=(${cropLeft},${cropTop},${cropWidth}x${cropHeight}), " +
                "masks=${maskRegions.size}")

        return Pair(workingBitmap, Pair(cropLeft, cropTop))
    }

    /**
     * 将 YUV_420_888 转换为标准 NV21 字节数组。
     *
     * Y 平面逐行复制以跳过 rowStride padding；
     * U/V 平面逐像素处理，兼容 pixelStride != 1 的情况。
     */
    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = ySize / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val uRowStride = image.planes[1].rowStride
        val vRowStride = image.planes[2].rowStride
        val uPixelStride = image.planes[1].pixelStride
        val vPixelStride = image.planes[2].pixelStride

        // 逐行复制 Y 平面，跳过 rowStride padding
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var pos = 0
            for (row in 0 until height) {
                yBuffer.get(nv21, pos, width)
                pos += width
                // 跳过行末 padding（最后一行不需要，且避免 position 越界）
                if (row < height - 1) {
                    yBuffer.position(yBuffer.position() + (yRowStride - width))
                }
            }
        }

        // 逐行逐像素处理 U/V 平面
        var pos = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIdx = row * uRowStride + col * uPixelStride
                val vIdx = row * vRowStride + col * vPixelStride
                nv21[pos++] = vBuffer.get(vIdx)  // NV21: V 在前
                nv21[pos++] = uBuffer.get(uIdx)  // NV21: U 在后
            }
        }

        return nv21
    }
}
