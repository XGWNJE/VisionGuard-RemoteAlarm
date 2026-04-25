package com.xgwnje.visionguard.util

// ┌─────────────────────────────────────────────────────────┐
// │ ScreenshotCache.kt                                      │
// │ 角色：报警截图本地磁盘缓存（LRU + 时间 + 大小三重约束）   │
// │ 约束：100MB / 7天 / 2000张上限                           │
// │ 用途：响应接收端 request-screenshot WS 消息              │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "VG_ScreenshotCache"

/** 缓存目录名 */
private const val CACHE_DIR = "alert_screenshots"

/** 最大缓存大小：100 MB */
private const val MAX_CACHE_SIZE_BYTES = 100L * 1024 * 1024

/** 最大缓存条数 */
private const val MAX_CACHE_COUNT = 2000

/** 最大缓存时间：7 天（毫秒） */
private const val MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000

class ScreenshotCache(private val context: Context) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }
    }

    /** 保存截图到本地缓存 */
    fun save(alertId: String, bitmap: Bitmap): Boolean {
        return try {
            ensureCacheConstraints()
            val file = File(cacheDir, "$alertId.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            Log.i(TAG, "截图已缓存: alertId=$alertId size=${file.length()}B")
            true
        } catch (e: Exception) {
            Log.e(TAG, "截图缓存失败: alertId=$alertId", e)
            false
        }
    }

    /** 读取截图字节数组（用于 base64 编码推送） */
    fun readBytes(alertId: String): ByteArray? {
        val file = File(cacheDir, "$alertId.jpg")
        return if (file.exists() && file.length() > 0) {
            try {
                file.readBytes()
            } catch (e: Exception) {
                Log.w(TAG, "读取截图失败: alertId=$alertId", e)
                null
            }
        } else null
    }

    /** 截图是否存在 */
    fun exists(alertId: String): Boolean {
        return File(cacheDir, "$alertId.jpg").exists()
    }

    /** 清理缓存，确保满足三重约束 */
    private fun ensureCacheConstraints() {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        if (files.isEmpty()) return

        val now = System.currentTimeMillis()

        // 1. 按时间清理：删除超过 7 天的文件
        var deleted = files.filter { now - it.lastModified() > MAX_CACHE_AGE_MS }
            .onEach { it.delete() }

        // 2. 按条数清理：超出 2000 条时删除最旧的
        val remaining = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        if (remaining.size > MAX_CACHE_COUNT) {
            val toDelete = remaining.sortedBy { it.lastModified() }
                .take(remaining.size - MAX_CACHE_COUNT)
            toDelete.forEach { it.delete() }
            deleted += toDelete
        }

        // 3. 按大小清理：超出 100MB 时删除最旧的
        val afterCount = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        var totalSize = afterCount.sumOf { it.length() }
        if (totalSize > MAX_CACHE_SIZE_BYTES) {
            val sorted = afterCount.sortedBy { it.lastModified() }
            for (file in sorted) {
                if (totalSize <= MAX_CACHE_SIZE_BYTES) break
                totalSize -= file.length()
                file.delete()
                deleted += file
            }
        }

        if (deleted.isNotEmpty()) {
            Log.i(TAG, "缓存清理完成: 删除 ${deleted.size} 个文件")
        }
    }

    /** 主动清理全部缓存 */
    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "缓存已清空")
    }
}
