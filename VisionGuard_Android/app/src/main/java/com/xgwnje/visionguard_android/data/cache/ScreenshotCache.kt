package com.xgwnje.visionguard_android.data.cache

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "VG_ScreenshotCache"

class ScreenshotCache(context: Context) {

    private val cacheDir = File(context.filesDir, "screenshots")

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    /** 保存 JPEG 字节到磁盘 */
    fun save(alertId: String, jpegBytes: ByteArray): File {
        val file = File(cacheDir, "$alertId.jpg")
        file.writeBytes(jpegBytes)
        Log.d(TAG, "截图已缓存: ${file.name} (${jpegBytes.size} bytes)")
        return file
    }

    /** 获取缓存文件，不存在返回 null */
    fun getFile(alertId: String): File? {
        val file = File(cacheDir, "$alertId.jpg")
        return if (file.exists()) file else null
    }

    /** 是否已缓存 */
    fun isCached(alertId: String): Boolean =
        File(cacheDir, "$alertId.jpg").exists()

    /** 清除所有缓存 */
    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "截图缓存已清空")
    }
}
