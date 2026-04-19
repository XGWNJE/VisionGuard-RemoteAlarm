package com.xgwnje.visionguard_android.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object NtpSync {

    private const val TAG = "VG_NTP"
    private const val NTP_PORT = 123
    private const val NTP_EPOCH_DIFF = 2208988800L

    @Volatile
    var offsetMs: Long = 0L
        private set

    @Volatile
    var isSynced: Boolean = false
        private set

    fun now(): Long = System.currentTimeMillis() + offsetMs

    suspend fun sync() = withContext(Dispatchers.IO) {
        val servers = listOf("ntp.aliyun.com", "cn.pool.ntp.org", "ntp.tencent.com")
        for (server in servers) {
            try {
                val offset = queryOffset(server)
                offsetMs = offset
                isSynced = true
                Log.i(TAG, "同步成功 server=$server offset=${offset}ms")
                return@withContext
            } catch (e: Exception) {
                Log.w(TAG, "$server 失败: ${e.message}")
            }
        }
        Log.w(TAG, "所有服务器均失败，使用本地时钟")
    }

    private fun queryOffset(server: String): Long {
        val ntpData = ByteArray(48)
        ntpData[0] = 0x1B // LI=0, VN=3, Mode=3

        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val address = InetAddress.getByName(server)
            val request = DatagramPacket(ntpData, ntpData.size, address, NTP_PORT)

            val t1 = System.currentTimeMillis()
            socket.send(request)

            val response = DatagramPacket(ByteArray(48), 48)
            socket.receive(response)
            val t4 = System.currentTimeMillis()

            val data = response.data

            val rxSec = data.readUInt32(32)
            val rxFrac = data.readUInt32(36)
            val txSec = data.readUInt32(40)
            val txFrac = data.readUInt32(44)

            val t2 = (rxSec - NTP_EPOCH_DIFF) * 1000 + rxFrac * 1000 / 0x100000000L
            val t3 = (txSec - NTP_EPOCH_DIFF) * 1000 + txFrac * 1000 / 0x100000000L

            return ((t2 - t1) + (t3 - t4)) / 2
        }
    }

    private fun ByteArray.readUInt32(offset: Int): Long {
        return ((this[offset].toLong() and 0xFF) shl 24) or
               ((this[offset + 1].toLong() and 0xFF) shl 16) or
               ((this[offset + 2].toLong() and 0xFF) shl 8) or
               (this[offset + 3].toLong() and 0xFF)
    }
}
