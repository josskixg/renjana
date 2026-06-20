package com.fesu.renjana.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.app.NotificationCompat
import com.fesu.renjana.R
import com.fesu.renjana.utils.RenjanaLog

/**
 * Manages per-instance notifications for running container instances.
 *
 * Strategy:
 * - 1 group notification "Renjana Container" (summary)
 * - Per-instance sub-notification saat running
 * - Tap → re-open instance, Stop action → kill instance
 */
class InstanceNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "InstanceNotifMgr"
        const val CHANNEL_ID = "renjana_instances"
        const val CHANNEL_NAME = "Running Instances"
        const val GROUP_KEY = "com.fesu.renjana.INSTANCES"
        const val SUMMARY_NOTIF_ID = 9001
        const val ACTION_OPEN_INSTANCE = "com.fesu.renjana.OPEN_INSTANCE"
        const val ACTION_STOP_INSTANCE = "com.fesu.renjana.STOP_INSTANCE"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_PACKAGE_NAME = "package_name"

        fun getNotificationId(instanceId: String): Int {
            return instanceId.hashCode() and 0x7FFFFFFF
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications for running container instances"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        RenjanaLog.d(TAG, "Notification channel created")
    }

    /**
     * Show/update notification for a running instance.
     */
    fun showInstanceNotification(instance: RunningInstance) {
        val notifId = getNotificationId(instance.instanceId)

        val openIntent = Intent(context, WrapperActivity::class.java).apply {
            action = ACTION_OPEN_INSTANCE
            putExtra(WrapperActivity.EXTRA_INSTANCE_ID, instance.instanceId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPending = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, InstanceLifecycleService::class.java).apply {
            action = ACTION_STOP_INSTANCE
            putExtra(EXTRA_INSTANCE_ID, instance.instanceId)
        }
        val stopPending = PendingIntent.getService(
            context, notifId + 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (instance.state) {
            InstanceState.RUNNING -> "Running"
            InstanceState.PAUSED -> "Paused in background"
            else -> "Active"
        }

        val iconBitmap = getAppIconBitmap(instance.packageName)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_renjana)
            .setContentTitle(instance.appName)
            .setContentText(statusText)
            .setSubText("Renjana Container")
            .setContentIntent(openPending)
            .setGroup(GROUP_KEY)
            .setOngoing(instance.state.isAlive)
            .setOnlyAlertOnce(true)
            .apply {
                if (iconBitmap != null) {
                    setLargeIcon(iconBitmap)
                }
            }

        if (instance.state.isAlive) {
            builder.addAction(0, "Stop", stopPending)
        }

        notificationManager.notify(notifId, builder.build())

        // Update summary notification
        showSummaryNotification()
        RenjanaLog.d(TAG, "Notification shown for instance ${instance.instanceId}")
    }

    /**
     * Cancel notification for a stopped instance.
     */
    fun cancelInstanceNotification(instanceId: String) {
        notificationManager.cancel(getNotificationId(instanceId))
        showSummaryNotification()
        RenjanaLog.d(TAG, "Notification cancelled for instance $instanceId")
    }

    /**
     * Show/update the group summary notification.
     */
    private fun showSummaryNotification() {
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_renjana)
            .setContentTitle("Renjana Container")
            .setContentText("Managing instances")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(SUMMARY_NOTIF_ID, summary)
    }

    /**
     * Create the foreground service notification (required when service is running).
     */
    fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_renjana)
            .setContentTitle("Renjana Container")
            .setContentText("Container service active")
            .setGroup(GROUP_KEY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun getAppIconBitmap(packageName: String): Bitmap? {
        return try {
            val pm = context.packageManager
            val drawable: Drawable = pm.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
