package com.xgwnje.visionguard_android.util

// ┌─────────────────────────────────────────────────────────┐
// │ NotificationHelper.kt                                   │
// │ 角色：通知渠道注册 + 报警通知构建                          │
// │ 渠道：ALERT_CHANNEL（HIGH）+ FOREGROUND_CHANNEL（LOW）   │
// │ 对外 API：createChannels(), buildAlertNotification(),    │
// │           buildForegroundNotification()                  │
// └─────────────────────────────────────────────────────────┘

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.xgwnje.visionguard_android.MainActivity
import com.xgwnje.visionguard_android.R
import com.xgwnje.visionguard_android.data.model.AlertMessage

object NotificationHelper {

    const val ALERT_CHANNEL_ID      = "vg_alert"
    const val FOREGROUND_CHANNEL_ID = "vg_foreground"
    const val FOREGROUND_NOTIF_ID   = 1
    const val ALERT_SUMMARY_NOTIF_ID = 999

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 报警通知：HIGH 优先级，声音+振动+呼吸灯
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "报警通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "VisionGuard 检测到目标时发送"
            enableVibration(true)
            enableLights(true)  // 呼吸灯
        }

        // 前台服务常驻通知：LOW 优先级，静默
        val fgChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "后台守护",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VisionGuard 后台运行状态"
        }

        nm.createNotificationChannels(listOf(alertChannel, fgChannel))
    }

    fun buildAlertNotification(
        context: Context,
        alert: AlertMessage,
        notifId: Int,
        largeIcon: Bitmap? = null
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("alertId", alert.alertId)
        }
        val pi = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 第一个检测目标作为标题
        val topLabel = alert.detections.firstOrNull()?.let {
            "${it.label} ${(it.confidence * 100).toInt()}%"
        } ?: "检测到目标"

        // 全屏 Intent：报警时点亮屏幕并显示通知内容
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alertId", alert.alertId)
        }
        val fullScreenPi = PendingIntent.getActivity(
            context, notifId + 10000, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
        setSmallAppIcon(builder, context)
        builder.setContentTitle("⚠ ${alert.deviceName}：$topLabel")
            .setContentText("${alert.detections.size} 个目标  ${formatTime(alert.timestamp)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(fullScreenPi, true)  // 亮屏显示通知
            .setDefaults(NotificationCompat.DEFAULT_ALL)  // 声音+振动+呼吸灯

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(largeIcon)
                    .bigLargeIcon(null as Bitmap?)
            )
        }

        builder.setGroup("vg_alerts")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)

        return builder.build()
    }

    fun buildAlertSummaryNotification(context: Context): Notification {
        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
        setSmallAppIcon(builder, context)
        builder.setContentTitle("VisionGuard")
            .setContentText("有新警报")
            .setGroup("vg_alerts")
            .setGroupSummary(true)
            .setAutoCancel(true)
        return builder.build()
    }

    fun buildForegroundNotification(context: Context, stateText: String): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, FOREGROUND_CHANNEL_ID)
        setSmallAppIcon(builder, context)
        builder.setContentTitle("VG 接收")
            .setContentText(stateText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
        return builder.build()
    }

    /** 将 smallIcon 设为完整应用图标（24dp 标准通知尺寸） */
    private fun setSmallAppIcon(builder: NotificationCompat.Builder, context: Context) {
        val bitmap = getAppIconBitmap(context, sizeDp = 24)
        if (bitmap != null) {
            builder.setSmallIcon(IconCompat.createWithBitmap(bitmap))
        } else {
            builder.setSmallIcon(R.mipmap.ic_launcher_foreground)
        }
    }

    /**
     * 获取应用图标 Bitmap（自适应图标也会合成完整图像）。
     * @param sizeDp 目标尺寸（dp），通知 smallIcon 标准 24dp，largeIcon 默认原图。
     */
    private fun getAppIconBitmap(context: Context, sizeDp: Int = 0): Bitmap? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val density = context.resources.displayMetrics.density
            val w: Int
            val h: Int
            if (sizeDp > 0) {
                w = (sizeDp * density).toInt().coerceAtLeast(1)
                h = w
            } else {
                w = drawable.intrinsicWidth.coerceAtLeast(1)
                h = drawable.intrinsicHeight.coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) { null }
    }

    /** 将 ISO 8601 时间戳解析为 HH:mm:ss 显示 */
    private fun formatTime(iso: String): String {
        return try {
            // 截取时间部分 "2024-01-15T14:30:05.123Z" → "14:30:05"
            val t = iso.substringAfter('T').substringBefore('.')
            t.take(8)
        } catch (_: Exception) { iso }
    }
}
