package com.xgwnje.visionguard.util

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
import androidx.core.app.NotificationCompat
import com.xgwnje.visionguard.MainActivity
import com.xgwnje.visionguard.R
import com.xgwnje.visionguard.data.model.AlertEvent
import com.xgwnje.visionguard.data.model.Detection

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
            "检测报警",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "VisionGuard 检测到目标时发送"
            enableVibration(true)
            enableLights(true)  // 呼吸灯
        }

        // 前台服务常驻通知：LOW 优先级，静默
        val fgChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "检测服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VisionGuard 检测服务运行状态"
        }

        nm.createNotificationChannels(listOf(alertChannel, fgChannel))
    }

    fun buildAlertNotification(
        context: Context,
        alert: AlertEvent,
        notifId: Int,
        largeIcon: Bitmap? = null
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        }
        val fullScreenPi = PendingIntent.getActivity(
            context, notifId + 10000, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ 检测到目标：$topLabel")
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
        return NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("VisionGuard")
            .setContentText("有新警报")
            .setGroup("vg_alerts")
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
    }

    fun buildForegroundNotification(context: Context, stateText: String): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("VisionGuard 检测中")
            .setContentText(stateText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /** 将时间戳解析为 HH:mm:ss 显示 */
    private fun formatTime(timestamp: Long): String {
        return try {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        } catch (_: Exception) { "" }
    }
}
