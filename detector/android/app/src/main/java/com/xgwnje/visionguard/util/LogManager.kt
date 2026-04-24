package com.xgwnje.visionguard.util

// ┌─────────────────────────────────────────────────────────┐
// │ LogManager.kt                                           │
// │ 角色：轻量日志封装，ERROR 级别自动 WS 上报               │
// │ 规则：DEBUG/INFO/WARN → 仅 logcat；ERROR → logcat + WS │
// └─────────────────────────────────────────────────────────┘

import android.util.Log
import com.xgwnje.visionguard.data.remote.WebSocketClient
import com.xgwnje.visionguard.data.remote.WsState

object LogManager {

    private lateinit var wsClient: WebSocketClient

    /** 初始化，注入 WebSocketClient 用于 ERROR 上报 */
    fun init(client: WebSocketClient) {
        wsClient = client
    }

    /** DEBUG → 仅 logcat */
    fun d(tag: String, msg: String) {
        Log.d("VG_$tag", msg)
    }

    /** INFO → 仅 logcat */
    fun i(tag: String, msg: String) {
        Log.i("VG_$tag", msg)
    }

    /** WARN → 仅 logcat */
    fun w(tag: String, msg: String) {
        Log.w("VG_$tag", msg)
    }

    /** ERROR → logcat + WS 上报 log-report */
    fun e(tag: String, msg: String) {
        Log.e("VG_$tag", msg)
        try {
            if (::wsClient.isInitialized && wsClient.connectionState.value == WsState.CONNECTED) {
                wsClient.sendLogReport("ERROR", tag, msg)
            }
        } catch (_: Exception) {
            // 上报失败不抛异常，避免日志系统自身导致崩溃
        }
    }
}
