package com.xgwnje.visionguard.service

// ┌─────────────────────────────────────────────────────────┐
// │ MonitorService.kt                                       │
// │ 角色：主监控循环 — CameraX 帧处理 → 推理 → 报警判定      │
// │ 规则：AtomicBoolean 防重入；ImageProxy 必须 close       │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.xgwnje.visionguard.data.model.AlertEvent
import com.xgwnje.visionguard.data.model.Detection
import com.xgwnje.visionguard.data.model.MonitorConfig
import com.xgwnje.visionguard.inference.ImagePreprocessor
import com.xgwnje.visionguard.inference.OnnxInferenceEngine
import com.xgwnje.visionguard.inference.YoloOutputParser
import com.xgwnje.visionguard.util.InferenceDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VG_Monitor"

class MonitorService(
    private val context: Context,
    private val inferenceEngine: OnnxInferenceEngine,
    preprocessor: ImagePreprocessor,
    parser: YoloOutputParser,
    private val alertService: AlertService,
    private val scope: CoroutineScope
) {

    /** 热替换预处理组件（分辨率切换时） */
    var preprocessor: ImagePreprocessor = preprocessor
        private set

    /** 热替换输出解析组件（分辨率切换时） */
    var parser: YoloOutputParser = parser
        private set

    /** 更新 preprocessor / parser 引用 */
    fun updateComponents(newPreprocessor: ImagePreprocessor, newParser: YoloOutputParser) {
        preprocessor = newPreprocessor
        parser = newParser
        Log.i(TAG, "Components updated: preprocessor.inputSize=${newPreprocessor.inputSize}, parser.inputSize=${newParser.inputSize}")
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 滑动窗口：记录最近 5 秒内处理帧的时间戳，用于计算实际采样率 */
    private val frameTimestamps = ArrayDeque<Long>()
    private val _actualSamplingRate = MutableStateFlow(0f)
    val actualSamplingRate: StateFlow<Float> = _actualSamplingRate.asStateFlow()

    val alertEvents: SharedFlow<AlertEvent> = alertService.alertEvents

    /** 当前识别帧（供 UI 持续显示，不受冷却期影响） */
    private val _latestDetectionFrame = MutableStateFlow<Bitmap?>(null)
    val latestDetectionFrame: StateFlow<Bitmap?> = _latestDetectionFrame.asStateFlow()

    private val processing = AtomicBoolean(false)

    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig()

    @Volatile
    private var paused = false

    @Volatile
    private var lastFrameTime = 0L

    /** 手动抓拍请求 */
    private val snapshotRequested = AtomicBoolean(false)

    /** 抓拍画面保持截止时间（3 秒） */
    @Volatile
    private var snapshotHoldUntil = 0L

    /** 请求手动抓拍一帧 */
    fun requestSnapshot() {
        snapshotRequested.set(true)
        Log.i(TAG, "手动抓拍已请求")
    }

    /** 启动监控 */
    fun start(config: MonitorConfig) {
        currentConfig = config
        _isRunning.value = true
        paused = false
        frameCounter = 0
        lastCallbackTime = System.currentTimeMillis()
        Log.i(TAG, "监控已启动: model=${config.modelName}, inputSize=${config.inputSize}, samplingRate=${config.targetSamplingRate}次/秒")

        // 启动 watchdog：5 秒内未收到 CameraX 回调则告警
        scope.launch {
            var checkCount = 0
            while (_isRunning.value) {
                kotlinx.coroutines.delay(5000)
                checkCount++
                val elapsed = System.currentTimeMillis() - lastCallbackTime
                if (elapsed > 5000) {
                    Log.e(TAG, "[WATCHDOG-$checkCount] ${elapsed}ms 未收到 CameraX 回调! " +
                        "isRunning=$_isRunning.value, paused=$paused, processing=${processing.get()}")
                } else {
                    Log.i(TAG, "[WATCHDOG-$checkCount] 正常: ${elapsed}ms 前有回调, frames=$frameCounter")
                }
            }
        }
    }

    /** 停止监控 */
    fun stop() {
        _isRunning.value = false
        paused = false
        _actualSamplingRate.value = 0f
        frameTimestamps.clear()
        _latestDetectionFrame.value = null
        Log.i(TAG, "监控已停止")
    }

    /** 暂停监控（保留 isRunning=true，但跳过帧处理） */
    fun pause() {
        paused = true
        Log.i(TAG, "监控已暂停")
    }

    /** 恢复监控 */
    fun resume() {
        paused = false
        Log.i(TAG, "监控已恢复")
    }

    /** 热更新配置 */
    fun updateConfig(config: MonitorConfig) {
        currentConfig = config
        Log.i(TAG, "配置已更新: confidence=${config.confidence}, targets=${config.targets}")
    }

    /**
     * 由 CameraX ImageAnalysis.Analyzer 调用。
     *
     * 处理流程：
     * 1. 防重入检查
     * 2. 图像预处理 → ONNX 推理 → 输出解析
     * 3. 将检测框坐标从 inputSize 映射到原始帧尺寸
     * 4. 目标过滤 → 报警判定（传入 Bitmap copy，避免 lifecycle 冲突）
     * 5. 计算实际 FPS
     */
    /** 帧计数器（用于保存测试帧） */
    private var frameCounter = 0

    /** 上次收到 CameraX 回调的时间（用于 watchdog） */
    private var lastCallbackTime = 0L

    fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        lastCallbackTime = now  // watchdog 计时

        // ── 路径 1：未启动或已暂停 ──
        if (!_isRunning.value) {
            Log.d(TAG, "[SKIP] !isRunning → close ImageProxy")
            imageProxy.close()
            return
        }
        if (paused) {
            Log.d(TAG, "[SKIP] paused → close ImageProxy")
            imageProxy.close()
            return
        }

        // ── 路径 2：采样率节流 ──
        val config = currentConfig
        val intervalMs = 1000L / config.targetSamplingRate.coerceAtLeast(1)
        if (now - lastFrameTime < intervalMs) {
            imageProxy.close()
            return
        }
        lastFrameTime = now

        // ── 路径 3：防重入 ──
        if (processing.getAndSet(true)) {
            Log.d(TAG, "[SKIP] processing=true → close ImageProxy")
            imageProxy.close()
            return
        }

        val frameStart = System.currentTimeMillis()
        var bitmap: Bitmap? = null
        frameCounter++

        try {
            Log.i(TAG, "========================================")
            Log.i(TAG, "[FRAME-$frameCounter] 开始处理: ${imageProxy.width}x${imageProxy.height}, rotation=${imageProxy.imageInfo.rotationDegrees}, format=${imageProxy.format}")
            InferenceDiagnostics.diagnoseImageProxy(imageProxy)

            // 1. ImageProxy → Bitmap
            val t1 = System.currentTimeMillis()
            val rawBitmap = preprocessor.toBitmap(imageProxy)
            if (rawBitmap == null) {
                Log.e(TAG, "[FRAME-$frameCounter] toBitmap() 返回 null，跳过")
                return@processFrame
            }
            val tBitmap = System.currentTimeMillis() - t1
            Log.i(TAG, "[FRAME-$frameCounter] Bitmap: ${rawBitmap.width}x${rawBitmap.height} config=${rawBitmap.config}, 耗时=${tBitmap}ms")
            InferenceDiagnostics.diagnoseBitmap(rawBitmap, "raw")

            // 1.5 中心裁切 + 遮罩涂黑（基于实际 Bitmap 尺寸）
            val tCrop = System.currentTimeMillis()
            val (croppedBitmap, cropOffset) = preprocessor.cropAndMask(
                rawBitmap,
                config.digitalZoom,
                config.maskRegions
            )
            // offsetX/Y 为原始帧 → 裁切后帧的偏移量；
            // 当前报警绘制直接在 croppedBitmap 上进行，检测框也基于此坐标系，故暂不需要加回偏移。
            val (offsetX, offsetY) = cropOffset
            bitmap = croppedBitmap  // 后续报警截图使用裁切后的 Bitmap
            if (croppedBitmap !== rawBitmap) {
                rawBitmap.recycle()  // 裁切后释放原始 Bitmap
            }
            val tCropMask = System.currentTimeMillis() - tCrop
            Log.i(TAG, "[FRAME-$frameCounter] 裁切+遮罩: zoom=${config.digitalZoom}, offset=($offsetX,$offsetY), bitmap=${bitmap.width}x${bitmap.height}, 耗时=${tCropMask}ms")

            // 保存第 1、5、10 帧用于离线验证
            if (frameCounter <= 10 && (frameCounter == 1 || frameCounter % 5 == 0)) {
                saveDebugBitmap(bitmap, "frame_${frameCounter}_preprocess")
            }

            // 2. 预处理
            val t2 = System.currentTimeMillis()
            val inputData = preprocessor.preprocess(bitmap)
            val tPreprocess = System.currentTimeMillis() - t2
            Log.i(TAG, "[FRAME-$frameCounter] 预处理完成: size=${inputData.size}, 耗时=${tPreprocess}ms")
            InferenceDiagnostics.diagnoseTensor(inputData, config.inputSize, "input")

            // 3. ONNX 推理
            val t3 = System.currentTimeMillis()
            val shape = longArrayOf(1, 3, config.inputSize.toLong(), config.inputSize.toLong())
            val output = inferenceEngine.run(inputData, shape)
            val tInference = System.currentTimeMillis() - t3
            Log.i(TAG, "[FRAME-$frameCounter] 推理完成: output size=${output.size}, 耗时=${tInference}ms")
            InferenceDiagnostics.diagnoseOnnxOutput(output, config.inputSize, "output")

            // 4. 解析输出
            val t4 = System.currentTimeMillis()
            val detections = parser.parse(output, config.confidence)
            val tParse = System.currentTimeMillis() - t4
            Log.i(TAG, "[FRAME-$frameCounter] 解析完成: ${detections.size} 个检测框, 阈值=${config.confidence}, 耗时=${tParse}ms")

            // 5. 坐标映射到原始帧
            val scaleX = bitmap.width / config.inputSize.toFloat()
            val scaleY = bitmap.height / config.inputSize.toFloat()
            val scaledDetections = detections.map { d ->
                Detection(
                    label = d.label,
                    confidence = d.confidence,
                    bbox = RectF(
                        d.bbox.left * scaleX,
                        d.bbox.top * scaleY,
                        d.bbox.right * scaleX,
                        d.bbox.bottom * scaleY
                    )
                )
            }

            // 6. 过滤目标
            val filteredDetections = scaledDetections.filter { it.label in config.targets }
            InferenceDiagnostics.diagnoseDetections(scaledDetections, filteredDetections, config.confidence, config.targets)

            // 7. 构建链路耗时统计（与 Windows 端对齐）
            val timings = mapOf(
                "captureMs" to tBitmap,
                "preprocessMs" to (tCropMask + tPreprocess),
                "inferMs" to tInference,
                "parseMs" to tParse
            )

            // 8. 报警判定（冷却控制推送频率，不影响 UI 显示）
            val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val alertEvent = alertService.evaluate(filteredDetections, bitmapCopy, config, timings)

            // 手动抓拍优先：若用户请求了抓拍，复制当前帧并显示 3 秒
            if (snapshotRequested.getAndSet(false)) {
                val snapshotBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                _latestDetectionFrame.value = snapshotBitmap
                snapshotHoldUntil = System.currentTimeMillis() + 3000
                Log.i(TAG, "[FRAME-$frameCounter] 手动抓拍已显示，保持 3 秒")
            } else if (System.currentTimeMillis() > snapshotHoldUntil) {
                _latestDetectionFrame.value = alertEvent?.renderedFrame
                if (filteredDetections.isEmpty()) {
                    _latestDetectionFrame.value = null
                }
            }
            InferenceDiagnostics.diagnoseAlertEvent(alertEvent)

            // 8. 滑动窗口计算实际采样率（5秒窗口）
            updateActualSamplingRate()
            val elapsed = System.currentTimeMillis() - frameStart
            Log.i(TAG, "[FRAME-$frameCounter] 帧处理完成: ${elapsed}ms, 实际采样率=${String.format("%.1f", _actualSamplingRate.value)}次/秒")
            Log.i(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "[FRAME-$frameCounter] 帧处理异常", e)
        } finally {
            processing.set(false)
            imageProxy.close()
            bitmap?.recycle()
        }
    }

    /** 滑动窗口更新实际采样率（5 秒窗口） */
    private fun updateActualSamplingRate() {
        val now = System.currentTimeMillis()
        frameTimestamps.addLast(now)
        // 移除超过 5 秒的旧记录
        while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 5000) {
            frameTimestamps.removeFirst()
        }
        val rate = if (frameTimestamps.size > 1) {
            val windowMs = frameTimestamps.last() - frameTimestamps.first()
            if (windowMs > 0) (frameTimestamps.size - 1) * 1000f / windowMs else 0f
        } else 0f
        _actualSamplingRate.value = rate
    }

    /** 保存调试 Bitmap 到 /sdcard/Android/data/.../files/debug/ */
    private fun saveDebugBitmap(bitmap: Bitmap, name: String) {
        try {
            val dir = java.io.File(context.getExternalFilesDir(null), "debug")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "$name.jpg")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.i(TAG, "[DEBUG] 已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "[DEBUG] 保存失败: $name", e)
        }
    }

}
