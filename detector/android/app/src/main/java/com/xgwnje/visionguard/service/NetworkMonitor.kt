package com.xgwnje.visionguard.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

private const val TAG = "VG_NetworkMonitor"

class NetworkMonitor(private val context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register(onAvailable: () -> Unit, onLost: () -> Unit) {
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "默认网络可用")
                onAvailable()
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "默认网络断开")
                onLost()
            }
        }

        try {
            cm.registerDefaultNetworkCallback(callback!!)
            Log.i(TAG, "网络监听已注册（DefaultNetwork）")
        } catch (e: Exception) {
            Log.w(TAG, "注册网络监听失败: ${e.message}")
        }
    }

    /** 注销网络监听 */
    fun unregister() {
        try {
            callback?.let { cm.unregisterNetworkCallback(it) }
            callback = null
            Log.i(TAG, "网络监听已注销")
        } catch (e: Exception) {
            Log.w(TAG, "注销网络监听失败: ${e.message}")
        }
    }
}
