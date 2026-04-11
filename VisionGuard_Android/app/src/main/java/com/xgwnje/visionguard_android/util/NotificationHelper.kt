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
import androidx.core.app.NotificationCompat
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

        // 报警通知：HIGH 优先级，声音+振动
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "报警通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "VisionGuard 检测到目标时发送"
            enableVibration(true)
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

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ ${alert.deviceName}：$topLabel")
            .setContentText("${alert.detections.size} 个目标  ${formatTime(alert.timestamp)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)

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
            .setContentTitle("VisionGuard 守护中")
            .setContentText(stateText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
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
