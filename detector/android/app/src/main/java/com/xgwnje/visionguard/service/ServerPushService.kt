package com.xgwnje.visionguard.service

// ┌─────────────────────────────────────────────────────────┐
// │ ServerPushService.kt                                    │
// │ 角色：服务器通信推送服务                                 │
// │ 职责：WS 连接管理、报警上报、命令回执、网络监听          │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.xgwnje.visionguard.AppConstants
import com.xgwnje.visionguard.data.model.AlertEvent
import com.xgwnje.visionguard.data.model.WsAlertMessage
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

    init {
        // 注入网络检测器
        wsClient.networkChecker = { cm.activeNetwork != null }
    }

    /** 连接 WebSocket 服务器 */
    fun connect() {
        scope.launch {
            try {
                val deviceId = settingsRepo.ensureDeviceId()
                Log.i(TAG, "正在连接服务器: ${AppConstants.SERVER_URL}, deviceId=$deviceId")
                wsClient.connect(AppConstants.SERVER_URL, AppConstants.API_KEY, deviceId)

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
     * 发送报警事件。
     *
     * @param event 报警事件
     * @param imageBase64 截图的 Base64 编码（JPEG）
     */
    fun sendAlert(event: AlertEvent, imageBase64: String) {
        scope.launch {
            val deviceId = settingsRepo.ensureDeviceId()
            val message = WsAlertMessage(
                deviceId = deviceId,
                deviceName = "Android-Detector",
                timestamp = isoNow(),
                detections = event.detections,
                imageBase64 = imageBase64
            )
            val sent = wsClient.sendAlert(message)
            if (sent) {
                Log.i(TAG, "报警已发送: ${event.detections.size} 个目标")
            } else {
                Log.w(TAG, "报警发送失败: WS 未连接")
            }
        }
    }

    /** 发送命令回执 */
    fun sendCommandAck(command: String, success: Boolean) {
        val sent = wsClient.sendCommandAck(command, success)
        if (sent) {
            Log.i(TAG, "命令回执已发送: $command success=$success")
        } else {
            Log.w(TAG, "命令回执发送失败: WS 未连接")
        }
    }

    /** ISO 8601 格式当前时间 */
    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
