package com.xgwnje.visionguard.inference

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import com.xgwnje.visionguard.util.InferenceDiagnostics
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

/**
 * ONNX Runtime Mobile 推理引擎封装。
 *
 * 负责从 assets 复制模型到内部存储并加载 ONNX 会话，
 * 提供 run() 方法执行推理。
 */
class OnnxInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VG_Inference"
        private const val ASSETS_MODEL_DIR = "models"
        private const val LOCAL_MODEL_DIR = "models"
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var outputName: String? = null
    private var currentModelPath: String? = null

    /** 检查是否已加载模型 */
    val isLoaded: Boolean
        get() = session != null

    /**
     * 加载 ONNX 模型。
     *
     * @param modelFileName 模型文件名，如 "yolo26n_320.onnx"
     * @param inputSize 输入分辨率（仅用于日志记录）
     * @return 是否加载成功
     */
    fun loadModel(modelFileName: String, inputSize: Int): Boolean {
        close()

        return try {
            val localDir = File(context.filesDir, LOCAL_MODEL_DIR)
            if (!localDir.exists()) {
                localDir.mkdirs()
            }

            val modelFile = File(localDir, modelFileName)

            // 如果本地不存在，或文件为空，从 assets 复制
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.i(TAG, "Model not found locally, copying from assets: $modelFileName")
                copyModelFromAssets(modelFileName, modelFile)
            }

            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.e(TAG, "Model file missing or empty: ${modelFile.absolutePath}")
                return false
            }

            env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // XNNPACK 已关闭 — 纯 CPU 执行对照测试
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val newSession = env?.createSession(modelFile.absolutePath, options)
            session = newSession

            // 动态获取输入/输出节点名（兼容不同导出方式）
            inputName = newSession?.inputNames?.firstOrNull()
            outputName = newSession?.outputNames?.firstOrNull()

            currentModelPath = modelFile.absolutePath

            Log.i(TAG, "Model loaded: $modelFileName (inputSize=$inputSize, inputName=$inputName, outputName=$outputName)")
            // 检查 Execution Provider 信息
            try {
                val epInfo = newSession?.let { s ->
                    "providers=${s.javaClass.name}"
                } ?: "null session"
                Log.i(TAG, "Execution Provider info: $epInfo")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get EP info", e)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelFileName", e)
            close()
            // 删除可能损坏的本地文件，下次启动会重新复制
            try {
                val modelFile = File(File(context.filesDir, LOCAL_MODEL_DIR), modelFileName)
                if (modelFile.exists()) {
                    modelFile.delete()
                    Log.w(TAG, "Deleted corrupted model file: ${modelFile.absolutePath}")
                }
            } catch (cleanupEx: Exception) {
                Log.w(TAG, "Failed to cleanup corrupted model", cleanupEx)
            }
            false
        }
    }

    /**
     * 运行 ONNX 推理。
     *
     * @param inputData CHW RGB float 数组，shape = [1, 3, inputSize, inputSize]
     * @param shape 输入张量 shape，如 longArrayOf(1, 3, 320, 320)
     * @return 原始输出 float 数组；失败返回空数组
     */
    fun run(inputData: FloatArray, shape: LongArray): FloatArray {
        val currentSession = session
        val currentEnv = env
        val currentInputName = inputName

        if (currentSession == null || currentEnv == null || currentInputName == null) {
            Log.w(TAG, "Inference called but session not loaded")
            return FloatArray(0)
        }

        return try {
            val inferenceStart = System.currentTimeMillis()

            // 确保 FloatBuffer position=0，避免某些环境下的异常
            val floatBuffer = FloatBuffer.wrap(inputData)
            floatBuffer.rewind()
            val tensor = OnnxTensor.createTensor(currentEnv, floatBuffer, shape)
            val inputs = mapOf(currentInputName to tensor)
            val results = currentSession.run(inputs)
            val inferenceMs = System.currentTimeMillis() - inferenceStart

            val outputTensor = results[0] as? OnnxTensor
            val outputArray = if (outputTensor != null) {
                val buffer = outputTensor.floatBuffer
                buffer.rewind()  // 确保从开头读取
                val arr = FloatArray(buffer.remaining())
                buffer.get(arr)
                val shapeStr = outputTensor.info.shape.contentToString()
                Log.i(TAG, "推理完成: ${inferenceMs}ms, output shape=$shapeStr")
                InferenceDiagnostics.diagnoseOnnxOutput(arr, shape[2].toInt(), "engine")
                arr
            } else {
                Log.e(TAG, "Output tensor is null or not float")
                FloatArray(0)
            }

            tensor.close()
            results.close()
            outputArray
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            FloatArray(0)
        }
    }

    /** 关闭会话并释放资源 */
    fun close() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
        session = null
        inputName = null
        outputName = null

        try {
            env?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing environment", e)
        }
        env = null
        currentModelPath = null
    }

    /** 从 assets 复制模型到本地文件 */
    private fun copyModelFromAssets(assetName: String, destFile: File) {
        try {
            // 删除已存在的旧文件（可能不完整）
            if (destFile.exists()) {
                destFile.delete()
            }

            context.assets.open("$ASSETS_MODEL_DIR/$assetName").use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            Log.i(TAG, "Model copied to: ${destFile.absolutePath} (${destFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets: $assetName", e)
            destFile.delete()
            throw e
        }
    }
}
