package com.xgwnje.visionguard.service

// ┌─────────────────────────────────────────────────────────┐
// │ ServerPushService.kt                                    │
// │ 角色：服务器通信推送服务                                 │
// │ 职责：WS 连接管理、报警上报、命令回执、网络监听          │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.util.Log
import com.xgwnje.visionguard.AppConstants
import com.xgwnje.visionguard.data.model.AlertEvent
import com.xgwnje.visionguard.data.model.AlertMeta
import com.xgwnje.visionguard.util.NtpSync
import com.xgwnje.visionguard.data.model.Bbox
import com.xgwnje.visionguard.data.model.ServerDetection
import com.xgwnje.visionguard.data.model.WsCommandMessage
import com.xgwnje.visionguard.data.model.WsSetConfigMessage
import com.xgwnje.visionguard.data.remote.WebSocketClient
import com.xgwnje.visionguard.data.remote.WsState
import com.xgwnje.visionguard.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "VG_ServerPush"

class ServerPushService(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope
) {

    val wsClient = WebSocketClient()

    val connectionState: StateFlow<WsState>
        get() = wsClient.connectionState

    val onCommand: SharedFlow<WsCommandMessage>
        get() = wsClient.onCommand

    val onSetConfig: SharedFlow<WsSetConfigMessage>
        get() = wsClient.onSetConfig

    private val networkMonitor = NetworkMonitor(context)
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        // 注入网络检测器
        wsClient.networkChecker = { cm.activeNetwork != null }
    }

    /** 连接 WebSocket 服务器 */
    fun connect() {
        scope.launch {
            try {
                val deviceId = settingsRepo.ensureDeviceId()
                val deviceName = settingsRepo.getDeviceName()
                Log.i(TAG, "正在连接服务器: ${AppConstants.SERVER_URL}, deviceId=$deviceId, deviceName=$deviceName")
                wsClient.connect(AppConstants.SERVER_URL, AppConstants.API_KEY, deviceId, deviceName)

                // 注册网络监听
                networkMonitor.register(
                    onAvailable = {
                        Log.i(TAG, "网络恢复，通知 WS 客户端")
                        wsClient.onNetworkAvailable()
                    },
                    onLost = {
                        Log.w(TAG, "网络断开，通知 WS 客户端")
                        wsClient.onNetworkLost()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "连接服务器失败", e)
            }
        }
    }

    /** 断开 WebSocket 连接 */
    fun disconnect() {
        Log.i(TAG, "主动断开服务器连接")
        wsClient.disconnect()
        networkMonitor.unregister()
    }

    /**
     * 发送报警事件（HTTP POST multipart，与 Windows 端对齐）。
     *
     * @param event 报警事件
     * @param bitmap 报警帧截图
     */
    fun sendAlert(event: AlertEvent, bitmap: Bitmap?) {
        scope.launch {
            val deviceId = settingsRepo.ensureDeviceId()
            val deviceName = settingsRepo.getDeviceName()
            val meta = AlertMeta(
                deviceId = deviceId,
                deviceName = deviceName,
                timestamp = isoNow(),
                detections = event.detections.map {
                    ServerDetection(
                        label = it.label,
                        confidence = it.confidence,
                        bbox = Bbox(it.bbox.left, it.bbox.top, it.bbox.width(), it.bbox.height())
                    )
                }
            )
            val metaJson = com.google.gson.Gson().toJson(meta)

            val jpegBytes = bitmap?.let { bmpToJpeg(it) }
            if (jpegBytes == null) {
                Log.w(TAG, "报警帧为空，跳过 HTTP 上传")
                return@launch
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("meta", metaJson)
                .addFormDataPart(
                    "screenshot", "alert.jpg",
                    jpegBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("${AppConstants.SERVER_URL}/api/alert")
                .header("X-API-Key", AppConstants.API_KEY)
                .post(body)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "报警上传失败: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) {
                            Log.i(TAG, "报警已上传: ${event.detections.size} 个目标")
                        } else {
                            Log.w(TAG, "报警上传被拒绝: ${it.code} ${it.message}")
                        }
                    }
                }
            })
        }
    }

    /**
     * 发送轻量报警通知（WS，无截图数据）。
     * 截图由检测端本地缓存，接收端按需通过 request-screenshot 拉取。
     *
     * @param alertId 报警 ID
     * @param detections 检测结果
     * @param timestampMs 报警发生时间戳（毫秒，与 Windows 端 alert.Timestamp 对齐）
     * @param timings 链路耗时统计
     */
    fun pushAlert(
        alertId: String,
        detections: List<com.xgwnje.visionguard.data.model.Detection>,
        timestampMs: Long,
        timings: Map<String, Long> = emptyMap()
    ) {
        scope.launch {
            val deviceId = settingsRepo.ensureDeviceId()
            val deviceName = settingsRepo.getDeviceName()
            val timestamp = isoFormat(timestampMs)
            val meta = com.xgwnje.visionguard.data.model.AlertMeta(
                deviceId = deviceId,
                deviceName = deviceName,
                timestamp = timestamp,
                detections = detections.map {
                    com.xgwnje.visionguard.data.model.ServerDetection(
                        label = it.label,
                        confidence = it.confidence,
                        bbox = com.xgwnje.visionguard.data.model.Bbox(it.bbox.left, it.bbox.top, it.bbox.width(), it.bbox.height())
                    )
                }
            )
            val gson = com.google.gson.Gson()
            val msg = mapOf(
                "type" to "alert",
                "alertId" to alertId,
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "timestamp" to timestamp,
                "detections" to gson.fromJson(gson.toJson(meta.detections), List::class.java),
                "timings" to timings,
                "wsSentAt" to isoFormat(NtpSync.now())
            )
            val sent = wsClient.sendRawJson(gson.toJson(msg))
            if (sent) {
                Log.i(TAG, "报警已推送(WS): alertId=$alertId targets=${detections.size}")
            } else {
                Log.w(TAG, "报警推送失败(WS): alertId=$alertId")
            }
        }
    }

    /**
     * Bitmap → JPEG，与 Windows 端对齐：
     * - 宽度超过 960px 时等比缩放
     * - JPEG quality = 65（平衡带宽与画质）
     */
    private fun bmpToJpeg(bitmap: Bitmap): ByteArray {
        val maxW = 960
        val toCompress = if (bitmap.width > maxW) {
            val ratio = maxW.toFloat() / bitmap.width
            val newH = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, maxW, newH, true)
        } else bitmap

        val stream = ByteArrayOutputStream()
        toCompress.compress(Bitmap.CompressFormat.JPEG, 65, stream)
        val bytes = stream.toByteArray()

        if (toCompress !== bitmap) toCompress.recycle()
        return bytes
    }

    /** 发送命令回执 */
    fun sendCommandAck(command: String, success: Boolean, reason: String = "") {
        val sent = wsClient.sendCommandAck(command, success, reason)
        if (sent) {
            Log.i(TAG, "命令回执已发送: $command success=$success")
        } else {
            Log.w(TAG, "命令回执发送失败: WS 未连接")
        }
    }

    /** ISO 8601 格式当前时间（带本地时区偏移，与 Windows 端对齐） */
    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return sdf.format(Date())
    }

    /** 将毫秒时间戳转为 ISO 8601 字符串（与 Windows 端 alert.Timestamp 对齐） */
    private fun isoFormat(timestampMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return sdf.format(Date(timestampMs))
    }
}
